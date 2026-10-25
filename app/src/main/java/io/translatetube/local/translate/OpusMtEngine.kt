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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Array as JavaArray
import java.nio.file.Paths
import java.util.Locale

/** First Android OPUS-MT model: English -> Simplified Chinese (quantized ONNX). */
class OpusMtEngine(private val context: Context) : TranslationEngine {
    override val id: String = "opus-mt"
    override val displayName: String = "OPUS-MT 本地翻译"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val source = SourceLanguagePolicy.normalizeTag(srcLang).substringBefore('-').lowercase(Locale.ROOT)
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (source !in setOf("en", "eng")) {
            throw IllegalStateException("当前 OPUS-MT 模型只支持英语 → 简体中文，请选择已下载对应语言包的 ML Kit。")
        }
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")) {
            throw IllegalStateException("当前 OPUS-MT 模型的目标语言是简体中文。")
        }
        if (!OpusMtModelManager.isReady(context)) {
            throw IllegalStateException("OPUS-MT 英语 → 中文模型尚未下载或验证，请到本地翻译中继续下载。")
        }

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
        return try {
            withContext(Dispatchers.Default) {
                val output = ArrayList<String>(lines.size)
                for ((index, line) in lines.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    output += if (line.isBlank()) "" else OpusMtRuntime.translateOne(context, line)
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
                    "模型文件已验证" to OpusMtModelManager.isReady(context).toString(),
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

        fun translateOne(text: String): String {
            val sourcePieces = sourceTokenizer.encodeNormalized(text, algorithm)
            val sourceIds = (sourcePieces + 0).map { it.toLong() }.toLongArray()
            if (sourceIds.isEmpty()) return ""
            val sourceMask = LongArray(sourceIds.size) { 1L }
            val hidden = encode(sourceIds, sourceMask)
            val decoderIds = arrayListOf(DECODER_START_TOKEN)
            val generated = ArrayList<Int>()
            val maxTokens = (sourceIds.size * 2 + 16).coerceIn(24, 128)

            for (step in 0 until maxTokens) {
                val token = decodeNext(decoderIds, hidden.first, hidden.second, sourceMask)
                if (token == EOS_TOKEN || token == PAD_TOKEN) break
                decoderIds += token.toLong()
                generated += token
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
            hiddenValues: FloatArray,
            hiddenShape: LongArray,
            sourceMask: LongArray,
        ): Int {
            val inputs = linkedMapOf<String, OnnxTensor>()
            var hiddenTensor: OnnxTensor? = null
            try {
                hiddenTensor = floatTensor(hiddenValues, hiddenShape)
                decoder.inputNames.forEach { name ->
                    when {
                        name.contains("decoder_input_ids") || name == "input_ids" ->
                            inputs[name] = longTensor(
                                decoderIds.toLongArray(), longArrayOf(1, decoderIds.size.toLong()),
                            )
                        name.contains("encoder_hidden_states") || name.contains("encoder_outputs") ->
                            inputs[name] = hiddenTensor!!
                        name.contains("encoder_attention_mask") || name == "attention_mask" ->
                            inputs[name] = longTensor(
                                sourceMask, longArrayOf(1, sourceMask.size.toLong()),
                            )
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
                inputs.values.toSet().forEach { runCatching { it.close() } }
                if (inputs.values.none { it === hiddenTensor }) runCatching { hiddenTensor?.close() }
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

    @Synchronized
    fun translateOne(context: Context, text: String): String = load(context).translateOne(text)

    /** Load all files and execute a small inference before the download UI reports the model ready. */
    fun validate(context: Context) {
        val sample = translateOne(context, "Hello.")
        if (sample.isBlank()) throw IllegalStateException("OPUS-MT 模型验证失败：测试句未返回译文")
    }

    @Synchronized
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

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val minimumFreeBytes = 700L * 1024L * 1024L
            if (memoryInfo.availMem < minimumFreeBytes) {
                throw IllegalStateException(
                    "手机当前可用内存不足，至少需要约 700 MB 空闲内存才能加载 OPUS-MT。请关闭其他应用后重试。"
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
            return next
        } catch (error: Exception) {
            runCatching { encoder?.close() }
            runCatching { decoder?.close() }
            throw IllegalStateException("OPUS-MT 模型加载失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            runCatching { options.close() }
        }
    }

    @Synchronized
    fun closeLoadedModel() {
        loaded?.close()
        loaded = null
    }

    private const val DECODER_START_TOKEN = 65000L
    private const val PAD_TOKEN = 65000
    private const val EOS_TOKEN = 0
}
