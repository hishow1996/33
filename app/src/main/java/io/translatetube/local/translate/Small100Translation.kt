package io.translatetube.local.translate

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Small100Status { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, PAUSED, READY, FAILED }

data class Small100DownloadState(
    val status: Small100Status = Small100Status.NOT_DOWNLOADED,
    val progress: Int = 0,
    val currentFile: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 4,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = Small100ModelManager.TOTAL_BYTES,
    val error: String = "",
) {
    /** True when every expected model byte is on disk, even if runtime validation is pending. */
    val hasCompleteFiles: Boolean
        get() = totalBytes > 0L && downloadedBytes >= totalBytes
}

/** The exact 100 language codes included by the pinned SMaLL-100 checkpoint. */
object Small100LanguagePolicy {
    val languages: List<Pair<String, String>> = listOf(
        "af" to "南非荷兰语", "am" to "阿姆哈拉语", "ar" to "阿拉伯语",
        "ast" to "阿斯图里亚斯语", "az" to "阿塞拜疆语", "ba" to "巴什基尔语",
        "be" to "白俄罗斯语", "bg" to "保加利亚语", "bn" to "孟加拉语",
        "br" to "布列塔尼语", "bs" to "波斯尼亚语", "ca" to "加泰罗尼亚语",
        "ceb" to "宿务语", "cs" to "捷克语", "cy" to "威尔士语",
        "da" to "丹麦语", "de" to "德语", "el" to "希腊语",
        "en" to "英语", "es" to "西班牙语", "et" to "爱沙尼亚语",
        "fa" to "波斯语", "ff" to "富拉语", "fi" to "芬兰语",
        "fr" to "法语", "fy" to "西弗里斯兰语", "ga" to "爱尔兰语",
        "gd" to "苏格兰盖尔语", "gl" to "加利西亚语", "gu" to "古吉拉特语",
        "ha" to "豪萨语", "he" to "希伯来语", "hi" to "印地语",
        "hr" to "克罗地亚语", "ht" to "海地克里奥尔语", "hu" to "匈牙利语",
        "hy" to "亚美尼亚语", "id" to "印尼语", "ig" to "伊博语",
        "ilo" to "伊洛卡诺语", "is" to "冰岛语", "it" to "意大利语",
        "ja" to "日语", "jv" to "爪哇语", "ka" to "格鲁吉亚语",
        "kk" to "哈萨克语", "km" to "高棉语", "kn" to "卡纳达语",
        "ko" to "韩语", "lb" to "卢森堡语", "lg" to "卢干达语",
        "ln" to "林加拉语", "lo" to "老挝语", "lt" to "立陶宛语",
        "lv" to "拉脱维亚语", "mg" to "马达加斯加语", "mk" to "马其顿语",
        "ml" to "马拉雅拉姆语", "mn" to "蒙古语", "mr" to "马拉地语",
        "ms" to "马来语", "my" to "缅甸语", "ne" to "尼泊尔语",
        "nl" to "荷兰语", "no" to "挪威语", "ns" to "北索托语",
        "oc" to "奥克语", "or" to "奥里亚语", "pa" to "旁遮普语",
        "pl" to "波兰语", "ps" to "普什图语", "pt" to "葡萄牙语",
        "ro" to "罗马尼亚语", "ru" to "俄语", "sd" to "信德语",
        "si" to "僧伽罗语", "sk" to "斯洛伐克语", "sl" to "斯洛文尼亚语",
        "so" to "索马里语", "sq" to "阿尔巴尼亚语", "sr" to "塞尔维亚语",
        "ss" to "斯瓦蒂语", "su" to "巽他语", "sv" to "瑞典语",
        "sw" to "斯瓦希里语", "ta" to "泰米尔语", "th" to "泰语",
        "tl" to "他加禄语", "tn" to "茨瓦纳语", "tr" to "土耳其语",
        "uk" to "乌克兰语", "ur" to "乌尔都语", "uz" to "乌兹别克语",
        "vi" to "越南语", "wo" to "沃洛夫语", "xh" to "科萨语",
        "yi" to "意第绪语", "yo" to "约鲁巴语", "zh" to "中文",
        "zu" to "祖鲁语",
    )
    private val supportedCodes = languages.mapTo(linkedSetOf()) { it.first }

    fun canonicalCode(languageTag: String): String =
        SourceLanguagePolicy.canonicalPrimaryCode(languageTag)

    fun supportsSource(languageTag: String): Boolean =
        SourceLanguagePolicy.isUndetermined(languageTag) || canonicalCode(languageTag) in supportedCodes

    fun display(languageTag: String): String {
        val code = canonicalCode(languageTag)
        return languages.firstOrNull { it.first == code }?.second ?: languageTag
    }

    /** The model's lang_tokens.json assigns consecutive IDs in this exact ISO-code order. */
    fun expectedTokenMap(): Map<String, Long> =
        languages.mapIndexed { index, item -> item.first to (128004L + index) }.toMap()
}

/** Range-resumable downloader for the pinned, int8 SMaLL-100 ONNX checkpoint. */
object Small100ModelManager {
    private const val MODEL_REVISION = "5c2c73ac70bee9c58f5a7ac5e84a36bee25db8ee"
    private const val VALIDATION_SCHEMA = 2

    private data class ModelFile(
        val name: String,
        val remotePath: String,
        val bytes: Long,
        val sha256: String = "",
    )

    private val files = listOf(
        ModelFile("encoder_model.onnx", "onnx/encoder_model.onnx", 287_317_499L,
            "a130f553106646e56c1908094074f354353c45d86b3e8d222b037784035e6dcd"),
        ModelFile("decoder_model_merged.onnx", "onnx/decoder_model_merged.onnx", 321_741_340L,
            "f966ce4f1f484c2307dc007ef0beadc3a1651220c1ff9b33ea8dddbce468a4b0"),
        ModelFile("tokenizer.json", "tokenizer.json", 5_807_639L),
        ModelFile("lang_tokens.json", "lang_tokens.json", 1_885L),
    )

    val TOTAL_BYTES: Long = files.sumOf { it.bytes }
    private val lock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mutableState = MutableStateFlow(Small100DownloadState())
    val state: StateFlow<Small100DownloadState> = mutableState

    fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "models/translation/small100")

    private fun readyMarker(context: Context) = File(modelDirectory(context), ".validated")

    private fun signature(context: Context): String {
        val directory = modelDirectory(context)
        return buildString {
            append(MODEL_REVISION).append(":runtime-validation-").append(VALIDATION_SCHEMA)
            files.forEach { spec ->
                val file = File(directory, spec.name)
                append('\n').append(spec.name).append(':')
                    .append(file.length()).append(':').append(file.lastModified())
            }
        }
    }

    fun isReady(context: Context): Boolean {
        val directory = modelDirectory(context)
        val marker = readyMarker(context)
        return marker.isFile &&
            files.all { spec -> File(directory, spec.name).let { it.isFile && it.length() == spec.bytes } } &&
            runCatching { marker.readText(Charsets.UTF_8).trim() == signature(context) }.getOrDefault(false)
    }

    /** Distinguishes final model files from a .part file that happens to reach the target byte count. */
    private fun areAllModelFilesPresent(context: Context): Boolean {
        val directory = modelDirectory(context)
        return files.all { spec ->
            File(directory, spec.name).let { it.isFile && it.length() == spec.bytes }
        }
    }

    fun hasPartialDownload(context: Context): Boolean {
        if (areAllModelFilesPresent(context)) return false
        val directory = modelDirectory(context)
        return files.any { spec ->
            File(directory, spec.name + ".part").let { it.isFile && it.length() > 0L } ||
                File(directory, spec.name).let { it.isFile && it.length() > 0L }
        }
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (lock.isLocked) return@withContext
        val directory = modelDirectory(context)
        mutableState.value = when {
            isReady(context) -> Small100DownloadState(
                status = Small100Status.READY, progress = 100, fileIndex = files.size,
                fileCount = files.size, downloadedBytes = TOTAL_BYTES, totalBytes = TOTAL_BYTES,
            )
            areAllModelFilesPresent(context) -> Small100DownloadState(
                status = Small100Status.FAILED,
                progress = 100,
                currentFile = "运行时验证",
                fileIndex = files.size,
                fileCount = files.size,
                downloadedBytes = TOTAL_BYTES,
                totalBytes = TOTAL_BYTES,
                error = "模型文件已完整下载，运行时验证尚未通过；点击重新验证",
            )
            hasPartialDownload(context) -> {
                val firstIncomplete = files.indexOfFirst { spec ->
                    val target = File(directory, spec.name)
                    val part = File(directory, spec.name + ".part")
                    !target.isFile || target.length() != spec.bytes || part.isFile
                }
                if (firstIncomplete < 0) {
                    // All four files are already present. A failed runtime check must not make
                    // the UI report a partial download or suggest downloading the 615 MB again.
                    Small100DownloadState(
                        status = Small100Status.PAUSED,
                        progress = 100,
                        currentFile = "运行时验证",
                        fileIndex = files.size,
                        fileCount = files.size,
                        downloadedBytes = TOTAL_BYTES,
                        totalBytes = TOTAL_BYTES,
                        error = "模型文件已下载，等待运行环境验证",
                    )
                } else {
                    val spec = files[firstIncomplete]
                    val part = File(directory, spec.name + ".part")
                    val finalFile = File(directory, spec.name)
                    val priorBytes = files.take(firstIncomplete).sumOf { it.bytes }
                    val currentBytes = if (part.isFile) part.length() else finalFile.length()
                    val incompleteDownloaded = (priorBytes + currentBytes)
                        .coerceAtMost((TOTAL_BYTES - 1L).coerceAtLeast(0L))
                    Small100DownloadState(
                        status = Small100Status.PAUSED,
                        progress = (incompleteDownloaded * 100L / TOTAL_BYTES).toInt().coerceIn(0, 99),
                        currentFile = spec.name,
                        fileIndex = firstIncomplete + 1,
                        fileCount = files.size,
                        downloadedBytes = incompleteDownloaded,
                        totalBytes = TOTAL_BYTES,
                    )
                }
            }
            else -> Small100DownloadState(status = Small100Status.NOT_DOWNLOADED)
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val directory = modelDirectory(app)
        if (isReady(app)) {
            mutableState.value = Small100DownloadState(
                status = Small100Status.READY,
                progress = 100,
                fileIndex = files.size,
                fileCount = files.size,
                downloadedBytes = TOTAL_BYTES,
                totalBytes = TOTAL_BYTES,
            )
            return@withLock
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建 SMaLL-100 模型目录")
        AppLogStore.info(
            app, "LocalTranslationModel", "SMaLL-100 模型下载开始或继续",
            diagnosticContext = mapOf(
                "固定版本" to MODEL_REVISION,
                "模型总字节" to TOTAL_BYTES.toString(),
                "保留可续传数据" to hasPartialDownload(app).toString(),
            ),
        )
        try {
            files.forEachIndexed { index, spec ->
                currentCoroutineContext().ensureActive()
                val target = File(directory, spec.name)
                if (target.isFile && target.length() == spec.bytes && hasValidContent(target, spec)) {
                    updateProgress(index, spec, spec.bytes, files.take(index).sumOf { it.bytes })
                    return@forEachIndexed
                }
                if (target.exists() && !target.delete()) throw IOException("无法清理无效模型文件：" + spec.name)
                downloadFile(directory, spec, index)
            }
            mutableState.value = Small100DownloadState(
                status = Small100Status.VERIFYING, progress = 100, currentFile = "运行时验证",
                fileIndex = files.size, fileCount = files.size, downloadedBytes = TOTAL_BYTES, totalBytes = TOTAL_BYTES,
            )
            withContext(Dispatchers.IO) {
                try {
                    Small100Runtime.validate(app)
                } finally {
                    Small100Runtime.closeLoadedModel()
                }
                writeReadyMarker(app)
            }
            if (!isReady(app)) throw IOException("SMaLL-100 文件完整性标记或运行时验证失败")
            mutableState.value = Small100DownloadState(
                status = Small100Status.READY, progress = 100, fileIndex = files.size,
                fileCount = files.size, downloadedBytes = TOTAL_BYTES, totalBytes = TOTAL_BYTES,
            )
            AppLogStore.info(
                app, "LocalTranslationModel", "SMaLL-100 模型下载、校验及中文试译验证成功",
                diagnosticContext = mapOf(
                    "模型版本" to MODEL_REVISION,
                    "文件数量" to files.size.toString(),
                    "模型总字节" to TOTAL_BYTES.toString(),
                    "支持语言数" to Small100LanguagePolicy.languages.size.toString(),
                ),
            )
        } catch (cancelled: CancellationException) {
            val previous = mutableState.value
            val completeFiles = areAllModelFilesPresent(app)
            mutableState.value = previous.copy(
                status = if (completeFiles) Small100Status.FAILED
                    else if (hasPartialDownload(app)) Small100Status.PAUSED else Small100Status.NOT_DOWNLOADED,
                progress = if (completeFiles) 100 else previous.progress.coerceAtMost(99),
                currentFile = if (completeFiles) "运行时验证" else previous.currentFile,
                fileIndex = if (completeFiles) files.size else previous.fileIndex,
                downloadedBytes = if (completeFiles) TOTAL_BYTES else
                    previous.downloadedBytes.coerceAtMost((TOTAL_BYTES - 1L).coerceAtLeast(0L)),
                totalBytes = TOTAL_BYTES,
                error = if (completeFiles) "模型文件已完整下载，运行时验证已中断；点击重新验证" else "",
            )
            AppLogStore.info(app, "LocalTranslationModel", "SMaLL-100 下载已暂停，可从上次进度继续")
            throw cancelled
        } catch (error: Throwable) {
            val partial = hasPartialDownload(app)
            val completeFiles = areAllModelFilesPresent(app)
            var rootCause = error
            repeat(8) {
                val next = rootCause.cause
                if (next != null && next !== rootCause) rootCause = next
            }
            val reason = when (rootCause) {
                is ClassNotFoundException, is NoClassDefFoundError ->
                    "应用缺少 ONNX Runtime Java 运行类（${rootCause.message ?: "ai.onnxruntime"}）。" +
                        "请安装修复版应用；已下载模型文件会保留。"
                is UnsatisfiedLinkError ->
                    "ONNX Runtime 原生库加载失败（${rootCause.message ?: "未知原因"}）。" +
                        "请安装修复版应用；已下载模型文件会保留。"
                else -> error.message?.takeIf { it.isNotBlank() } ?: rootCause.javaClass.simpleName
            }
            val previous = mutableState.value
            mutableState.value = previous.copy(
                status = Small100Status.FAILED,
                progress = if (completeFiles) 100 else previous.progress.coerceAtMost(99),
                downloadedBytes = if (completeFiles) TOTAL_BYTES else
                    previous.downloadedBytes.coerceAtMost((TOTAL_BYTES - 1L).coerceAtLeast(0L)),
                totalBytes = TOTAL_BYTES,
                error = reason,
            )
            AppLogStore.error(
                app, "LocalTranslationModel", "SMaLL-100 模型下载或验证失败", error,
                diagnosticContext = mapOf(
                    "当前文件" to mutableState.value.currentFile,
                    "是否可续传" to partial.toString(),
                    "失败原因" to reason,
                    "根因类型" to rootCause.javaClass.name,
                    "根因信息" to (rootCause.message ?: ""),
                ),
            )
            if (error is Exception) throw error else throw IOException("SMaLL-100 模型验证或加载失败：$reason", error)
        }
    }

    suspend fun delete(context: Context) = lock.withLock {
        Small100Runtime.closeLoadedModel()
        withContext(Dispatchers.IO) {
            val directory = modelDirectory(context)
            if (directory.exists() && !directory.deleteRecursively()) {
                throw IOException("无法完整删除 SMaLL-100 模型目录")
            }
            mutableState.value = Small100DownloadState(status = Small100Status.NOT_DOWNLOADED)
        }
    }

    private fun updateProgress(index: Int, spec: ModelFile, currentBytes: Long, bytesBefore: Long) {
        val downloaded = (bytesBefore + currentBytes).coerceAtMost(TOTAL_BYTES)
        mutableState.value = Small100DownloadState(
            status = Small100Status.DOWNLOADING,
            progress = (downloaded * 100L / TOTAL_BYTES).toInt().coerceIn(0, 99),
            currentFile = spec.name, fileIndex = index + 1, fileCount = files.size,
            downloadedBytes = downloaded, totalBytes = TOTAL_BYTES,
        )
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun downloadFile(directory: File, spec: ModelFile, index: Int) = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val activeJob = coroutineContext[Job]
        val target = File(directory, spec.name)
        val part = File(directory, spec.name + ".part")
        var existingBytes = if (part.isFile) part.length() else 0L
        var rangeRetryUsed = false
        val bytesBefore = files.take(index).sumOf { it.bytes }

        // A completed .part file is already the download. Verify and install it instead of
        // asking the server for bytes after EOF, which returns 416 and previously restarted it.
        if (existingBytes >= spec.bytes) {
            if (ModelDownloadRangePolicy.isCompletePart(existingBytes, spec.bytes) && hasValidContent(part, spec)) {
                installVerifiedPart(target, part, spec.name)
                updateProgress(index, spec, spec.bytes, bytesBefore)
                return@withContext
            }
            if (part.exists() && !part.delete()) throw IOException("无法清理无效的完整下载残片：" + spec.name)
            existingBytes = 0L
        }

        while (true) {
            coroutineContext.ensureActive()
            val url = "https://huggingface.co/casawolice/small100-onnx/resolve/" +
                MODEL_REVISION + "/" + spec.remotePath
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity").get()
            if (existingBytes > 0L) builder.header("Range", "bytes=" + existingBytes + "-")
            var restartFromZero = false
            val call = client.newCall(builder.build())
            val cancellationHandle = activeJob?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is java.util.concurrent.CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                if (response.code == 416 && existingBytes > 0L && !rangeRetryUsed) {
                    if (part.exists() && !part.delete()) throw IOException("无法重置下载残片：" + spec.name)
                    existingBytes = 0L
                    rangeRetryUsed = true
                    restartFromZero = true
                } else {
                    if (!response.isSuccessful) {
                        throw IOException("HTTP " + response.code + "，下载 SMaLL-100 文件 " + spec.name + " 失败")
                    }
                    val body = response.body ?: throw IOException("下载响应缺少内容：" + spec.name)
                    val isPartialResponse = response.code == 206
                    if (isPartialResponse && existingBytes <= 0L) {
                        throw IOException("服务器对完整下载返回了异常的分段响应：" + spec.name)
                    }
                    if (isPartialResponse &&
                        !ModelDownloadRangePolicy.matches(
                            response.header("Content-Range"),
                            expectedStart = existingBytes,
                            expectedTotal = spec.bytes,
                        )
                    ) {
                        if (rangeRetryUsed) {
                            throw IOException("断点续传响应范围连续异常：" + spec.name)
                        }
                        if (part.exists() && !part.delete()) {
                            throw IOException("无法重置范围异常的下载残片：" + spec.name)
                        }
                        existingBytes = 0L
                        rangeRetryUsed = true
                        restartFromZero = true
                    } else {
                        val append = isPartialResponse && existingBytes > 0L
                        if (existingBytes > 0L && !append) {
                            if (part.exists() && !part.delete()) throw IOException("无法重置下载残片：" + spec.name)
                            existingBytes = 0L
                        }
                        FileOutputStream(part, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var current = if (append) existingBytes else 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (!ModelDownloadRangePolicy.canAcceptBytes(current, count.toLong(), spec.bytes)) {
                                    throw IOException("服务器返回的模型文件超过预期大小：" + spec.name)
                                }
                                output.write(buffer, 0, count)
                                current += count.toLong()
                                updateProgress(index, spec, current, bytesBefore)
                            }
                            output.fd.sync()
                        }
                    }
                    if (!part.isFile || part.length() != spec.bytes) {
                        throw IOException(
                            "模型文件大小异常：" + spec.name + "，预期 " + spec.bytes +
                                " 字节，实际 " + part.length() + " 字节"
                        )
                    }
                    if (!hasValidContent(part, spec)) {
                        part.delete()
                        throw IOException("模型文件 SHA-256 校验失败：" + spec.name + "，请重新下载")
                    }
                    installVerifiedPart(target, part, spec.name)
                    updateProgress(index, spec, spec.bytes, bytesBefore)
                    }
                }
            } catch (error: Exception) {
                if (activeJob?.isCancelled == true && error !is CancellationException) {
                    throw CancellationException("SMaLL-100 模型下载已取消").also { it.initCause(error) }
                }
                throw error
            } finally {
                cancellationHandle?.dispose()
            }
            if (restartFromZero) continue
            return@withContext
        }
    }

    private fun installVerifiedPart(target: File, part: File, name: String) {
        if (target.exists() && !target.delete()) throw IOException("无法替换模型文件：" + name)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            if (!part.delete()) throw IOException("无法清理模型临时文件：" + name)
        }
    }

    private fun hasValidContent(file: File, spec: ModelFile): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        if (spec.sha256.isBlank()) return true
        return runCatching { sha256(file).equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun writeReadyMarker(context: Context) {
        val marker = readyMarker(context)
        val temp = File(marker.parentFile, ".validated.tmp")
        temp.writeText(signature(context), Charsets.UTF_8)
        if (marker.exists() && !marker.delete()) throw IOException("无法替换 SMaLL-100 验证标记")
        if (!temp.renameTo(marker)) {
            temp.copyTo(marker, overwrite = true)
            if (!temp.delete()) throw IOException("无法清理验证临时文件")
        }
    }
}

