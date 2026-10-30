package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class OpusMtStatus { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, PAUSED, READY, FAILED }

data class OpusMtDownloadState(
    val status: OpusMtStatus = OpusMtStatus.NOT_DOWNLOADED,
    val progress: Int = 0,
    val currentFile: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 4,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String = "",
) {
    val hasCompleteFiles: Boolean
        get() = totalBytes > 0L && downloadedBytes >= totalBytes
}

/**
 * Range-resumable ONNX model downloader. A cancelled or broken download keeps its .part file.
 * A pinned model revision prevents a resumed partial file from mixing revisions.
 */
object OpusMtModelManager {
    private data class ModelFile(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private const val MODEL_REVISION = "4dc37af"
    private const val RUNTIME_VALIDATION_SCHEMA = 2
    private val files = listOf(
        ModelFile(
            "encoder_model_quantized.onnx",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/onnx/encoder_model_quantized.onnx",
            52_875_078L,
            "cb02c542d2ab3010766031f3ce04e9041ffb6bd2df15caa7c41f3aead7cfe6dc",
        ),
        ModelFile(
            "decoder_model_quantized.onnx",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/onnx/decoder_model_quantized.onnx",
            192_882_669L,
            "ddb2e3913bbdb0622545ad589cba0f05d8621cbc14096d13c6dc6cd17d61c13a",
        ),
        ModelFile(
            "source.spm",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/source.spm",
            806_435L,
            "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8",
        ),
        ModelFile(
            "target.spm",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/target.spm",
            804_600L,
            "81dc94efa84e4025ef38d25d5d07429fe41e3eb29d44003f1db6fe98487b0052",
        ),
    )

    private val lock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mutableState = MutableStateFlow(OpusMtDownloadState())
    val state: StateFlow<OpusMtDownloadState> = mutableState

    fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "models/translation/opus-mt-en-zh")

    /** Exact byte total for the four pinned model files. */
    fun downloadSizeBytes(): Long = files.sumOf { it.sizeBytes }

    private fun readyMarker(context: Context) = File(modelDirectory(context), ".validated")

    private fun validationSignature(context: Context): String {
        val dir = modelDirectory(context)
        return buildString {
            append(MODEL_REVISION)
            append(":runtime-validation-").append(RUNTIME_VALIDATION_SCHEMA)
            files.forEach { spec ->
                val file = File(dir, spec.name)
                append('\n').append(spec.name)
                    .append(':').append(file.length())
                    .append(':').append(file.lastModified())
            }
        }
    }

