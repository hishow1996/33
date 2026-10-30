package io.translatetube.local.translate

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.sentencepiece.Model
import com.sentencepiece.Scoring
import com.sentencepiece.SentencePieceAlgorithm
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.lang.reflect.Array as JavaArray
import java.nio.file.Paths
import java.util.Locale

/** First Android OPUS-MT model: English -> Simplified Chinese (quantized ONNX). */
internal class OpusMtLineLimitException(message: String) : IllegalArgumentException(message)

class OpusMtEngine(private val context: Context) : TranslationEngine {
    override val id: String = "opus-mt"
    override val displayName: String = "OPUS-MT 本地翻译"

    private val runtimeLeaseLock = Any()
    private val leasedRuntimes = linkedSetOf<String>()

    private fun retainRuntimeLease(source: String) {
        synchronized(runtimeLeaseLock) {
            if (source == "en") {
                if (leasedRuntimes.add("onnx")) OpusMtRuntime.acquireLease()
            } else {
                if (leasedRuntimes.add("ct2")) OpusMtCt2Runtime.acquireLease()
            }
        }
    }

    override fun close() {
        val released = synchronized(runtimeLeaseLock) {
            leasedRuntimes.toList().also { leasedRuntimes.clear() }
        }
        if ("onnx" in released) OpusMtRuntime.releaseLease()
        if ("ct2" in released) OpusMtCt2Runtime.releaseLease()
    }

    /** Validate language compatibility and model readiness before the playback batch loop begins. */
    suspend fun validateConfiguration(
        srcLang: String,
        tgtLang: String,
        sampleLines: List<String> = emptyList(),
    ): String {
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")) {
            throw IllegalStateException("当前 OPUS-MT 模型的目标语言是简体中文。")
        }

        val normalizedSource = SourceLanguagePolicy.sourceForTranslation(srcLang)
        val sourceUndetermined = SourceLanguagePolicy.isUndetermined(normalizedSource)
        // Some callers preflight the engine before subtitle text is available. Defer automatic
        // language/model validation until the actual batch reaches translate().
        if (sourceUndetermined && sampleLines.none { it.isNotBlank() }) return "auto"

        val detectedSource = if (sourceUndetermined) {
            io.translatetube.local.subs.MlKitSourceLanguageIdentifier.detect(sampleLines)
        } else normalizedSource
        if (SourceLanguagePolicy.isUndetermined(detectedSource)) {
            val nonBlankCues = sampleLines.count { it.isNotBlank() }
            val sampleChars = sampleLines.filter { it.isNotBlank() }.sumOf { it.length }
            AppLogStore.warning(
                context.applicationContext,
                "Translation",
                "OPUS-MT 自动识别未能可靠确定源语言",
                diagnosticContext = mapOf(
                    "检测方式" to "设备端 ML Kit 语言识别与文字系统辅助判断",
                    "采样字幕条数" to nonBlankCues.toString(),
                    "采样字符数" to sampleChars.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            throw IllegalStateException(
                "OPUS-MT 自动识别暂时无法可靠判断源语言。请提供更多原文字幕（建议连续几句）后重试，" +
                    "或在翻译设置中手动选择源语言；为避免翻错，不会擅自猜测。"
            )
        }

        val source = LocalTranslationEnginePolicy.canonicalOpusSource(detectedSource)
        if (sourceUndetermined) {
            AppLogStore.info(
                context.applicationContext,
                "Translation",
                "OPUS-MT 自动识别源语言完成",
                diagnosticContext = mapOf(
                    "识别语言" to LangMap.display(source),
                    "源语言代码" to source,
                    "采样字幕条数" to sampleLines.count { it.isNotBlank() }.toString(),
                    "采样字符数" to sampleLines.filter { it.isNotBlank() }.sumOf { it.length }.toString(),
                    "未记录字幕原文" to "true",
                ),
            )
        }
        if (!LocalTranslationEnginePolicy.supportsOpusSource(source)) {
            val sourceLabel = LangMap.display(source)
            if (SourceLanguagePolicy.isChinese(source)) {
                throw IllegalStateException(
                    "OPUS-MT 已识别源语言为中文，但当前目标语言也是简体中文。请确认原文字幕语言，" +
                        "或切换至支持的其他翻译引擎。"
                )
            }
            throw IllegalStateException(
                "OPUS-MT 已识别源语言为“$sourceLabel”（$source），但当前本地 OPUS-MT 仅支持英语、德语、芬兰语、" +
                    "瑞典语直接翻译至简体中文。日语、韩语、俄语目前没有已验证的应用兼容直译包，不会经英语中转。请切换至 ML Kit/SMaLL-100。"
            )
        }
        if (source == "en") {
            if (!OpusMtModelManager.isReady(context)) {
                throw IllegalStateException("OPUS-MT 英语 → 中文模型尚未下载或验证，请到 API 管理 → 本地翻译中继续下载。")
            }
        } else if (!OpusMtAdditionalModelManager.isReady(context, source)) {
            throw IllegalStateException(
                "OPUS-MT " + OpusMtAdditionalModelManager.label(source) +
                    " 模型尚未下载或验证，请到 API 管理 → 本地翻译中下载并完成试译。"
            )
        }
        return source
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.none { it.isNotBlank() }) return lines.map { "" }
        val source = validateConfiguration(srcLang, tgtLang, lines)
        retainRuntimeLease(source)
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)