class Small100Engine(private val context: Context) : TranslationEngine {
    private val appContext = context.applicationContext
    override val id: String = "small100"
    override val displayName: String = "SMaLL-100 多语言本地翻译"

    suspend fun validateConfiguration(srcLang: String, tgtLang: String, sampleLines: List<String> = emptyList()): String {
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn", "zh-sg")) {
            throw IllegalStateException("SMaLL-100 当前集成配置的目标语言为简体中文。")
        }
        val source = SourceLanguagePolicy.sourceForTranslation(srcLang)
        if (source.isNotBlank() && !Small100LanguagePolicy.supportsSource(source)) {
            throw IllegalStateException(
                "SMaLL-100 不支持源语言 " + Small100LanguagePolicy.display(source) +
                    "（" + source + "）。请在“API 管理 → 本地翻译”中选择支持的语言。"
            )
        }
        if (!Small100ModelManager.isReady(appContext)) {
            throw IllegalStateException("SMaLL-100 模型尚未下载或通过验证，请到“API 管理 → 本地翻译”中继续下载。")
        }
        return if (source.isBlank()) srcLang else Small100LanguagePolicy.canonicalCode(source)
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val source = validateConfiguration(srcLang, tgtLang, lines)
        AppLogStore.info(
            appContext, "Translation", "开始调用 SMaLL-100 多语言本地模型",
            diagnosticContext = mapOf("源语言" to source, "目标语言" to "zh", "字幕条数" to lines.size.toString()),
        )
        return withContext(Dispatchers.Default) {
            val taskContext = currentCoroutineContext()
            lines.mapIndexed { index, line ->
                taskContext.ensureActive()
                val translated = if (line.isBlank()) "" else try {
                    Small100Runtime.translateOneWithRecovery(appContext, line, "zh", source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogStore.error(
                        appContext,
                        "Translation",
                        "SMaLL-100 单条字幕自动拆分恢复失败",
                        error,
                        diagnosticContext = mapOf(
                            "字幕序号" to (index + 1).toString(),
                            "字幕总数" to lines.size.toString(),
                            "字幕字符数" to line.length.toString(),
                            "自动拆分恢复" to "已尝试",
                            "未记录字幕原文" to "true",
                        ),
                    )
                    throw IllegalStateException(
                        "SMaLL-100 第 ${index + 1}/${lines.size} 条字幕无法完整翻译，已停止本次翻译以避免空字幕进入配音。",
                        error,
                    )
                }
                if ((index + 1) % 10 == 0 || index == lines.lastIndex) {
                    AppLogStore.info(
                        appContext, "Translation", "SMaLL-100 本地翻译进度",
                        diagnosticContext = mapOf(
                            "已完成字幕" to (index + 1).toString(),
                            "字幕总数" to lines.size.toString(),
                            "源语言" to source,
                            "目标语言" to "zh",
                        ),
                    )
                }
                translated
            }
        }
    }

