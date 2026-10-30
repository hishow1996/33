package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.data.ModelDownloadForegroundService
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Resumable, checksum-verified CTranslate2 packages for directly translated Chinese pairs. */
object OpusMtAdditionalModelManager {
    private data class ModelFile(val name: String, val size: Long, val sha256: String? = null)
    private data class ModelSpec(
        val source: String, val label: String, val officialModelId: String,
        val repository: String, val revision: String, val testText: String, val files: List<ModelFile>,
        val targetLanguage: String = "zh",
    ) {
        fun url(file: ModelFile) = "https://huggingface.co/" + repository + "/resolve/" + revision + "/" + file.name
    }

    private val models = listOf(
        ModelSpec("de", "德语", "Helsinki-NLP/opus-mt-de-ZH", "manancode/opus-mt-de-ZH-ctranslate2-android",
            "cb85d758e103c317539c89ab74c11f2ec09a592e", "Guten Morgen.", listOf(
                ModelFile("config.json", 223L),
                ModelFile("model.bin", 77_963_435L, "54c3266f82f935b61eb3c9fdda78faa594efbefa9347e32f1613ab75730b07bc"),
                ModelFile("shared_vocabulary.json", 1_101_196L),
                ModelFile("source.spm", 816_201L, "b4133b71df5764b5567a9c0df8a17931bbe0299d2c77a7420f0c6c8efa630778"),
                ModelFile("target.spm", 710_619L, "eb1a4b79ddd8f762289f5c92d352c1f499cccfa93ba2ee7e30ee1c4077e557b2"),
            )),
        ModelSpec("fi", "芬兰语", "Helsinki-NLP/opus-mt-fi-ZH", "manancode/opus-mt-fi-ZH-ctranslate2-android",
            "69f7bd09e764e837bc4e0bf90f8e0d5788689666", "Hyvää huomenta.", listOf(
                ModelFile("config.json", 223L),
                ModelFile("model.bin", 78_492_275L, "465714bf716e94dac32148c345f7417e662758a485cfddebabc076a05092880e"),
                ModelFile("shared_vocabulary.json", 1_161_822L),
                ModelFile("source.spm", 823_948L, "ec4208e1be1888d5edd55d8e7bfc3fc84475951a04fc173e9c93fb42ad9a5716"),
                ModelFile("target.spm", 716_019L, "894f5c58af7316774fb8a2ef6ead6a3bda24967a22daf082bbbbca4948f6c571"),
            )),
        ModelSpec("sv", "瑞典语", "Helsinki-NLP/opus-mt-sv-ZH", "manancode/opus-mt-sv-ZH-ctranslate2-android",
            "c15412f1daf274eac89fdf3f0a48f26e8f6fcbee", "God morgon.", listOf(
                ModelFile("config.json", 223L),
                ModelFile("model.bin", 78_044_035L, "6247230f47d538cd40e1aadd0f91198c69fc72fb69428ee8fb19c2c406ea4bfc"),
                ModelFile("shared_vocabulary.json", 1_111_618L),
                ModelFile("source.spm", 800_916L, "b5d2cd87731d58c048864935ec1be3fbca7d2392fdb66202f241bb21ee99fec9"),
                ModelFile("target.spm", 713_467L, "aa0218fd9ae05b8d93ba1fb108e17cc219129aed56f2a8ba76fdeb9366d231c4"),
            )),

    )