    fun isReady(context: Context): Boolean {
        val dir = modelDirectory(context)
        val marker = readyMarker(context)
        return marker.isFile &&
            files.all { spec ->
                val file = File(dir, spec.name)
                file.isFile && file.length() == spec.sizeBytes
            } &&
            runCatching {
                marker.readText(Charsets.UTF_8).trim() == validationSignature(context)
            }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hasValidContent(file: File, spec: ModelFile): Boolean =
        file.isFile && file.length() >= spec.sizeBytes &&
            runCatching { sha256(file).equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)

    private fun writeReadyMarker(context: Context) {
        val marker = readyMarker(context)
        val temp = File(marker.parentFile, ".validated.tmp")
        temp.writeText(validationSignature(context), Charsets.UTF_8)
        if (marker.exists() && !marker.delete()) throw IOException("无法替换 OPUS-MT 验证标记")
        if (!temp.renameTo(marker)) {
            temp.copyTo(marker, overwrite = true)
            if (!temp.delete()) throw IOException("无法清理 OPUS-MT 验证临时文件")
        }
    }

    /** True only when every pinned model file is present at its expected size. */
    private fun areAllModelFilesPresent(context: Context): Boolean {
        val dir = modelDirectory(context)
        return files.all { spec ->
            File(dir, spec.name).let { it.isFile && it.length() == spec.sizeBytes }
        }
    }

    /** True when a resumable fragment or an incomplete installed file exists. */
    fun hasPartialDownload(context: Context): Boolean {
        if (areAllModelFilesPresent(context)) return false
        val dir = modelDirectory(context)
        return files.any { spec ->
            val part = File(dir, "${spec.name}.part")
            val target = File(dir, spec.name)
            (part.isFile && part.length() > 0L) || (target.isFile && target.length() > 0L)
        }
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (lock.isLocked) return@withContext
        val dir = modelDirectory(context)
        val totalBytes = files.sumOf { it.sizeBytes }
        val ready = isReady(context)
        val allModelFilesPresent = areAllModelFilesPresent(context)
        val partialIndex = files.indexOfFirst { spec ->
            File(dir, "${spec.name}.part").let { it.isFile && it.length() > 0L }
        }.takeIf { it >= 0 }
        val missingIndex = files.indexOfFirst { spec ->
            val target = File(dir, spec.name)
            !target.isFile || target.length() != spec.sizeBytes
        }.takeIf { it >= 0 }
        val currentIndex = partialIndex ?: missingIndex
        val completedBefore = if (currentIndex == null) totalBytes else files.take(currentIndex).sumOf { spec ->
            val finalFile = File(dir, spec.name)
            if (finalFile.isFile && finalFile.length() == spec.sizeBytes) spec.sizeBytes else 0L
        }
        val currentSpec = currentIndex?.let { files[it] }
        val part = currentSpec?.let { File(dir, "${it.name}.part") }
        val finalFile = currentSpec?.let { File(dir, it.name) }
        val currentBytes = when {
            part?.isFile == true -> part.length()
            finalFile?.isFile == true -> finalFile.length().coerceAtMost(currentSpec?.sizeBytes ?: 0L)
            else -> 0L
        }
        val downloaded = (completedBefore + currentBytes).coerceAtMost(totalBytes)
        // Until every final file is present, a full-sized .part file is not a completed model.
        val incompleteDownloaded = downloaded.coerceAtMost((totalBytes - 1L).coerceAtLeast(0L))
        mutableState.value = when {
            ready -> OpusMtDownloadState(
                status = OpusMtStatus.READY, progress = 100, fileIndex = files.size,
                fileCount = files.size, downloadedBytes = totalBytes, totalBytes = totalBytes,
            )
            allModelFilesPresent -> OpusMtDownloadState(
                status = OpusMtStatus.FAILED,
                progress = 100,
                currentFile = "验证翻译效果",
                fileIndex = files.size,
                fileCount = files.size,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                error = "模型文件已完整下载，运行时验证尚未通过；点击重新验证",
            )
            hasPartialDownload(context) -> OpusMtDownloadState(
                status = OpusMtStatus.PAUSED,
                progress = (incompleteDownloaded * 100L / totalBytes).toInt().coerceIn(0, 99),
                currentFile = currentSpec?.name ?: "验证运行环境",
                fileIndex = currentIndex?.plus(1) ?: files.size,
                fileCount = files.size,
                downloadedBytes = incompleteDownloaded,
                totalBytes = totalBytes,
                error = if (allModelFilesPresent) "模型文件已下载，等待重新验证" else "",
            )
            else -> OpusMtDownloadState(
                status = OpusMtStatus.NOT_DOWNLOADED, fileCount = files.size, totalBytes = totalBytes,
            )
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val dir = modelDirectory(app)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 OPUS-MT 模型目录")
        if (isReady(app)) {
            val totalBytes = files.sumOf { it.sizeBytes }
            mutableState.value = OpusMtDownloadState(
                status = OpusMtStatus.READY, progress = 100, fileIndex = files.size, fileCount = files.size,
                downloadedBytes = totalBytes, totalBytes = totalBytes,
            )
            return@withLock
        }
        val totalBytes = files.sumOf { it.sizeBytes }
        AppLogStore.info(
            app,
            "LocalTranslationModel",
            "OPUS-MT 模型下载开始或继续",
            diagnosticContext = mapOf(
                "模型" to "onnx-community/opus-mt-en-zh",
                "模型版本" to MODEL_REVISION,
                "续传或复用已完成文件" to hasPartialDownload(app).toString(),
                "模型目录存在" to dir.isDirectory.toString(),
            ),
        )
        try {
            var completedBytes = 0L
            files.forEachIndexed { index, spec ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val target = File(dir, spec.name)
                if (target.isFile && target.length() >= spec.sizeBytes) {
                    if (hasValidContent(target, spec)) {
                        completedBytes += spec.sizeBytes
                        mutableState.value = OpusMtDownloadState(
                            status = OpusMtStatus.DOWNLOADING,
                            progress = (completedBytes * 100L / totalBytes).toInt().coerceIn(0, 99),
                            currentFile = spec.name,
                            fileIndex = index + 1,
                            fileCount = files.size,
                            downloadedBytes = completedBytes,
                            totalBytes = totalBytes,
                        )
                        return@forEachIndexed
                    }
                    AppLogStore.warning(
                        app,
                        "LocalTranslationModel",
                        "发现已保存模型文件与官方校验值不一致，将重新下载该文件",
                        diagnosticContext = mapOf(
                            "模型文件" to spec.name,
                            "现有文件字节" to target.length().toString(),
                        ),
                    )
                    if (!target.delete()) throw IOException("无法删除校验失败的模型文件：${spec.name}")
                }
                AppLogStore.info(
                    app,
                    "LocalTranslationModel",
                    "开始下载 OPUS-MT 模型文件",
                    diagnosticContext = mapOf(
                        "模型文件" to spec.name,
                        "文件序号" to "${index + 1}/${files.size}",
                        "已有残片字节" to File(dir, "${spec.name}.part").length().toString(),
                    ),
                )
                downloadFile(app, dir, spec, index, completedBytes, totalBytes)
                completedBytes += spec.sizeBytes
            }
            val invalid = files.firstOrNull { spec ->
                val file = File(dir, spec.name)
                !file.isFile || file.length() < spec.sizeBytes
            }
            if (invalid != null) throw IOException("模型文件完整性检查失败：${invalid.name}")
            // Run a real translation before exposing the model as ready. Free the ONNX sessions
            // immediately afterwards so leaving the settings page does not retain ~250 MB of model state.
            withContext(Dispatchers.IO) {
                try {
                    mutableState.value = OpusMtDownloadState(
                        status = OpusMtStatus.VERIFYING,
                        progress = 100,
                        currentFile = "验证翻译效果",
                        fileIndex = files.size,
                        fileCount = files.size,
                        downloadedBytes = totalBytes,
                        totalBytes = totalBytes,
                    )
                    OpusMtRuntime.validate(app)
                    writeReadyMarker(app)
                } finally {
                    OpusMtRuntime.closeLoadedModel()
                }
            }
            if (!isReady(app)) throw IOException("OPUS-MT 验证标记或模型完整性检查失败")
            mutableState.value = OpusMtDownloadState(
                status = OpusMtStatus.READY,
                progress = 100,
                fileIndex = files.size,
                fileCount = files.size,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
            )
            AppLogStore.info(
                app,
                "LocalTranslationModel",
                "OPUS-MT 模型下载、文件检查和运行时加载验证成功",
                diagnosticContext = mapOf(
                    "模型" to "opus-mt-en-zh",
                    "文件数量" to files.size.toString(),
                    "模型总大小字节" to files.sumOf { File(dir, it.name).length() }.toString(),
                    "验证内容" to "ONNX 编码器/解码器与 SentencePiece 分词文件可加载",
                ),
            )
        } catch (cancelled: CancellationException) {
            val stateNow = mutableState.value
            val completeFiles = areAllModelFilesPresent(app)
            val resumableData = hasPartialDownload(app)
            val nextStatus = OpusMtAdditionalDownloadStatePolicy.afterCancellation(
                filesComplete = completeFiles,
                resumableData = resumableData,
            )
            mutableState.value = stateNow.copy(
                status = nextStatus,
                progress = if (completeFiles) 100 else stateNow.progress.coerceAtMost(99),
                currentFile = if (completeFiles) "验证翻译效果" else stateNow.currentFile,
                fileIndex = if (completeFiles) files.size else stateNow.fileIndex,
                downloadedBytes = if (completeFiles) totalBytes else
                    stateNow.downloadedBytes.coerceAtMost((totalBytes - 1L).coerceAtLeast(0L)),
                totalBytes = totalBytes,
                error = if (completeFiles) "模型文件已下载，运行时验证已中断；点击重新验证" else "",
            )
            AppLogStore.info(
                app,
                "LocalTranslationModel",
                if (completeFiles) "OPUS-MT 文件已完整下载，验证任务已中断" else "OPUS-MT 下载已暂停，可继续下载",
                diagnosticContext = mapOf(
                    "模型文件已全部下载" to completeFiles.toString(),
                    "是否存在可续传数据" to resumableData.toString(),
                    "中断前阶段" to stateNow.status.name,
                    "当前文件" to stateNow.currentFile,
                    "文件序号" to "${stateNow.fileIndex}/${stateNow.fileCount}",
                    "已下载字节" to stateNow.downloadedBytes.toString(),
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            val previous = mutableState.value
            val completeFiles = areAllModelFilesPresent(app)
            val resumableData = hasPartialDownload(app)
            val nextStatus = OpusMtAdditionalDownloadStatePolicy.afterFailure(
                filesComplete = completeFiles,
                resumableData = resumableData,
            )
            var rootCause = error
            repeat(8) {
                val next = rootCause.cause
                if (next != null && next !== rootCause) rootCause = next
            }
            val reason = rootCause.message?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: rootCause.javaClass.simpleName
            mutableState.value = previous.copy(
                status = nextStatus,
                progress = if (completeFiles) 100 else previous.progress.coerceAtMost(99),
                currentFile = if (completeFiles) "验证翻译效果" else previous.currentFile,
                fileIndex = if (completeFiles) files.size else previous.fileIndex,
                downloadedBytes = if (completeFiles) totalBytes else
                    previous.downloadedBytes.coerceAtMost((totalBytes - 1L).coerceAtLeast(0L)),
                totalBytes = totalBytes,
                error = reason,
            )
            AppLogStore.error(
                app,
                "LocalTranslationModel",
                "OPUS-MT 模型下载或模型验证失败",
                error,
                diagnosticContext = mapOf(
                    "模型文件已全部下载" to completeFiles.toString(),
                    "是否存在可续传数据" to resumableData.toString(),
                    "失败文件或阶段" to previous.currentFile,
                    "中断前状态" to previous.status.name,
                    "根因类型" to rootCause.javaClass.name,
                    "失败原因" to reason,
                ),
            )
            if (error is Exception) throw error
            throw IOException("OPUS-MT 模型验证失败：$reason", error)
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun downloadFile(
        context: Context,
        dir: File,
        spec: ModelFile,
        index: Int,
        completedBefore: Long,
        totalBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val target = File(dir, spec.name)
        val part = File(dir, "${spec.name}.part")
        var existingBytes = if (part.isFile) part.length() else 0L
        var rangeRetryUsed = false

        // A pause may happen after the final byte is written but before installation. Reuse
        // a completed .part file instead of requesting Range: bytes=size- and downloading again.
        if (existingBytes >= spec.sizeBytes) {
            if (ModelDownloadRangePolicy.isCompletePart(existingBytes, spec.sizeBytes) && hasValidContent(part, spec)) {
                installVerifiedPart(target, part, spec.name)
                return@withContext
            }
            if (part.exists() && !part.delete()) throw IOException("无法清理无效的完整下载残片：${spec.name}")
            existingBytes = 0L
        }

        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val requestBuilder = Request.Builder().url(spec.url)
                .header("Accept-Encoding", "identity")
                .get()
            if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
            var retryFromZero = false
            val call = client.newCall(requestBuilder.build())
            val activeJob = currentCoroutineContext()[Job]
            val cancellationHandle = activeJob?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is java.util.concurrent.CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                if (response.code == 416 && existingBytes > 0L) {
                    if (rangeRetryUsed) throw IOException("服务器连续拒绝 OPUS-MT 续传范围：${spec.name}")
                    if (part.exists() && !part.delete()) throw IOException("无法清理无效的下载残片：${spec.name}")
                    existingBytes = 0L
                    rangeRetryUsed = true
                    retryFromZero = true
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}，下载 ${spec.name} 失败")
                val body = response.body ?: throw IOException("下载响应没有内容：${spec.name}")
                val isPartialResponse = response.code == 206
                if (isPartialResponse && (
                        existingBytes <= 0L ||
                            !ModelDownloadRangePolicy.matches(
                                response.header("Content-Range"),
                                expectedStart = existingBytes,
                                expectedTotal = spec.sizeBytes,
                            )
                    )
                ) {
                    if (rangeRetryUsed) throw IOException("OPUS-MT 断点续传响应范围连续异常：${spec.name}")
                    if (part.exists() && !part.delete()) throw IOException("无法重置范围异常的下载残片：${spec.name}")
                    existingBytes = 0L
                    rangeRetryUsed = true
                    retryFromZero = true
                    return@use
                }
                val append = isPartialResponse && existingBytes > 0L
                if (existingBytes > 0L && !append) {
                    if (part.exists() && !part.delete()) throw IOException("无法重置下载残片：${spec.name}")
                    existingBytes = 0L
                }
                var writtenThisRequest = 0L
                mutableState.value = OpusMtDownloadState(
                    status = OpusMtStatus.DOWNLOADING,
                    progress = ((completedBefore + existingBytes) * 100L / totalBytes).toInt().coerceIn(0, 99),
                    currentFile = spec.name,
                    fileIndex = index + 1,
                    fileCount = files.size,
                    downloadedBytes = completedBefore + existingBytes,
                    totalBytes = totalBytes,
                )
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastPublishedPercent = -1
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            val bytesBeforeWrite = (if (append) existingBytes else 0L) + writtenThisRequest
                            if (!ModelDownloadRangePolicy.canAcceptBytes(bytesBeforeWrite, count.toLong(), spec.sizeBytes)) {
                                throw IOException("服务器返回的模型文件超过预期大小：${spec.name}")
                            }
                            output.write(buffer, 0, count)
                            writtenThisRequest += count.toLong()
                            val currentBytes = (if (append) existingBytes else 0L) + writtenThisRequest
                            val aggregate = completedBefore + currentBytes
                            val percent = (aggregate * 100L / totalBytes).toInt().coerceIn(0, 99)
                            if (percent != lastPublishedPercent) {
                                mutableState.value = OpusMtDownloadState(
                                    status = OpusMtStatus.DOWNLOADING,
                                    progress = percent,
                                    currentFile = spec.name,
                                    fileIndex = index + 1,
                                    fileCount = files.size,
                                    downloadedBytes = aggregate,
                                    totalBytes = totalBytes,
                                )
                                lastPublishedPercent = percent
                            }
                        }
                        output.fd.sync()
                    }
                }
                val finalSize = part.length()
                if (finalSize != spec.sizeBytes) {
                    throw IOException("下载文件长度不匹配：${spec.name}，预期 ${spec.sizeBytes} 字节，当前 ${finalSize} 字节")
                }
                if (!sha256(part).equals(spec.sha256, ignoreCase = true)) {
                    if (!part.delete()) throw IOException("OPUS-MT SHA-256 校验失败，且无法清理残片：${spec.name}")
                    throw IOException("模型文件 SHA-256 校验失败：${spec.name}。已清除损坏文件，可重新下载。")
                }
                installVerifiedPart(target, part, spec.name)
                AppLogStore.info(
                    context,
                    "LocalTranslationModel",
                    "OPUS-MT 模型文件已下载并安装",
                    diagnosticContext = mapOf(
                        "模型文件" to spec.name,
                        "文件大小字节" to target.length().toString(),
                        "下载来源主机" to "huggingface.co",
                        "文件序号" to "${index + 1}/${files.size}",
                    ),
                )
                }
            } catch (error: Exception) {
                if (activeJob?.isCancelled == true && error !is CancellationException) {
                    throw CancellationException("OPUS-MT 模型下载已取消").also { it.initCause(error) }
                }
                throw error
            } finally {
                cancellationHandle?.dispose()
            }
            if (retryFromZero) continue
            if (!validFile(target, spec)) throw IOException("下载文件检查失败：${spec.name}")
            break
        }
    }

    private fun installVerifiedPart(target: File, part: File, name: String) {
        if (target.exists() && !target.delete()) throw IOException("无法替换旧模型文件：$name")
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            if (!part.delete()) throw IOException("模型文件已复制，但无法清理残片：$name")
        }
    }

    suspend fun delete(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            OpusMtRuntime.closeLoadedModel()
            val dir = modelDirectory(context)
            val deleted = if (dir.exists()) dir.deleteRecursively() else true
            if (!deleted) throw IOException("无法删除 OPUS-MT 模型文件")
            mutableState.value = OpusMtDownloadState(
                status = OpusMtStatus.NOT_DOWNLOADED,
                fileCount = files.size,
                totalBytes = files.sumOf { it.sizeBytes },
            )
            AppLogStore.info(
                context.applicationContext,
                "LocalTranslationModel",
                "OPUS-MT 模型已删除",
                diagnosticContext = mapOf("模型目录" to "models/translation/opus-mt-en-zh"),
            )
        }
    }
}