    override fun close() {
        // TranslationEngine.close() is non-suspending. Never close ONNX sessions directly here:
        // another playback session may still be inside inference. The runtime serializes release
        // with inference and model loading through the same mutex.
        Small100Runtime.requestCloseLoadedModel()
    }
}

private object Small100Runtime {
    private const val LAYERS = 3
    private const val HEADS = 16L
    private const val HEAD_DIM = 64L
    private const val EOS = 2L
    private const val MAX_SOURCE_TOKENS = 1024
    private val inferenceMutex = Mutex()
    private val loadedLock = Any()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loaded: Loaded? = null

    private data class Loaded(
        val identity: String,
        val environment: OrtEnvironment,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val tokenizer: Small100BpeTokenizer,
        val langToId: Map<String, Long>,
    ) {
        fun close() {
            runCatching { encoder.close() }
            runCatching { decoder.close() }
            runCatching { tokenizer.close() }
        }

        fun translateOneWithRecovery(
            text: String,
            targetCode: String,
            sourceLanguage: String,
            checkActive: () -> Unit,
            recoveryDepth: Int = 0,
        ): String {
            checkActive()
            try {
                return translateOne(text, targetCode, sourceLanguage, checkActive)
            } catch (error: Small100InputTooLongException) {
                return translateSplitParts(text, targetCode, sourceLanguage, checkActive, recoveryDepth, error)
            } catch (error: Small100OutputLimitException) {
                return translateSplitParts(text, targetCode, sourceLanguage, checkActive, recoveryDepth, error)
            } catch (error: Small100QualityException) {
                return translateSplitParts(text, targetCode, sourceLanguage, checkActive, recoveryDepth, error)
            }
        }

        private fun translateSplitParts(
            text: String,
            targetCode: String,
            sourceLanguage: String,
            checkActive: () -> Unit,
            recoveryDepth: Int,
            cause: Exception,
        ): String {
            if (recoveryDepth >= Small100GenerationPolicy.MAX_SPLIT_RECOVERY_DEPTH) {
                throw IllegalStateException("SMaLL-100 字幕拆分恢复达到最大层数，仍未能完整生成译文。", cause)
            }
            val sourceTokenCount = (cause as? Small100InputTooLongException)?.actualTokens
                ?: (tokenizer.encode(text).size + 1)
            val allowWeakBoundaries = cause is Small100InputTooLongException ||
                sourceTokenCount > Small100GenerationPolicy.MIN_TOKENS_FOR_NON_SEMANTIC_SPLIT
            val parts = Small100GenerationPolicy.splitForRecovery(
                text = text,
                maxChunkTokens = MAX_SOURCE_TOKENS,
                allowWeakBoundaries = allowWeakBoundaries,
                tokenCount = { chunk -> tokenizer.encode(chunk).size + 1 },
            ) ?: throw IllegalStateException(
                "SMaLL-100 字幕无法继续拆分，无法保证译文完整。", cause
            )
            val left = translateOneWithRecovery(
                parts.first, targetCode, sourceLanguage, checkActive, recoveryDepth + 1
            )
            checkActive()
            val right = translateOneWithRecovery(
                parts.second, targetCode, sourceLanguage, checkActive, recoveryDepth + 1
            )
            return cleanChineseSpacing(Small100GenerationPolicy.joinTranslatedParts(left, right))
        }

        fun translateOne(
            text: String,
            targetCode: String,
            sourceLanguage: String,
            checkActive: () -> Unit,
        ): String {
            val targetId = langToId[targetCode] ?: throw IllegalArgumentException("模型缺少目标语言代码：" + targetCode)
            val encoded = tokenizer.encode(text)
            val sourceIds = LongArray(encoded.size + 1)
            sourceIds[0] = targetId
            encoded.copyInto(sourceIds, destinationOffset = 1)
            if (sourceIds.size > MAX_SOURCE_TOKENS) {
                throw Small100InputTooLongException(
                    actualTokens = sourceIds.size,
                    maxTokens = MAX_SOURCE_TOKENS,
                )
            }
            val maxOutputTokens = Small100GenerationPolicy.outputTokenLimit(sourceIds.size)
            val mask = LongArray(sourceIds.size) { 1L }
            val inputIdsTensor = longTensor(environment, sourceIds)
            val maskTensor = longTensor(environment, mask)
            var encoderResult: OrtSession.Result? = null
            var past = emptyPast(environment)
            try {
                val encResult = encoder.run(mapOf("input_ids" to inputIdsTensor, "attention_mask" to maskTensor))
                encoderResult = encResult
                val hiddenName = encoder.outputNames.firstOrNull { it.contains("last_hidden_state") }
                    ?: encoder.outputNames.firstOrNull()
                    ?: throw IOException("SMaLL-100 编码器没有输出")
                val hidden = encResult.get(hiddenName).orElseThrow {
                    IOException("SMaLL-100 编码器缺少输出：" + hiddenName)
                } as? OnnxTensor ?: throw IOException("SMaLL-100 编码器输出不是张量")

                val generated = ArrayList<Long>()
                var currentToken = 2L
                var useCache = false
                var reachedEos = false
                for (step in 0 until maxOutputTokens) {
                    checkActive()
                    val tokenTensor = longTensor(environment, longArrayOf(currentToken))
                    val cacheFlagTensor = OnnxTensor.createTensor(
                        environment, ByteBuffer.wrap(byteArrayOf(if (useCache) 1 else 0)),
                        longArrayOf(1L), OnnxJavaType.BOOL,
                    )
                    val feeds = HashMap<String, OnnxTensor>()
                    feeds["encoder_attention_mask"] = maskTensor
                    feeds["input_ids"] = tokenTensor
                    feeds["encoder_hidden_states"] = hidden
                    feeds["use_cache_branch"] = cacheFlagTensor
                    feeds.putAll(past)
                    val result = try {
                        decoder.run(feeds)
                    } finally {
                        runCatching { tokenTensor.close() }
                        runCatching { cacheFlagTensor.close() }
                    }
                    try {
                        val logitsName = decoder.outputNames.firstOrNull { it == "logits" }
                            ?: decoder.outputNames.firstOrNull { it.contains("logits") }
                            ?: throw IOException("SMaLL-100 解码器没有 logits 输出")
                        val logits = result.get(logitsName).orElseThrow {
                            IOException("SMaLL-100 解码器缺少输出：" + logitsName)
                        } as? OnnxTensor ?: throw IOException("SMaLL-100 logits 不是张量")
                        val nextToken = argmaxLast(logits)
                        if (nextToken == EOS) {
                            reachedEos = true
                            break
                        }
                        generated += nextToken
                        past = rollPast(environment, result, useCache, past)
                        currentToken = nextToken
                        useCache = true
                    } finally {
                        runCatching { result.close() }
                    }
                }
                Small100GenerationPolicy.requireComplete(reachedEos, generated.size, maxOutputTokens)
                val translation = tokenizer.decode(generated.toLongArray(), true).trim()
                if (translation.isBlank()) throw IllegalStateException("SMaLL-100 返回了空译文。")
                Small100GenerationPolicy.requireTranslationQuality(text, translation, sourceLanguage)
                return cleanChineseSpacing(translation)
            } finally {
                past.values.forEach { runCatching { it.close() } }
                runCatching { encoderResult?.close() }
                runCatching { inputIdsTensor.close() }
                runCatching { maskTensor.close() }
            }
        }
    }