    private val lock = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true).build()
    private val mutableStates = MutableStateFlow<Map<String, OpusMtDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, OpusMtDownloadState>> = mutableStates

    fun supportedSources(): Set<String> = models.map { it.source }.toSet()
    fun label(source: String): String = spec(source).label
    fun officialModelId(source: String): String = spec(source).officialModelId
    fun runtimeRepository(source: String): String = spec(source).repository
    fun targetLanguage(source: String): String = spec(source).targetLanguage
    fun downloadSizeBytes(source: String): Long = spec(source).files.sumOf { it.size }
    fun modelDirectory(context: Context, source: String): File =
        File(context.applicationContext.filesDir, "models/translation/opus-mt-" + spec(source).source + "-" +
            spec(source).targetLanguage.uppercase(Locale.ROOT) + "-ct2")

    private fun spec(source: String): ModelSpec {
        val normalized = source.trim().substringBefore('-').lowercase(Locale.ROOT)
        return models.firstOrNull { it.source == normalized }
            ?: throw IllegalArgumentException("不支持的 OPUS-MT 中文翻译语言：" + source)
    }

    private fun marker(context: Context, model: ModelSpec) =
        File(modelDirectory(context, model.source), ".validated")

    private fun signature(context: Context, model: ModelSpec): String {
        val dir = modelDirectory(context, model.source)
        return buildString {
            append(model.revision).append(":ct2-validation-v1")
            model.files.forEach { item ->
                val file = File(dir, item.name)
                append('\n').append(item.name).append(':').append(file.length()).append(':').append(file.lastModified())
            }
        }
    }

    fun isReady(context: Context, source: String): Boolean {
        val model = runCatching { spec(source) }.getOrNull() ?: return false
        val dir = modelDirectory(context, model.source)
        val readyMarker = marker(context, model)
        return readyMarker.isFile &&
            model.files.all { item -> File(dir, item.name).let { it.isFile && it.length() == item.size } } &&
            runCatching { readyMarker.readText(Charsets.UTF_8).trim() == signature(context, model) }.getOrDefault(false)
    }

    fun hasPartialDownload(context: Context, source: String): Boolean {
        val model = runCatching { spec(source) }.getOrNull() ?: return false
        val dir = modelDirectory(context, model.source)
        val allFinalFilesPresent = model.files.all { item ->
            File(dir, item.name).let { it.isFile && it.length() == item.size }
        }
        // A full package that only needs CTranslate2 validation is not a paused transfer.
        if (allFinalFilesPresent) return false
        return model.files.any { item ->
            val part = File(dir, item.name + ".part")
            val finalFile = File(dir, item.name)
            (part.isFile && part.length() > 0L) ||
                (finalFile.isFile && finalFile.length() == item.size)
        }
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val next = mutableStates.value.toMutableMap()
        models.forEach { model ->
            val current = next[model.source]
            val taskId = "opus-mt-${model.source}-zh"
            val taskIsActive = ModelDownloadForegroundService.isExternalTaskActive(taskId)
            if (taskIsActive) return@forEach
            val dir = modelDirectory(app, model.source)
            val total = model.files.sumOf { it.size }
            val firstPartIndex = model.files.indexOfFirst { item ->
                File(dir, item.name + ".part").let { it.isFile && it.length() > 0L }
            }.takeIf { it >= 0 }
            val firstIncompleteIndex = model.files.indexOfFirst { item ->
                File(dir, item.name).let { !it.isFile || it.length() != item.size }
            }.takeIf { it >= 0 }
            val currentIndex = firstPartIndex ?: firstIncompleteIndex
            val allFilesPresent = model.files.all { item ->
                File(dir, item.name).let { it.isFile && it.length() == item.size }
            }
            val completedBefore = if (currentIndex == null) total else model.files.take(currentIndex).sumOf { item ->
                val finalFile = File(dir, item.name)
                if (finalFile.isFile && finalFile.length() == item.size) item.size else 0L
            }
            val currentSpec = currentIndex?.let { model.files[it] }
            val part = currentSpec?.let { File(dir, it.name + ".part") }
            val finalFile = currentSpec?.let { File(dir, it.name) }
            val currentBytes = when {
                part?.isFile == true -> part.length()
                finalFile?.isFile == true -> finalFile.length().coerceAtMost(currentSpec?.size ?: 0L)
                else -> 0L
            }
            val downloaded = (completedBefore + currentBytes).coerceAtMost(total)
            // A full-sized temporary file still needs finalization and integrity validation.
            val incompleteDownloaded = downloaded.coerceAtMost((total - 1L).coerceAtLeast(0L))
            next[model.source] = when {
                isReady(app, model.source) -> OpusMtDownloadState(
                    status = OpusMtStatus.READY, progress = 100,
                    fileIndex = model.files.size, fileCount = model.files.size,
                    downloadedBytes = total, totalBytes = total,
                )
                allFilesPresent -> OpusMtDownloadState(
                    status = OpusMtStatus.FAILED, progress = 100,
                    currentFile = "验证实际翻译",
                    fileIndex = model.files.size, fileCount = model.files.size,
                    downloadedBytes = total, totalBytes = total,
                    error = "模型文件已完整下载，运行时验证尚未通过；点击重新验证",
                )
                hasPartialDownload(app, model.source) -> OpusMtDownloadState(
                    status = OpusMtStatus.PAUSED,
                    progress = (incompleteDownloaded * 100L / total).toInt().coerceIn(0, 99),
                    currentFile = currentSpec?.name ?: "",
                    fileIndex = currentIndex?.plus(1) ?: 0,
                    fileCount = model.files.size,
                    downloadedBytes = incompleteDownloaded,
                    totalBytes = total,
                )
                else -> OpusMtDownloadState(
                    status = OpusMtStatus.NOT_DOWNLOADED, fileCount = model.files.size, totalBytes = total,
                )
            }
        }
        mutableStates.value = next
    }

    private fun publish(source: String, state: OpusMtDownloadState) {
        mutableStates.value = mutableStates.value + (source to state)
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

    private fun validFile(file: File, item: ModelFile): Boolean =
        file.isFile && file.length() == item.size &&
            (item.sha256 == null || runCatching { sha256(file).equals(item.sha256, ignoreCase = true) }.getOrDefault(false))

    private fun writeReadyMarker(context: Context, model: ModelSpec) {
        val markerFile = marker(context, model)
        val temp = File(markerFile.parentFile, ".validated.tmp")
        temp.writeText(signature(context, model), Charsets.UTF_8)
        if (markerFile.exists() && !markerFile.delete()) throw IOException("无法替换模型验证标记")
        if (!temp.renameTo(markerFile)) {
            temp.copyTo(markerFile, overwrite = true)
            if (!temp.delete()) throw IOException("无法清理模型验证临时文件")
        }
    }

    suspend fun download(context: Context, source: String) = lock.withLock {
        val app = context.applicationContext
        val model = spec(source)
        val dir = modelDirectory(app, model.source)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建" + model.label + " OPUS-MT 模型目录")
        val totalBytes = model.files.sumOf { it.size }
        if (isReady(app, model.source)) {
            publish(model.source, OpusMtDownloadState(status = OpusMtStatus.READY, progress = 100,
                fileIndex = model.files.size, fileCount = model.files.size, downloadedBytes = totalBytes, totalBytes = totalBytes))
            return@withLock
        }

        val allFilesAlreadyPresent = model.files.all { item ->
            File(dir, item.name).let { it.isFile && it.length() == item.size }
        }
        var completedBytes = 0L
        publish(model.source, OpusMtDownloadState(
            status = if (allFilesAlreadyPresent) OpusMtStatus.VERIFYING else OpusMtStatus.DOWNLOADING,
            progress = if (allFilesAlreadyPresent) 100 else 0,
            currentFile = if (allFilesAlreadyPresent) "检查已下载模型文件" else "",
            fileCount = model.files.size,
            downloadedBytes = if (allFilesAlreadyPresent) totalBytes else 0L,
            totalBytes = totalBytes,
        ))
        AppLogStore.info(app, "LocalTranslationModel", "OPUS-MT " + model.label + " → 中文模型下载开始或继续",
            diagnosticContext = mapOf("官方模型" to model.officialModelId, "运行时格式" to "CTranslate2 int8",
                "下载仓库" to model.repository, "固定版本" to model.revision, "模型总字节" to totalBytes.toString(),
                "续传或复用已有文件" to hasPartialDownload(app, model.source).toString()))

        try {
            model.files.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                val target = File(dir, item.name)
                if (validFile(target, item)) {
                    completedBytes += item.size
                    publish(model.source, OpusMtDownloadState(
                        status = if (allFilesAlreadyPresent) OpusMtStatus.VERIFYING else OpusMtStatus.DOWNLOADING,
                        progress = if (allFilesAlreadyPresent) 100 else (completedBytes * 100 / totalBytes).toInt().coerceIn(0, 99),
                        currentFile = item.name, fileIndex = index + 1, fileCount = model.files.size,
                        downloadedBytes = completedBytes, totalBytes = totalBytes,
                    ))
                    return@forEachIndexed
                }
                if (target.exists() && !target.delete()) throw IOException("无法替换校验失败的文件：" + item.name)
                publish(model.source, OpusMtDownloadState(
                    status = OpusMtStatus.DOWNLOADING,
                    progress = (completedBytes * 100L / totalBytes).toInt().coerceIn(0, 99),
                    currentFile = item.name, fileIndex = index + 1, fileCount = model.files.size,
                    downloadedBytes = completedBytes, totalBytes = totalBytes,
                ))
                downloadFile(app, model, item, index, completedBytes, totalBytes)
                completedBytes += item.size
            }

            val invalid = model.files.firstOrNull { item -> !validFile(File(dir, item.name), item) }
            if (invalid != null) throw IOException("模型文件完整性检查失败：" + invalid.name)
            publish(model.source, OpusMtDownloadState(status = OpusMtStatus.VERIFYING, progress = 100,
                currentFile = "验证实际翻译", fileIndex = model.files.size, fileCount = model.files.size,
                downloadedBytes = totalBytes, totalBytes = totalBytes))
            OpusMtCt2Runtime.validate(app, model.source, model.testText)
            writeReadyMarker(app, model)
            if (!isReady(app, model.source)) throw IOException("模型运行时验证标记未通过")
            publish(model.source, OpusMtDownloadState(status = OpusMtStatus.READY, progress = 100,
                fileIndex = model.files.size, fileCount = model.files.size, downloadedBytes = totalBytes, totalBytes = totalBytes))
            AppLogStore.info(app, "LocalTranslationModel", "OPUS-MT " + model.label + " → 中文模型下载、校验和实际试译成功",
                diagnosticContext = mapOf("官方模型" to model.officialModelId, "下载仓库" to model.repository,
                    "模型总字节" to totalBytes.toString(), "模型可供字幕翻译" to "true"))
        } catch (cancelled: CancellationException) {
            val previous = mutableStates.value[model.source] ?: OpusMtDownloadState(fileCount = model.files.size)
            val allFilesPresent = model.files.all { item ->
                File(dir, item.name).let { it.isFile && it.length() == item.size }
            }
            val resumableData = hasPartialDownload(app, model.source)
            val nextStatus = OpusMtAdditionalDownloadStatePolicy.afterCancellation(allFilesPresent, resumableData)
            publish(model.source, previous.copy(
                status = nextStatus,
                progress = if (allFilesPresent) 100 else previous.progress.coerceAtMost(99),
                currentFile = if (allFilesPresent) "验证实际翻译" else previous.currentFile,
                fileIndex = if (allFilesPresent) model.files.size else previous.fileIndex,
                downloadedBytes = if (allFilesPresent) totalBytes else
                    previous.downloadedBytes.coerceAtMost((totalBytes - 1L).coerceAtLeast(0L)),
                totalBytes = totalBytes,
                error = if (allFilesPresent) "模型文件已下载，运行时验证被中断；点击重新验证" else "",
            ))
            AppLogStore.info(app, "LocalTranslationModel", "OPUS-MT " + model.label + " 下载任务已中断",
                diagnosticContext = mapOf(
                    "模型文件已全部下载" to allFilesPresent.toString(),
                    "是否存在可续传数据" to resumableData.toString(),
                    "中断前阶段" to previous.status.name,
                    "当前文件" to previous.currentFile,
                ))
            throw cancelled
        } catch (error: Throwable) {
            val previous = mutableStates.value[model.source] ?: OpusMtDownloadState(fileCount = model.files.size)
            val allFilesPresent = model.files.all { item ->
                File(dir, item.name).let { it.isFile && it.length() == item.size }
            }
            val resumableData = hasPartialDownload(app, model.source)
            val nextStatus = OpusMtAdditionalDownloadStatePolicy.afterFailure(allFilesPresent, resumableData)
            var rootCause = error
            repeat(8) {
                val next = rootCause.cause
                if (next != null && next !== rootCause) rootCause = next
            }
            val reason = rootCause.message?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: rootCause.javaClass.simpleName
            publish(model.source, previous.copy(
                status = nextStatus,
                progress = if (allFilesPresent) 100 else previous.progress.coerceAtMost(99),
                currentFile = if (allFilesPresent) "验证实际翻译" else previous.currentFile,
                fileIndex = if (allFilesPresent) model.files.size else previous.fileIndex,
                downloadedBytes = if (allFilesPresent) totalBytes else
                    previous.downloadedBytes.coerceAtMost((totalBytes - 1L).coerceAtLeast(0L)),
                totalBytes = totalBytes,
                error = reason,
            ))
            AppLogStore.error(app, "LocalTranslationModel", "OPUS-MT " + model.label + " → 中文模型下载或验证失败", error,
                diagnosticContext = mapOf(
                    "官方模型" to model.officialModelId,
                    "下载仓库" to model.repository,
                    "模型文件已全部下载" to allFilesPresent.toString(),
                    "是否存在可续传数据" to resumableData.toString(),
                    "失败文件或阶段" to previous.currentFile,
                    "根因类型" to rootCause.javaClass.name,
                    "失败原因" to reason,
                ))
            if (error is Exception) throw error
            throw IOException("OPUS-MT " + model.label + " 模型验证失败：" + reason, error)
        }
    }

    private suspend fun downloadFile(
        context: Context, model: ModelSpec, item: ModelFile, index: Int, completedBefore: Long, totalBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val dir = modelDirectory(context, model.source)
        val target = File(dir, item.name)
        val part = File(dir, item.name + ".part")
        var allowRangeRetry = true
        while (true) {
            currentCoroutineContext().ensureActive()
            var existingBytes = if (part.isFile) part.length() else 0L
            val request = Request.Builder().url(model.url(item)).get().apply {
                if (existingBytes > 0L && allowRangeRetry) header("Range", "bytes=" + existingBytes + "-")
            }.build()
            var retryFromZero = false
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && existingBytes > 0L && allowRangeRetry) {
                    if (part.exists() && !part.delete()) throw IOException("无法清理无效的下载残片：" + item.name)
                    allowRangeRetry = false
                    retryFromZero = true
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP " + response.code + "，下载 " + item.name + " 失败")
                val body = response.body ?: throw IOException("下载响应没有内容：" + item.name)
                val isPartialResponse = response.code == 206
                if (isPartialResponse && (
                        existingBytes <= 0L ||
                            !ModelDownloadRangePolicy.matches(
                                response.header("Content-Range"),
                                expectedStart = existingBytes,
                                expectedTotal = item.size,
                            )
                    )
                ) {
                    if (!allowRangeRetry) throw IOException("OPUS-MT 断点续传响应范围连续异常：" + item.name)
                    if (part.exists() && !part.delete()) throw IOException("无法重置范围异常的下载残片：" + item.name)
                    allowRangeRetry = false
                    retryFromZero = true
                    return@use
                }
                val append = isPartialResponse && existingBytes > 0L
                if (existingBytes > 0L && !append) {
                    if (part.exists() && !part.delete()) throw IOException("服务器未接受断点续传，且无法重置残片：" + item.name)
                    existingBytes = 0L
                }
                var writtenThisRequest = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            writtenThisRequest += count.toLong()
                            val bytesNow = (if (append) existingBytes else 0L) + writtenThisRequest
                            val aggregate = completedBefore + bytesNow
                            publish(model.source, OpusMtDownloadState(
                                status = OpusMtStatus.DOWNLOADING,
                                progress = (aggregate * 100L / totalBytes).toInt().coerceIn(0, 99),
                                currentFile = item.name,
                                fileIndex = index + 1,
                                fileCount = model.files.size,
                                downloadedBytes = aggregate,
                                totalBytes = totalBytes,
                            ))
                        }
                        output.fd.sync()
                    }
                }
                if (part.length() != item.size) throw IOException(
                    "下载文件长度不匹配：" + item.name + "，预期 " + item.size + " 字节，实际 " + part.length() + " 字节"
                )
                if (item.sha256 != null && !sha256(part).equals(item.sha256, ignoreCase = true)) {
                    if (!part.delete()) throw IOException("模型文件 SHA-256 校验失败，且无法清理残片：" + item.name)
                    throw IOException("模型文件 SHA-256 校验失败：" + item.name + "，已清除损坏文件")
                }
                if (target.exists() && !target.delete()) throw IOException("无法替换旧文件：" + item.name)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (!part.delete()) throw IOException("文件已复制但无法清理残片：" + item.name)
                }
            }
            if (retryFromZero) continue
            if (!validFile(target, item)) throw IOException("下载文件检查失败：" + item.name)
            break
        }
    }

    suspend fun delete(context: Context, source: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val model = spec(source)
            OpusMtCt2Runtime.release(model.source)
            val dir = modelDirectory(context, model.source)
            if (dir.exists() && !dir.deleteRecursively()) throw IOException("无法删除" + model.label + " OPUS-MT 模型")
            publish(model.source, OpusMtDownloadState(
                status = OpusMtStatus.NOT_DOWNLOADED,
                fileCount = model.files.size,
                totalBytes = model.files.sumOf { it.size },
            ))
            AppLogStore.info(
                context.applicationContext,
                "LocalTranslationModel",
                "OPUS-MT " + model.label + " → 中文模型已删除",
                diagnosticContext = mapOf("模型目录" to dir.absolutePath),
            )
        }
    }
}
