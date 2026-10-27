package io.translatetube.local.translate

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
)

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
    private const val VALIDATION_SCHEMA = 1

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

    fun hasPartialDownload(context: Context): Boolean {
        val directory = modelDirectory(context)
        return files.any { spec ->
            File(directory, spec.name + ".part").let { it.isFile && it.length() > 0L } ||
                File(directory, spec.name).let { it.isFile && it.length() > 0L }
        } && !isReady(context)
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val directory = modelDirectory(context)
        mutableState.value = when {
            isReady(context) -> Small100DownloadState(
                status = Small100Status.READY, progress = 100, fileIndex = files.size,
                fileCount = files.size, downloadedBytes = TOTAL_BYTES, totalBytes = TOTAL_BYTES,
            )
            hasPartialDownload(context) -> {
                val firstIncomplete = files.indexOfFirst { spec ->
                    val target = File(directory, spec.name)
                    val part = File(directory, spec.name + ".part")
                    !target.isFile || target.length() != spec.bytes || part.isFile
                }.coerceAtLeast(0)
                val spec = files[firstIncomplete]
                val part = File(directory, spec.name + ".part")
                val finalFile = File(directory, spec.name)
                val priorBytes = files.take(firstIncomplete).sumOf { it.bytes }
                val currentBytes = if (part.isFile) part.length() else finalFile.length()
                Small100DownloadState(
                    status = Small100Status.PAUSED,
                    progress = ((priorBytes + currentBytes).coerceAtMost(TOTAL_BYTES) * 100L / TOTAL_BYTES).toInt(),
                    currentFile = spec.name,
                    fileIndex = firstIncomplete + 1,
                    fileCount = files.size,
                    downloadedBytes = (priorBytes + currentBytes).coerceAtMost(TOTAL_BYTES),
                    totalBytes = TOTAL_BYTES,
                    error = if (files.all { entry ->
                            File(directory, entry.name).let { f -> f.isFile && f.length() == entry.bytes }
                        }) "模型文件已下载，等待运行时验证" else "",
                )
            }
            else -> Small100DownloadState(status = Small100Status.NOT_DOWNLOADED)
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val directory = modelDirectory(app)
        if (isReady(app)) {
            refresh(app)
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
            mutableState.value = mutableState.value.copy(
                status = if (hasPartialDownload(app)) Small100Status.PAUSED else Small100Status.NOT_DOWNLOADED,
                error = "",
            )
            AppLogStore.info(app, "LocalTranslationModel", "SMaLL-100 下载已暂停，可从上次进度继续")
            throw cancelled
        } catch (error: Exception) {
            val partial = hasPartialDownload(app)
            mutableState.value = mutableState.value.copy(
                status = if (partial) Small100Status.PAUSED else Small100Status.FAILED,
                error = error.message ?: error.javaClass.simpleName,
            )
            AppLogStore.error(
                app, "LocalTranslationModel", "SMaLL-100 模型下载或验证失败", error,
                diagnosticContext = mapOf(
                    "当前文件" to mutableState.value.currentFile,
                    "是否可续传" to partial.toString(),
                    "失败原因" to (error.message ?: error.javaClass.simpleName),
                ),
            )
            throw error
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
            progress = (downloaded * 100L / TOTAL_BYTES).toInt().coerceIn(0, 100),
            currentFile = spec.name, fileIndex = index + 1, fileCount = files.size,
            downloadedBytes = downloaded, totalBytes = TOTAL_BYTES,
        )
    }

    private suspend fun downloadFile(directory: File, spec: ModelFile, index: Int) = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val target = File(directory, spec.name)
        val part = File(directory, spec.name + ".part")
        var existingBytes = if (part.isFile) part.length() else 0L
        var rangeRetryUsed = false
        val bytesBefore = files.take(index).sumOf { it.bytes }

        while (true) {
            coroutineContext.ensureActive()
            val url = "https://huggingface.co/casawolice/small100-onnx/resolve/" +
                MODEL_REVISION + "/" + spec.remotePath
            val builder = Request.Builder().url(url).get()
            if (existingBytes > 0L) builder.header("Range", "bytes=" + existingBytes + "-")
            var restartFromZero = false
            client.newCall(builder.build()).execute().use { response ->
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
                    val append = existingBytes > 0L && response.code == 206
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
                    if (target.exists() && !target.delete()) throw IOException("无法替换模型文件：" + spec.name)
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        if (!part.delete()) throw IOException("无法清理模型临时文件：" + spec.name)
                    }
                    updateProgress(index, spec, spec.bytes, bytesBefore)
                }
            }
            if (restartFromZero) continue
            return@withContext
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
                    Small100Runtime.translateOne(appContext, line, "zh")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (lineLimit: Small100InputTooLongException) {
                    AppLogStore.warning(
                        appContext,
                        "Translation",
                        "SMaLL-100 单条字幕超过输入长度上限，已隔离该行并继续处理其他字幕",
                        lineLimit,
                        diagnosticContext = mapOf(
                            "字幕序号" to (index + 1).toString(),
                            "字幕总数" to lines.size.toString(),
                            "字幕字符数" to line.length.toString(),
                            "输入 token 数" to lineLimit.actualTokens.toString(),
                            "模型上限" to lineLimit.maxTokens.toString(),
                            "未记录字幕原文" to "true",
                        ),
                    )
                    ""
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
    private const val MAX_OUTPUT_TOKENS = Small100GenerationPolicy.MAX_OUTPUT_TOKENS
    private val inferenceMutex = Mutex()
    private val loadedLock = Any()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loaded: Loaded? = null

    private data class Loaded(
        val identity: String,
        val environment: OrtEnvironment,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
        val langToId: Map<String, Long>,
    ) {
        fun close() {
            runCatching { encoder.close() }
            runCatching { decoder.close() }
            runCatching { tokenizer.close() }
        }

        fun translateOne(text: String, targetCode: String, checkActive: () -> Unit): String {
            val targetId = langToId[targetCode] ?: throw IllegalArgumentException("模型缺少目标语言代码：" + targetCode)
            val encoded = tokenizer.encode(text).ids
            val sourceIds = LongArray(encoded.size + 1)
            sourceIds[0] = targetId
            encoded.copyInto(sourceIds, destinationOffset = 1)
            if (sourceIds.size > MAX_SOURCE_TOKENS) {
                throw Small100InputTooLongException(
                    actualTokens = sourceIds.size,
                    maxTokens = MAX_SOURCE_TOKENS,
                )
            }
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
                for (step in 0 until MAX_OUTPUT_TOKENS) {
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
                Small100GenerationPolicy.requireComplete(reachedEos, generated.size)
                val translation = tokenizer.decode(generated.toLongArray(), true).trim()
                if (translation.isBlank()) throw IllegalStateException("SMaLL-100 返回了空译文。")
                return cleanChineseSpacing(translation)
            } finally {
                past.values.forEach { runCatching { it.close() } }
                runCatching { encoderResult?.close() }
                runCatching { inputIdsTensor.close() }
                runCatching { maskTensor.close() }
            }
        }
    }

    suspend fun translateOne(context: Context, text: String, targetCode: String): String {
        val taskContext = currentCoroutineContext()
        return inferenceMutex.withLock {
            taskContext.ensureActive()
            load(context).translateOne(text, targetCode) { taskContext.ensureActive() }
        }
    }

    suspend fun validate(context: Context) {
        val result = translateOne(context, "Hello.", "zh")
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
            val minAvailable = 1_000L * 1024L * 1024L
            if (memory.availMem < minAvailable) {
                throw IllegalStateException(
                    "SMaLL-100 模型约占 615 MB 磁盘空间，建议至少有 1 GB 可用运行内存。请关闭其他应用后重试。"
                )
            }
        }

        loaded?.close()
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        var encoder: OrtSession? = null
        var decoder: OrtSession? = null
        var tokenizer: HuggingFaceTokenizer? = null
        try {
            val encoderSession = environment.createSession(encoderFile.absolutePath, options)
            encoder = encoderSession
            val decoderSession = environment.createSession(decoderFile.absolutePath, options)
            decoder = decoderSession
            val hfTokenizer = tokenizerFile.inputStream().use { input ->
                HuggingFaceTokenizer.newInstance(input, null)
            }
            tokenizer = hfTokenizer
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
            val result = Loaded(identity, environment, encoderSession, decoderSession, hfTokenizer, parsedTokens)
            loaded = result
            result
        } catch (error: Exception) {
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            runCatching { tokenizer?.close() }
            throw error
        } finally {
            runCatching { options.close() }
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