    suspend fun translateOneWithRecovery(
        context: Context,
        text: String,
        targetCode: String,
        sourceLanguage: String,
    ): String {
        val taskContext = currentCoroutineContext()
        return inferenceMutex.withLock {
            taskContext.ensureActive()
            load(context).translateOneWithRecovery(
                text, targetCode, sourceLanguage
            ) { taskContext.ensureActive() }
        }
    }

    suspend fun validate(context: Context) {
        val result = translateOneWithRecovery(context, "Hello.", "zh", "en")
        if (result.isBlank() || result.none { it in '\u4E00'..'\u9FFF' }) {
            throw IllegalStateException("SMaLL-100 运行验证失败：英文测试句没有生成中文译文。")
        }
        AppLogStore.info(
            context.applicationContext, "LocalTranslationModel", "SMaLL-100 运行时试译成功",
            diagnosticContext = mapOf("测试语言对" to "en→zh", "译文含汉字" to "true"),
        )
    }

    /**
     * Release model memory safely after the current inference completes. The engine interface's
     * close() method cannot suspend, so schedule the same mutex-protected close used by model
     * deletion instead of closing native ONNX sessions while another playback session uses them.
     */
    fun requestCloseLoadedModel() {
        closeScope.launch {
            closeLoadedModel()
        }
    }

    suspend fun closeLoadedModel() = inferenceMutex.withLock {
        synchronized(loadedLock) {
            loaded?.close()
            loaded = null
        }
    }