        AppLogStore.info(
            context.applicationContext,
            "Translation",
            "开始调用 OPUS-MT 本地模型",
            diagnosticContext = mapOf(
                "引擎" to id,
                "源语言" to source,
                "目标语言" to "zh-CN",
                "字幕条数" to lines.size.toString(),
            ),
        )
        if (source != "en") {
            return try {
                val translated = OpusMtCt2Runtime.translate(context.applicationContext, source, lines)
                if (translated.size != lines.size) {
                    throw IllegalStateException("CTranslate2 返回的译文条数与原字幕不一致")
                }
                AppLogStore.info(
                    context.applicationContext,
                    "Translation",
                    "OPUS-MT CTranslate2 本地直译完成",
                    diagnosticContext = mapOf(
                        "引擎" to id,
                        "源语言" to source,
                        "目标语言" to "zh-CN",
                        "翻译方式" to "OPUS-MT CTranslate2 单模型直译",
                        "字幕条数" to lines.size.toString(),
                        "模型" to OpusMtAdditionalModelManager.officialModelId(source),
                        "未记录字幕原文" to "true",
                    ),
                )
                translated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogStore.error(
                    context.applicationContext,
                    "Translation",
                    "OPUS-MT CTranslate2 直译推理失败",
                    error,
                    diagnosticContext = mapOf(
                        "源语言" to source,
                        "目标语言" to "zh-CN",
                        "翻译方式" to "CTranslate2 单模型直译",
                        "源语言模型已验证" to OpusMtAdditionalModelManager.isReady(context, source).toString(),
                        "未记录字幕原文" to "true",
                    ),
                )
                throw IllegalStateException("OPUS-MT " + OpusMtAdditionalModelManager.label(source) + " 翻译失败：" +
                    (error.message ?: error.javaClass.simpleName), error)
            }
        }

