package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

enum class OpusMtStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, READY, FAILED }

data class OpusMtDownloadState(
    val status: OpusMtStatus = OpusMtStatus.NOT_DOWNLOADED,
    val progress: Int = 0,
    val currentFile: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 4,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String = "",
)

/**
 * Range-resumable ONNX model downloader. A cancelled or broken download keeps its .part file.
 * A pinned model revision prevents a resumed partial file from mixing revisions.
 */
object OpusMtModelManager {
    private data class ModelFile(
        val name: String,
        val url: String,
        val minBytes: Long,
        val sha256: String,
    )

    private const val MIB = 1024L * 1024L
    private const val MODEL_REVISION = "4dc37af"
    private val files = listOf(
        ModelFile(
            "encoder_model_quantized.onnx",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/onnx/encoder_model_quantized.onnx",
            50L * MIB,
            "cb02c542d2ab3010766031f3ce04e9041ffb6bd2df15caa7c41f3aead7cfe6dc",
        ),
        ModelFile(
            "decoder_model_quantized.onnx",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/onnx/decoder_model_quantized.onnx",
            180L * MIB,
            "ddb2e3913bbdb0622545ad589cba0f05d8621cbc14096d13c6dc6cd17d61c13a",
        ),
        ModelFile(
            "source.spm",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/source.spm",
            500L * 1024L,
            "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8",
        ),
        ModelFile(
            "target.spm",
            "https://huggingface.co/onnx-community/opus-mt-en-zh/resolve/$MODEL_REVISION/target.spm",
            500L * 1024L,
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

    private fun readyMarker(context: Context) = File(modelDirectory(context), ".validated")

    private fun validationSignature(context: Context): String {
        val dir = modelDirectory(context)
        return buildString {
            append(MODEL_REVISION)
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
                file.isFile && file.length() >= spec.minBytes
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
        file.isFile && file.length() >= spec.minBytes &&
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

    /** Also returns true when complete model files exist but later files still need downloading. */
    fun hasPartialDownload(context: Context): Boolean {
        val dir = modelDirectory(context)
        if (files.any { spec ->
                val part = File(dir, "${spec.name}.part")
                part.isFile && part.length() > 0L
            }
        ) return true
        return files.any { spec ->
            val target = File(dir, spec.name)
            target.isFile && target.length() >= spec.minBytes
        } && !isReady(context)
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val dir = modelDirectory(context)
        val ready = isReady(context)
        val firstPart = files.firstOrNull { spec ->
            File(dir, "${spec.name}.part").let { it.isFile && it.length() > 0L }
        }
        val firstMissing = files.firstOrNull { spec ->
            val finalFile = File(dir, spec.name)
            !finalFile.isFile || finalFile.length() < spec.minBytes
        }
        val partialSpec = firstPart ?: firstMissing
        val progressFile = partialSpec?.let { spec ->
            File(dir, "${spec.name}.part").takeIf { it.isFile } ?: File(dir, spec.name)
        }
        mutableState.value = when {
            ready -> OpusMtDownloadState(status = OpusMtStatus.READY, progress = 100, fileIndex = files.size, fileCount = files.size)
            hasPartialDownload(context) -> OpusMtDownloadState(
                status = OpusMtStatus.PAUSED,
                currentFile = partialSpec?.name.orEmpty(),
                fileIndex = partialSpec?.let { files.indexOf(it) + 1 } ?: 0,
                fileCount = files.size,
                downloadedBytes = progressFile?.length() ?: 0L,
            )
            else -> OpusMtDownloadState(status = OpusMtStatus.NOT_DOWNLOADED, fileCount = files.size)
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val dir = modelDirectory(app)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 OPUS-MT 模型目录")
        if (isReady(app)) {
            mutableState.value = OpusMtDownloadState(
                status = OpusMtStatus.READY, progress = 100, fileIndex = files.size, fileCount = files.size,
            )
            return@withLock
        }
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
            files.forEachIndexed { index, spec ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val target = File(dir, spec.name)
                if (target.isFile && target.length() >= spec.minBytes) {
                    if (hasValidContent(target, spec)) {
                        mutableState.value = OpusMtDownloadState(
                            status = OpusMtStatus.DOWNLOADING,
                            progress = ((index + 1) * 100 / files.size),
                            currentFile = spec.name,
                            fileIndex = index + 1,
                            fileCount = files.size,
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
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
                downloadFile(app, dir, spec, index)
            }
            val invalid = files.firstOrNull { spec ->
                val file = File(dir, spec.name)
                !file.isFile || file.length() < spec.minBytes
            }
            if (invalid != null) throw IOException("模型文件完整性检查失败：${invalid.name}")
            // Run a real translation before exposing the model as ready, then publish the marker atomically.
            withContext(Dispatchers.IO) {
                OpusMtRuntime.validate(app)
                writeReadyMarker(app)
            }
            if (!isReady(app)) throw IOException("OPUS-MT 验证标记或模型完整性检查失败")
            mutableState.value = OpusMtDownloadState(
                status = OpusMtStatus.READY,
                progress = 100,
                fileIndex = files.size,
                fileCount = files.size,
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
            mutableState.value = stateNow.copy(
                status = if (hasPartialDownload(app)) OpusMtStatus.PAUSED else OpusMtStatus.NOT_DOWNLOADED,
                error = "",
            )
            AppLogStore.info(
                app,
                "LocalTranslationModel",
                "OPUS-MT 下载已暂停，可继续下载",
                diagnosticContext = mapOf(
                    "保留可续传数据" to hasPartialDownload(app).toString(),
                    "当前文件" to stateNow.currentFile,
                    "文件序号" to "${stateNow.fileIndex}/${stateNow.fileCount}",
                    "已下载字节" to stateNow.downloadedBytes.toString(),
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            val partial = hasPartialDownload(app)
            mutableState.value = mutableState.value.copy(
                status = if (partial) OpusMtStatus.PAUSED else OpusMtStatus.FAILED,
                error = error.message ?: error.javaClass.simpleName,
            )
            AppLogStore.error(
                app,
                "LocalTranslationModel",
                "OPUS-MT 模型下载或模型验证失败",
                error,
                diagnosticContext = mapOf(
                    "当前文件" to mutableState.value.currentFile,
                    "已保留可继续下载文件" to partial.toString(),
                    "失败原因" to (error.message ?: error.javaClass.simpleName),
                ),
            )
            throw error
        }
    }

    private suspend fun downloadFile(
        context: Context,
        dir: File,
        spec: ModelFile,
        index: Int,
    ) = withContext(Dispatchers.IO) {
        val target = File(dir, spec.name)
        val part = File(dir, "${spec.name}.part")
        var existingBytes = if (part.isFile) part.length() else 0L
        var allowRangeRetry = true

        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val requestBuilder = Request.Builder().url(spec.url).get()
            if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
            var retryFromZero = false
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 416 && existingBytes > 0L && allowRangeRetry) {
                    AppLogStore.warning(
                        context,
                        "LocalTranslationModel",
                        "下载服务器拒绝续传范围，清理当前残片后重试",
                        diagnosticContext = mapOf(
                            "模型文件" to spec.name,
                            "残片字节" to existingBytes.toString(),
                        ),
                    )
                    if (part.exists() && !part.delete()) throw IOException("无法清理无效的下载残片：${spec.name}")
                    existingBytes = 0L
                    allowRangeRetry = false
                    retryFromZero = true
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}，下载 ${spec.name} 失败")
                val body = response.body ?: throw IOException("下载响应没有内容：${spec.name}")

                val append = existingBytes > 0L && response.code == 206
                if (existingBytes > 0L && !append) {
                    // The server ignored Range. Restart only this file; fully downloaded files remain.
                    if (part.exists() && !part.delete()) throw IOException("无法重置下载残片：${spec.name}")
                    existingBytes = 0L
                }
                val contentLength = body.contentLength()
                val totalLength = if (contentLength >= 0L) existingBytes + contentLength else -1L
                var writtenThisRequest = 0L
                mutableState.value = OpusMtDownloadState(
                    status = OpusMtStatus.DOWNLOADING,
                    progress = if (totalLength > 0L) (existingBytes * 100 / totalLength).toInt().coerceIn(0, 99) else 0,
                    currentFile = spec.name,
                    fileIndex = index + 1,
                    fileCount = files.size,
                    downloadedBytes = existingBytes,
                    totalBytes = totalLength,
                )

                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastPublishedPercent = -1
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            writtenThisRequest += count
                            val nowBytes = existingBytes + writtenThisRequest
                            val percent = if (totalLength > 0L) {
                                (nowBytes * 100 / totalLength).toInt().coerceIn(0, 99)
                            } else 0
                            if (percent != lastPublishedPercent || totalLength <= 0L) {
                                mutableState.value = OpusMtDownloadState(
                                    status = OpusMtStatus.DOWNLOADING,
                                    progress = percent,
                                    currentFile = spec.name,
                                    fileIndex = index + 1,
                                    fileCount = files.size,
                                    downloadedBytes = nowBytes,
                                    totalBytes = totalLength,
                                )
                                lastPublishedPercent = percent
                            }
                        }
                        output.fd.sync()
                    }
                }

                val finalSize = part.length()
                if (finalSize < spec.minBytes) {
                    throw IOException("下载文件不完整：${spec.name}，当前 ${finalSize} 字节")
                }
                if (totalLength > 0L && finalSize != totalLength) {
                    throw IOException("文件长度不匹配：${spec.name}，预期 $totalLength 字节，当前 $finalSize 字节")
                }
                val actualSha256 = sha256(part)
                if (!actualSha256.equals(spec.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException(
                        "模型文件 SHA-256 校验失败：${spec.name}。已清除损坏文件，可重新下载。"
                    )
                }
                if (target.exists() && !target.delete()) throw IOException("无法替换旧模型文件：${spec.name}")
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (!part.delete()) throw IOException("模型文件已复制，但无法清理残片：${spec.name}")
                }
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
                mutableState.value = OpusMtDownloadState(
                    status = OpusMtStatus.DOWNLOADING,
                    progress = 100,
                    currentFile = spec.name,
                    fileIndex = index + 1,
                    fileCount = files.size,
                    downloadedBytes = target.length(),
                    totalBytes = target.length(),
                )
            }
            if (retryFromZero || (part.isFile && part.length() > 0L && !target.isFile)) {
                // Recovery path from HTTP 416: retry the file from the beginning.
                continue
            }
            break
        }
    }

    suspend fun delete(context: Context) = withContext(Dispatchers.IO) {
        OpusMtRuntime.closeLoadedModel()
        val dir = modelDirectory(context)
        val deleted = if (dir.exists()) dir.deleteRecursively() else true
        if (!deleted) throw IOException("无法删除 OPUS-MT 模型文件")
        mutableState.value = OpusMtDownloadState(status = OpusMtStatus.NOT_DOWNLOADED, fileCount = files.size)
        AppLogStore.info(
            context.applicationContext,
            "LocalTranslationModel",
            "OPUS-MT 模型已删除",
            diagnosticContext = mapOf("模型目录" to "models/translation/opus-mt-en-zh"),
        )
    }
}