    private fun load(context: Context): Loaded = synchronized(loadedLock) {
        val directory = Small100ModelManager.modelDirectory(context)
        val encoderFile = File(directory, "encoder_model.onnx")
        val decoderFile = File(directory, "decoder_model_merged.onnx")
        val tokenizerFile = File(directory, "tokenizer.json")
        val languageFile = File(directory, "lang_tokens.json")
        val modelFiles = listOf(encoderFile, decoderFile, tokenizerFile, languageFile)
        val missing = modelFiles.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) throw IOException("SMaLL-100 文件不完整：" + missing.joinToString { it.name })

        val identity = modelFiles.joinToString("|") { it.name + ":" + it.length() + ":" + it.lastModified() }
        loaded?.takeIf { it.identity == identity }?.let { return@synchronized it }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memory = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memory)
            val minAvailable = 256L * 1024L * 1024L
            if (memory.availMem in 1 until minAvailable) {
                throw IllegalStateException(
                    "当前设备可用运行内存不足（低于 256 MB），请关闭其他应用后重试。"
                )
            }
        }

        loaded?.close()
        var stage = "ONNX Runtime 环境初始化"
        var options: OrtSession.SessionOptions? = null
        var encoder: OrtSession? = null
        var decoder: OrtSession? = null
        var tokenizer: Small100BpeTokenizer? = null
        try {
            val environment = try {
                OrtEnvironment.getEnvironment()
            } catch (error: Throwable) {
                throw IOException("ONNX Runtime 环境初始化失败：" +
                    (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName), error)
            }

            stage = "创建 ONNX Runtime 会话参数"
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            options = sessionOptions

            stage = "加载 SMaLL-100 编码器"
            val encoderSession = environment.createSession(encoderFile.absolutePath, sessionOptions)
            encoder = encoderSession

            stage = "加载 SMaLL-100 解码器"
            val decoderSession = environment.createSession(decoderFile.absolutePath, sessionOptions)
            decoder = decoderSession

            stage = "解析 SMaLL-100 Tokenizer"
            val bpeTokenizer = tokenizerFile.inputStream().use { input ->
                Small100BpeTokenizer.fromStream(input)
            }
            tokenizer = bpeTokenizer

            stage = "读取 SMaLL-100 语言代码表"
            val languageJson = JSONObject(languageFile.readText(Charsets.UTF_8)).getJSONObject("lang_to_id")
            val parsedTokens = linkedMapOf<String, Long>()
            val keys = languageJson.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                parsedTokens[code] = languageJson.getLong(code)
            }
            if (parsedTokens != Small100LanguagePolicy.expectedTokenMap()) {
                throw IOException("SMaLL-100 语言代码表与固定模型版本不一致")
            }

            val result = Loaded(identity, environment, encoderSession, decoderSession, bpeTokenizer, parsedTokens)
            loaded = result
            result
        } catch (error: Throwable) {
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            runCatching { tokenizer?.close() }
            var rootCause = error
            repeat(8) {
                val next = rootCause.cause
                if (next != null && next !== rootCause) rootCause = next
            }
            val detail = rootCause.message?.takeIf { it.isNotBlank() } ?: rootCause.javaClass.simpleName
            throw IOException("SMaLL-100 $stage 失败：$detail", error)
        } finally {
            runCatching { options?.close() }
        }
    }

    private fun emptyPast(environment: OrtEnvironment): MutableMap<String, OnnxTensor> {
        val result = linkedMapOf<String, OnnxTensor>()
        for (layer in 0 until LAYERS) {
            for (kind in listOf("decoder", "encoder")) {
                for (kv in listOf("key", "value")) {
                    result["past_key_values." + layer + "." + kind + "." + kv] = OnnxTensor.createTensor(
                        environment, FloatBuffer.allocate(0), longArrayOf(1L, HEADS, 0L, HEAD_DIM),
                    )
                }
            }
        }
        return result
    }

    private fun rollPast(
        environment: OrtEnvironment,
        result: OrtSession.Result,
        wasCache: Boolean,
        previous: Map<String, OnnxTensor>,
    ): MutableMap<String, OnnxTensor> {
        val next = linkedMapOf<String, OnnxTensor>()
        try {
            for (layer in 0 until LAYERS) {
                for (kv in listOf("key", "value")) {
                    val decoderName = "present." + layer + ".decoder." + kv
                    val encoderName = "present." + layer + ".encoder." + kv
                    next["past_key_values." + layer + ".decoder." + kv] = copyTensor(environment, result, decoderName)
                    next["past_key_values." + layer + ".encoder." + kv] =
                        if (wasCache) previous.getValue("past_key_values." + layer + ".encoder." + kv)
                        else copyTensor(environment, result, encoderName)
                }
            }
        } catch (error: Exception) {
            next.forEach { (name, tensor) ->
                if (!wasCache || !name.contains(".encoder.")) runCatching { tensor.close() }
            }
            throw error
        }
        previous.forEach { (name, tensor) ->
            if (!wasCache || !name.contains(".encoder.")) runCatching { tensor.close() }
        }
        return next
    }

    private fun copyTensor(environment: OrtEnvironment, result: OrtSession.Result, name: String): OnnxTensor {
        val output = result.get(name).orElseThrow { IOException("SMaLL-100 解码器缺少缓存输出：" + name) }
            as? OnnxTensor ?: throw IOException("SMaLL-100 缓存输出不是张量：" + name)
        val shape = (output.info as? TensorInfo)?.shape ?: throw IOException("缓存张量形状缺失：" + name)
        val source = output.floatBuffer.duplicate()
        val buffer = ByteBuffer.allocateDirect(source.remaining() * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(source)
        buffer.flip()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun longTensor(environment: OrtEnvironment, values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), longArrayOf(1L, values.size.toLong()))

    private fun argmaxLast(tensor: OnnxTensor): Long {
        val shape = (tensor.info as? TensorInfo)?.shape ?: throw IOException("SMaLL-100 logits 形状缺失")
        if (shape.size < 2) throw IOException("SMaLL-100 logits 维度异常")
        val vocabulary = shape.last().toInt()
        val sequence = shape[shape.size - 2].toInt()
        if (vocabulary <= 0 || sequence <= 0) throw IOException("SMaLL-100 logits 维度为空")
        val buffer = tensor.floatBuffer.duplicate()
        val start = (sequence - 1) * vocabulary
        if (buffer.limit() < start + vocabulary) throw IOException("SMaLL-100 logits 数据长度不足")
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (index in 0 until vocabulary) {
            val score = buffer.get(start + index)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex.toLong()
    }

    private fun cleanChineseSpacing(text: String): String = text
        .replace(Regex("(?<=[\\u4E00-\\u9FFF])\\s+(?=[\\u4E00-\\u9FFF])"), "")
        .replace(Regex("\\s+([，。！？；：、）》】」』])"), "$1")
        .replace(Regex("([（【「『])\\s+"), "$1")
        .trim()
}

/**
 * Pure-Kotlin SentencePiece-BPE tokenizer for the pinned SMaLL-100 `tokenizer.json`.
 * Avoids desktop-only JNI dependencies (`ai.djl.huggingface:tokenizers`) that fail to link on Android Bionic.
 */
internal class Small100BpeTokenizer(
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String?>,
    private val merges: Map<String, Int>,
    private val specialIds: Set<Int>,
    private val unkId: Int = DEFAULT_UNK_ID,
    private val eosId: Int = DEFAULT_EOS_ID,
    private val fuseUnk: Boolean = true,
) : AutoCloseable {

    fun encode(text: String): LongArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return longArrayOf(eosId.toLong())

        val ids = ArrayList<Long>(normalized.length + 4)
        var start = 0
        while (start < normalized.length) {
            val spaceIdx = normalized.indexOf(' ', start)
            val word = if (spaceIdx < 0) normalized.substring(start) else normalized.substring(start, spaceIdx)
            start = if (spaceIdx < 0) normalized.length else spaceIdx + 1
            if (word.isEmpty()) continue

            val pieces = splitToCodePointStrings("$METASPACE$word")
            val bpeTokens = applyBpe(pieces)
            for (piece in bpeTokens) {
                val id = vocab[piece] ?: unkId
                if (fuseUnk && id == unkId && ids.isNotEmpty() && ids.last() == unkId.toLong()) {
                    continue
                }
                ids.add(id.toLong())
            }
        }
        ids.add(eosId.toLong())
        return ids.toLongArray()
    }

    fun decode(ids: LongArray, skipSpecialTokens: Boolean = true): String {
        val sb = StringBuilder(ids.size * 3)
        for (rawId in ids) {
            val id = rawId.toInt()
            if (skipSpecialTokens && isSpecialTokenId(id)) continue
            val token = if (id in idToToken.indices) idToToken[id] else null
            if (token != null) {
                sb.append(token)
            }
        }
        for (i in 0 until sb.length) {
            if (sb[i] == METASPACE) sb.setCharAt(i, ' ')
        }
        return sb.toString().trim()
    }

    private fun isSpecialTokenId(id: Int): Boolean =
        id in 0..3 || id in 128004..128111 || id in specialIds

    private fun applyBpe(initialPieces: MutableList<String>): List<String> {
        var pieces = initialPieces
        while (pieces.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestFirst = ""
            var bestSecond = ""
            for (i in 0 until pieces.size - 1) {
                val first = pieces[i]
                val second = pieces[i + 1]
                val rank = merges["$first $second"] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestFirst = first
                    bestSecond = second
                }
            }
            if (bestRank == Int.MAX_VALUE) break

            val merged = ArrayList<String>(pieces.size - 1)
            var i = 0
            while (i < pieces.size) {
                if (i + 1 < pieces.size && pieces[i] == bestFirst && pieces[i + 1] == bestSecond) {
                    merged.add(bestFirst + bestSecond)
                    i += 2
                } else {
                    merged.add(pieces[i])
                    i += 1
                }
            }
            pieces = merged
        }
        return pieces
    }

    override fun close() {
        // Pure JVM data structures; no native handles to release.
    }

    companion object {
        private const val METASPACE = '\u2581'
        private const val DEFAULT_UNK_ID = 3
        private const val DEFAULT_EOS_ID = 2

        internal fun normalize(text: String): String {
            if (text.isEmpty()) return ""
            val sb = StringBuilder(text.length)
            var lastWasSpace = true
            for (i in 0 until text.length) {
                val mapped = when (val c = text[i]) {
                    '，' -> ','
                    '？' -> '?'
                    '！' -> '!'
                    '：' -> ':'
                    '；' -> ';'
                    '（' -> '('
                    '）' -> ')'
                    '％' -> '%'
                    '\u3000' -> ' '
                    else -> c
                }
                if (mapped.isWhitespace()) {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                } else {
                    sb.append(mapped)
                    lastWasSpace = false
                }
            }
            if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.setLength(sb.length - 1)
            }
            return sb.toString()
        }

        private fun splitToCodePointStrings(input: String): MutableList<String> {
            val list = ArrayList<String>(input.length)
            var index = 0
            while (index < input.length) {
                val cp = input.codePointAt(index)
                val charCount = Character.charCount(cp)
                list.add(input.substring(index, index + charCount))
                index += charCount
            }
            return list
        }

        fun fromStream(input: InputStream): Small100BpeTokenizer {
            val reader = StreamingJsonTokenizerReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024))
            return reader.parseTokenizer()
        }
    }
}