        var activeLineNumber = 0
        var activeLineLength = 0
        return try {
            withContext(Dispatchers.Default) {
                val output = ArrayList<String>(lines.size)
                for ((index, line) in lines.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    activeLineNumber = index + 1
                    activeLineLength = line.length
                    val translated = if (line.isBlank()) "" else try {
                        OpusMtRuntime.translateOne(context, line)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (lineLimit: OpusMtLineLimitException) {
                        AppLogStore.warning(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT 已跳过超过单句处理上限的字幕，将保留该行供上层质量恢复处理",
                            diagnosticContext = mapOf(
                                "字幕序号" to (index + 1).toString(),
                                "字幕总数" to lines.size.toString(),
                                "字幕字符数" to line.length.toString(),
                                "原因" to (lineLimit.message ?: "超过模型单句上限"),
                                "未记录字幕原文" to "true",
                            ),
                        )
                        ""
                    } catch (lineError: Exception) {
                        AppLogStore.warning(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT 单条字幕推理失败，已隔离该行并继续处理其他字幕",
                            lineError,
                            diagnosticContext = mapOf(
                                "字幕序号" to (index + 1).toString(),
                                "字幕总数" to lines.size.toString(),
                                "字幕字符数" to line.length.toString(),
                                "源语言" to source,
                                "目标语言" to "zh-CN",
                                "未记录字幕原文" to "true",
                            ),
                        )
                        // Let the shared batch parser retry this cue without discarding successful
                        // translations around it. A completely failed batch is still rejected there.
                        ""
                    }
                    output += translated
                    if ((index + 1) % 10 == 0 || index == lines.lastIndex) {
                        AppLogStore.info(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT 本地翻译进度",
                            diagnosticContext = mapOf(
                                "已完成字幕" to (index + 1).toString(),
                                "字幕总数" to lines.size.toString(),
                            ),
                        )
                    }
                }
                output
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogStore.error(
                context.applicationContext,
                "Translation",
                "OPUS-MT 本地推理失败",
                error,
                diagnosticContext = mapOf(
                    "模型" to "opus-mt-en-zh-onnx-quantized-v1",
                    "源语言" to source,
                    "目标语言" to "zh-CN",
                    "字幕条数" to lines.size.toString(),
                    "失败字幕序号" to activeLineNumber.toString(),
                    "失败字幕字符数" to activeLineLength.toString(),
                    "模型文件已验证" to OpusMtModelManager.isReady(context).toString(),
                    "未记录字幕原文" to "true",
                ),
            )
            throw IllegalStateException("OPUS-MT 本地翻译失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }
}

internal object OpusMtRuntime {
    private data class Loaded(
        val identity: String,
        val environment: OrtEnvironment,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val sourceTokenizer: Model,
        val targetTokenizer: Model,
        val algorithm: SentencePieceAlgorithm,
    ) {
        fun close() {
            runCatching { encoder.close() }
            runCatching { decoder.close() }
        }

        private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor {
            val buffer = java.nio.ByteBuffer.allocateDirect(values.size * java.lang.Long.BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .asLongBuffer()
            buffer.put(values)
            buffer.flip()
            return OnnxTensor.createTensor(environment, buffer, shape)
        }

        private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
            val buffer = java.nio.ByteBuffer.allocateDirect(values.size * java.lang.Float.BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(values)
            buffer.flip()
            return OnnxTensor.createTensor(environment, buffer, shape)
        }

        suspend fun translateOne(text: String): String {
            val sourcePieces = sourceTokenizer.encodeNormalized(text, algorithm)
            val sourceIds = (sourcePieces + 0).map { it.toLong() }.toLongArray()
            if (sourceIds.isEmpty()) return ""
            if (sourceIds.size > MAX_SOURCE_TOKENS) {
                throw OpusMtLineLimitException(
                    "原文分词后为 ${sourceIds.size} tokens，模型单句输入上限为 ${MAX_SOURCE_TOKENS} tokens"
                )
            }
            val sourceMask = LongArray(sourceIds.size) { 1L }
            val hidden = encode(sourceIds, sourceMask)
            val decoderIds = arrayListOf(DECODER_START_TOKEN)
            val generated = ArrayList<Int>()
            val maxTokens = (sourceIds.size * 2 + 16).coerceIn(24, MAX_OUTPUT_TOKENS)

            var reachedEndToken = false
            var hiddenTensor: OnnxTensor? = null
            var sourceMaskTensor: OnnxTensor? = null
            try {
                // These values are invariant for the full sentence. Keep their native tensors
                // alive across decoder steps instead of copying the encoder state on every token.
                hiddenTensor = floatTensor(hidden.first, hidden.second)
                sourceMaskTensor = longTensor(
                    sourceMask, longArrayOf(1, sourceMask.size.toLong()),
                )
                for (step in 0 until maxTokens) {
                    currentCoroutineContext().ensureActive()
                    val token = decodeNext(decoderIds, hiddenTensor!!, sourceMaskTensor!!)
                    if (token == EOS_TOKEN || token == PAD_TOKEN) {
                        reachedEndToken = true
                        break
                    }
                    decoderIds += token.toLong()
                    generated += token
                }
            } finally {
                runCatching { sourceMaskTensor?.close() }
                runCatching { hiddenTensor?.close() }
            }
            if (!reachedEndToken && generated.size >= maxTokens) {
                throw OpusMtLineLimitException(
                    "译文达到单句生成长度上限（${maxTokens} tokens），已避免静默截断"
                )
            }

            val raw = targetTokenizer.decodeSmart(generated.filter { it != EOS_TOKEN && it != PAD_TOKEN }).trim()
            // SentencePiece decoding inserts word spaces. Remove spaces between CJK characters and
            // before common Chinese punctuation, while preserving spaces around Latin words/numbers.
            return raw
                .replace(Regex("(?<=[\\u4E00-\\u9FFF])\\s+(?=[\\u4E00-\\u9FFF])"), "")
                .replace(Regex("\\s+([，。！？；：、）》】」』])"), "$1")
                .replace(Regex("([（【「『])\\s+"), "$1")
                .trim()
        }

        private fun encode(inputIds: LongArray, attentionMask: LongArray): Pair<FloatArray, LongArray> {
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                encoder.inputNames.forEach { name ->
                    when {
                        name.contains("input_ids") -> inputs[name] = longTensor(
                            inputIds, longArrayOf(1, inputIds.size.toLong()),
                        )
                        name.contains("attention_mask") -> inputs[name] = longTensor(
                            attentionMask, longArrayOf(1, attentionMask.size.toLong()),
                        )
                        else -> throw IOException("不支持的 OPUS-MT 编码器输入：$name")
                    }
                }
                encoder.run(inputs).use { result ->
                    val name = encoder.outputNames.firstOrNull { it.contains("last_hidden_state") }
                        ?: encoder.outputNames.firstOrNull()
                        ?: throw IOException("OPUS-MT 编码器没有输出")
                    val value = result.get(name).orElseThrow { IOException("编码器没有输出：$name") }
                    val info = value.info as? TensorInfo ?: throw IOException("编码器输出不是张量")
                    val shape = info.shape
                    val expectedSize = shape.fold(1L) { total, dimension ->
                        if (dimension <= 0L || total > Int.MAX_VALUE.toLong() / dimension) {
                            throw IOException("编码器输出形状过大或无效")
                        }
                        total * dimension
                    }.toInt()
                    val flat = flattenFloats(value.value, expectedSize)
                    if (flat.isEmpty() || shape.isEmpty()) throw IOException("编码器输出为空")
                    return flat to shape
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }

        private fun decodeNext(
            decoderIds: List<Long>,
            hiddenTensor: OnnxTensor,
            sourceMaskTensor: OnnxTensor,
        ): Int {
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                decoder.inputNames.forEach { name ->
                    when {
                        name.contains("decoder_input_ids") || name == "input_ids" ->
                            inputs[name] = longTensor(
                                decoderIds.toLongArray(), longArrayOf(1, decoderIds.size.toLong()),
                            )
                        name.contains("encoder_hidden_states") || name.contains("encoder_outputs") ->
                            inputs[name] = hiddenTensor
                        name.contains("encoder_attention_mask") || name == "attention_mask" ->
                            inputs[name] = sourceMaskTensor
                        name.contains("decoder_attention_mask") ->
                            inputs[name] = longTensor(
                                LongArray(decoderIds.size) { 1L }, longArrayOf(1, decoderIds.size.toLong()),
                            )
                        else -> throw IOException("不支持的 OPUS-MT 解码器输入：$name")
                    }
                }
                decoder.run(inputs).use { result ->
                    val name = decoder.outputNames.firstOrNull { it.contains("logits") }
                        ?: decoder.outputNames.firstOrNull()
                        ?: throw IOException("OPUS-MT 解码器没有输出")
                    val value = result.get(name).orElseThrow { IOException("解码器没有输出：$name") }
                    val info = value.info as? TensorInfo ?: throw IOException("解码器输出不是张量")
                    val logits = lastTokenLogits(value.value, info.shape)
                    if (logits.isEmpty()) throw IOException("解码器 logits 为空")
                    var best = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (index in logits.indices) {
                        val score = logits[index]
                        if (score > bestScore) {
                            bestScore = score
                            best = index
                        }
                    }
                    return best
                }
            } finally {
                // The encoder state and source mask are owned by translateOne() and reused
                // between decoder calls. Close only the per-step decoder input tensors here.
                val closed = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<OnnxTensor, Boolean>(),
                )
                inputs.values.forEach { tensor ->
                    if (tensor !== hiddenTensor && tensor !== sourceMaskTensor && closed.add(tensor)) {
                        runCatching { tensor.close() }
                    }
                }
            }
        }

        private fun lastTokenLogits(value: Any?, shape: LongArray): FloatArray {
            val vocabulary = shape.lastOrNull()?.toInt() ?: throw IOException("解码器输出形状为空")
            if (vocabulary <= 0) throw IOException("解码器词表维度无效")
            if (value is FloatArray) {
                if (value.size < vocabulary) throw IOException("解码器 logits 长度不足")
                return value.copyOfRange(value.size - vocabulary, value.size)
            }

            var current = value
            if (shape.size >= 3) {
                if (current == null || !current.javaClass.isArray || JavaArray.getLength(current) == 0) {
                    throw IOException("解码器 logits 缺少 batch 维度")
                }
                current = JavaArray.get(current, 0)
            }
            if (shape.size >= 2) {
                val sequenceLength = shape[shape.size - 2].toInt()
                if (sequenceLength <= 0 || current == null || !current.javaClass.isArray ||
                    JavaArray.getLength(current) < sequenceLength
                ) {
                    throw IOException("解码器 logits 缺少序列维度")
                }
                current = JavaArray.get(current, sequenceLength - 1)
            }
            if (current is FloatArray) {
                if (current.size != vocabulary) throw IOException("解码器最后一行 logits 长度异常")
                return current.copyOf()
            }

            // Fallback for alternate ONNX Runtime output representations.
            val flattened = flattenFloats(value, null)
            if (flattened.size < vocabulary) throw IOException("解码器 logits 为空")
            return flattened.copyOfRange(flattened.size - vocabulary, flattened.size)
        }

        /** Uses primitive FloatArray storage instead of boxed Float objects for large ONNX outputs. */
        private fun flattenFloats(value: Any?, expectedSize: Int?): FloatArray {
            var output = FloatArray(expectedSize?.coerceAtLeast(1) ?: 4096)
            var size = 0
            fun visit(current: Any?) {
                if (current == null) return
                when (current) {
                    is Number -> {
                        if (size == output.size) {
                            val nextSize = if (output.size < 1_048_576) output.size * 2 else output.size + 1_048_576
                            output = output.copyOf(nextSize)
                        }
                        output[size++] = current.toFloat()
                    }
                    else -> if (current.javaClass.isArray) {
                        for (index in 0 until JavaArray.getLength(current)) visit(JavaArray.get(current, index))
                    }
                }
            }
            visit(value)
            if (expectedSize != null && size != expectedSize) {
                throw IOException("ONNX 输出元素数量不匹配，预期 $expectedSize，实际 $size")
            }
            return if (size == output.size) output else output.copyOf(size)
        }
    }

    @Volatile private var loaded: Loaded? = null
    private val inferenceMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeLeases = SharedRuntimeLeaseCounter()

    fun acquireLease() {
        runtimeLeases.acquire()
    }

    fun releaseLease() {
        if (!runtimeLeases.release()) return
        closeScope.launch { closeLoadedModel() }
    }

    /** All session use and release is serialized to prevent deleting a model during active inference. */
    suspend fun translateOne(context: Context, text: String): String =
        inferenceMutex.withLock { load(context).translateOne(text) }

    /** Load all files and execute a small inference before the download UI reports the model ready. */
    suspend fun validate(context: Context) {
        val sample = translateOne(context, "Hello.")
        if (sample.isBlank()) {
            throw IllegalStateException("OPUS-MT 模型验证失败：测试句未返回译文")
        }
        if (sample.none { it in '\u4E00'..'\u9FFF' }) {
            throw IllegalStateException("OPUS-MT 模型验证失败：测试句译文中没有中文字符")
        }
        AppLogStore.info(
            context.applicationContext,
            "LocalTranslationModel",
            "OPUS-MT 测试翻译通过",
            diagnosticContext = mapOf(
                "模型" to "opus-mt-en-zh",
                "测试用例" to "固定英语短句",
                "含中文字符" to "true",
                "未记录测试译文" to "true",
            ),
        )
    }

    suspend fun closeLoadedModel() {
        inferenceMutex.withLock {
            runtimeLeases.closeIfUnused {
                loaded?.close()
                loaded = null
            }
        }
    }

    private fun load(context: Context): Loaded {
        if (Build.VERSION.SDK_INT < 26) {
            throw IllegalStateException("当前 Android 版本不支持 OPUS-MT 分词运行环境，需要 Android 8.0 或以上。")
        }
        val dir = OpusMtModelManager.modelDirectory(context)
        val encoderFile = File(dir, "encoder_model_quantized.onnx")
        val decoderFile = File(dir, "decoder_model_quantized.onnx")
        val sourceFile = File(dir, "source.spm")
        val targetFile = File(dir, "target.spm")
        val expectedFiles = listOf(encoderFile, decoderFile, sourceFile, targetFile)
        val missing = expectedFiles.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) throw IOException("OPUS-MT 文件不完整：${missing.joinToString { it.name }}")
        val identity = expectedFiles.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        loaded?.takeIf { it.identity == identity }?.let { return it }
        loaded?.close()
        loaded = null

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val requiredBytes = LocalTranslationMemoryPolicy.requiredAvailableBytes(memoryInfo.totalMem)
            if (!LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(memoryInfo.availMem, memoryInfo.totalMem)) {
                val availableMb = memoryInfo.availMem / LocalTranslationMemoryPolicy.MEGABYTE
                val requiredMb = requiredBytes / LocalTranslationMemoryPolicy.MEGABYTE
                throw IllegalStateException(
                    "OPUS-MT 加载需要更多运行内存：当前约 ${availableMb} MB，可用内存建议至少 " +
                        "${requiredMb} MB。请关闭其他应用后重试，已下载模型无需重新下载。"
                )
            }
        }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        var encoder: OrtSession? = null
        var decoder: OrtSession? = null
        try {
            encoder = environment.createSession(encoderFile.absolutePath, options)
            decoder = environment.createSession(decoderFile.absolutePath, options)
            val sourceTokenizer = Model.parseFrom(Paths.get(sourceFile.absolutePath))
            val targetTokenizer = Model.parseFrom(Paths.get(targetFile.absolutePath))
            val next = Loaded(
                identity,
                environment,
                encoder,
                decoder,
                sourceTokenizer,
                targetTokenizer,
                SentencePieceAlgorithm(true, Scoring.HIGHEST_SCORE),
            )
            loaded = next
            AppLogStore.info(
                context.applicationContext,
                "LocalTranslationModel",
                "OPUS-MT ONNX 会话加载成功",
                diagnosticContext = mapOf(
                    "模型" to "opus-mt-en-zh",
                    "Android API" to Build.VERSION.SDK_INT.toString(),
                    "编码器输入" to encoder.inputNames.sorted().joinToString(","),
                    "编码器输出" to encoder.outputNames.sorted().joinToString(","),
                    "解码器输入" to decoder.inputNames.sorted().joinToString(","),
                    "解码器输出" to decoder.outputNames.sorted().joinToString(","),
                    "分词器文件已加载" to "true",
                    "未记录字幕原文" to "true",
                ),
            )
            return next
        } catch (error: Exception) {
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            throw IllegalStateException("OPUS-MT 模型加载失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            runCatching { options.close() }
        }
    }

    private const val DECODER_START_TOKEN = 65000L
    private const val PAD_TOKEN = 65000
    private const val EOS_TOKEN = 0
    private const val MAX_SOURCE_TOKENS = 512
    private const val MAX_OUTPUT_TOKENS = 256
}