private class StreamingJsonTokenizerReader(private val reader: BufferedReader) {
    private var lookahead: Int = -2

    fun parseTokenizer(): Small100BpeTokenizer {
        val vocab = HashMap<String, Int>(180_000)
        var idToToken = arrayOfNulls<String>(131_072)
        val merges = HashMap<String, Int>(320_000)
        val specialIds = HashSet<Int>(256)
        var fuseUnk = true

        fun putToken(token: String, id: Int, isSpecial: Boolean) {
            if (id < 0) return
            vocab[token] = id
            if (id >= idToToken.size) {
                idToToken = idToToken.copyOf(maxOf(id + 1, idToToken.size * 2))
            }
            idToToken[id] = token
            if (isSpecial || token.startsWith("<madeupword")) {
                specialIds.add(id)
            }
        }

        expectChar('{')
        if (peekNonWs() != '}'.code) {
            while (true) {
                val key = readString()
                expectChar(':')
                when (key) {
                    "added_tokens" -> {
                        expectChar('[')
                        if (peekNonWs() != ']'.code) {
                            while (true) {
                                expectChar('{')
                                var id = -1
                                var content: String? = null
                                var special = false
                                if (peekNonWs() != '}'.code) {
                                    while (true) {
                                        val entryKey = readString()
                                        expectChar(':')
                                        when (entryKey) {
                                            "id" -> id = readInt()
                                            "content" -> content = readString()
                                            "special" -> special = readBoolean()
                                            else -> skipValue()
                                        }
                                        val sep = nextNonWs()
                                        if (sep == '}'.code) break
                                        if (sep != ','.code) throw IOException("解析 added_tokens 对象失败")
                                    }
                                } else {
                                    nextNonWs()
                                }
                                if (content != null && id >= 0) {
                                    putToken(content, id, special)
                                }
                                val arrSep = nextNonWs()
                                if (arrSep == ']'.code) break
                                if (arrSep != ','.code) throw IOException("解析 added_tokens 数组失败")
                            }
                        } else {
                            nextNonWs()
                        }
                    }
                    "model" -> {
                        expectChar('{')
                        if (peekNonWs() != '}'.code) {
                            while (true) {
                                val modelKey = readString()
                                expectChar(':')
                                when (modelKey) {
                                    "fuse_unk" -> fuseUnk = readBoolean()
                                    "vocab" -> {
                                        expectChar('{')
                                        if (peekNonWs() != '}'.code) {
                                            while (true) {
                                                val token = readString()
                                                expectChar(':')
                                                val id = readInt()
                                                putToken(token, id, false)
                                                val vSep = nextNonWs()
                                                if (vSep == '}'.code) break
                                                if (vSep != ','.code) throw IOException("解析 vocab 词表失败")
                                            }
                                        } else {
                                            nextNonWs()
                                        }
                                    }
                                    "merges" -> {
                                        expectChar('[')
                                        var rank = 0
                                        if (peekNonWs() != ']'.code) {
                                            while (true) {
                                                val next = peekNonWs()
                                                val pair = if (next == '['.code) {
                                                    expectChar('[')
                                                    val first = readString()
                                                    expectChar(',')
                                                    val second = readString()
                                                    expectChar(']')
                                                    "$first $second"
                                                } else {
                                                    readString()
                                                }
                                                merges.putIfAbsent(pair, rank++)
                                                val mSep = nextNonWs()
                                                if (mSep == ']'.code) break
                                                if (mSep != ','.code) throw IOException("解析 merges 规则失败")
                                            }
                                        } else {
                                            nextNonWs()
                                        }
                                    }
                                    else -> skipValue()
                                }
                                val modelSep = nextNonWs()
                                if (modelSep == '}'.code) break
                                if (modelSep != ','.code) throw IOException("解析 model 字段失败")
                            }
                        } else {
                            nextNonWs()
                        }
                    }
                    else -> skipValue()
                }
                val topSep = nextNonWs()
                if (topSep == '}'.code) break
                if (topSep != ','.code) throw IOException("解析 tokenizer.json 顶层字段失败")
            }
        } else {
            nextNonWs()
        }

        if (vocab.isEmpty()) {
            throw IOException("SMaLL-100 tokenizer.json 词表为空")
        }
        return Small100BpeTokenizer(
            vocab = vocab,
            idToToken = idToToken,
            merges = merges,
            specialIds = specialIds,
            unkId = vocab["<unk>"] ?: 3,
            eosId = vocab["</s>"] ?: 2,
            fuseUnk = fuseUnk,
        )
    }

    private fun read(): Int {
        val current = lookahead
        if (current != -2) {
            lookahead = -2
            return current
        }
        return reader.read()
    }

    private fun peek(): Int {
        if (lookahead == -2) {
            lookahead = reader.read()
        }
        return lookahead
    }

    private fun nextNonWs(): Int {
        while (true) {
            val ch = read()
            if (ch < 0) return -1
            if (ch != ' '.code && ch != '\t'.code && ch != '\n'.code && ch != '\r'.code) {
                return ch
            }
        }
    }

    private fun peekNonWs(): Int {
        while (true) {
            val ch = peek()
            if (ch < 0) return -1
            if (ch != ' '.code && ch != '\t'.code && ch != '\n'.code && ch != '\r'.code) {
                return ch
            }
            read()
        }
    }

    private fun expectChar(expected: Char) {
        val actual = nextNonWs()
        if (actual != expected.code) {
            throw IOException("JSON 语法错误：期望 '$expected'，实际为 '${if (actual < 0) "EOF" else actual.toChar()}'")
        }
    }

    private fun readString(): String {
        expectChar('"')
        val sb = StringBuilder()
        while (true) {
            val ch = read()
            if (ch < 0) throw IOException("JSON 字符串未闭合")
            if (ch == '"'.code) return sb.toString()
            if (ch == '\\'.code) {
                when (val esc = read()) {
                    '"'.code -> sb.append('"')
                    '\\'.code -> sb.append('\\')
                    '/'.code -> sb.append('/')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000C')
                    'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r')
                    't'.code -> sb.append('\t')
                    'u'.code -> {
                        var code = 0
                        repeat(4) {
                            val hex = read()
                            val digit = Character.digit(hex, 16)
                            if (digit < 0) throw IOException("JSON unicode 转义无效")
                            code = (code shl 4) or digit
                        }
                        sb.append(code.toChar())
                    }
                    else -> throw IOException("JSON 转义字符无效")
                }
            } else {
                sb.append(ch.toChar())
            }
        }
    }

    private fun skipString() {
        expectChar('"')
        while (true) {
            val ch = read()
            if (ch < 0) throw IOException("JSON 字符串未闭合")
            if (ch == '"'.code) return
            if (ch == '\\'.code) {
                val esc = read()
                if (esc == 'u'.code) {
                    repeat(4) { read() }
                }
            }
        }
    }

    private fun readInt(): Int {
        val first = nextNonWs()
        if (first < 0) throw IOException("JSON 数字缺失")
        val sb = StringBuilder()
        sb.append(first.toChar())
        while (true) {
            val p = peek()
            if (p in '0'.code..'9'.code || p == '-'.code || p == '+'.code || p == '.'.code || p == 'e'.code || p == 'E'.code) {
                sb.append(read().toChar())
            } else {
                break
            }
        }
        return sb.toString().toIntOrNull()
            ?: sb.toString().toDoubleOrNull()?.toInt()
            ?: throw IOException("JSON 整数格式错误：$sb")
    }

    private fun readBoolean(): Boolean {
        val token = readPrimitiveToken()
        return when (token) {
            "true" -> true
            "false" -> false
            else -> throw IOException("JSON 布尔值错误：$token")
        }
    }

    private fun readPrimitiveToken(): String {
        val first = nextNonWs()
        if (first < 0) throw IOException("JSON 值缺失")
        val sb = StringBuilder()
        sb.append(first.toChar())
        while (true) {
            val p = peek()
            if (p < 0 || p == ','.code || p == '}'.code || p == ']'.code ||
                p == ' '.code || p == '\t'.code || p == '\n'.code || p == '\r'.code
            ) {
                break
            }
            sb.append(read().toChar())
        }
        return sb.toString()
    }

    private fun skipValue() {
        when (val next = peekNonWs()) {
            '{'.code -> {
                nextNonWs()
                if (peekNonWs() == '}'.code) {
                    nextNonWs()
                    return
                }
                while (true) {
                    skipString()
                    expectChar(':')
                    skipValue()
                    val sep = nextNonWs()
                    if (sep == '}'.code) return
                    if (sep != ','.code) throw IOException("跳过 JSON 对象失败")
                }
            }
            '['.code -> {
                nextNonWs()
                if (peekNonWs() == ']'.code) {
                    nextNonWs()
                    return
                }
                while (true) {
                    skipValue()
                    val sep = nextNonWs()
                    if (sep == ']'.code) return
                    if (sep != ','.code) throw IOException("跳过 JSON 数组失败")
                }
            }
            '"'.code -> skipString()
            -1 -> throw IOException("JSON 意外结束")
            else -> {
                readPrimitiveToken()
            }
        }
    }
}
