package io.translatetube.local.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.translatetube.local.player.Wsola
import io.translatetube.local.util.AppLogStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 免 Key 谷歌在线语音引擎（Google Translate TTS）：
 * 调用谷歌翻译朗读接口获取 MP3 音频流并解码为 24kHz 单声道 FloatArray PCM。
 * 多端点自动互备（tw-ob / gtx / dict-chrome-ex），无需 API Key、不占本地算力。
 */
internal class GoogleTts(context: Context) : TtsEngine {
    private val appCtx = context.applicationContext

    override val name: String = "谷歌在线语音"
    override val cacheKey: String = "google-tts-v2"
    override val speakerIdAffectsOutput: Boolean = false

    private val closed = AtomicBoolean(false)

    /**
     * Index (in the fixed endpoint list) of the endpoint that last succeeded. Later requests start
     * there, so a blocked or slow first endpoint no longer costs every cue a failed attempt
     * (up to the connect timeout) before the working endpoint is reached.
     */
    @Volatile private var preferredEndpoint = 0

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthCancellable(text, speed, speakerId) {}

    override fun synthCancellable(
        text: String,
        speed: Float,
        speakerId: Int,
        checkActive: () -> Unit,
    ): TtsAudio {
        checkOpen()
        checkActive()
        val spoken = TtsTextPolicy.sanitizeForSpeech(text)
        if (spoken.isBlank() || !TtsTextPolicy.hasSpeakableContent(spoken)) {
            return TtsAudio(FloatArray(0), TARGET_RATE_HZ)
        }
        TtsTextPolicy.validate(spoken)
        val normalizedSpeed = TtsProviderSpeedPolicy.normalize(speed)
        val chunks = splitForRequest(spoken)
        val pcmChunks = ArrayList<FloatArray>()
        var totalSamples = 0
        var sampleRate = TARGET_RATE_HZ
        for (chunk in chunks) {
            checkOpen()
            checkActive()
            val decoded = fetchAndDecodeWithFallback(chunk, normalizedSpeed, checkActive)
            if (decoded.second <= 0 || decoded.first.isEmpty()) continue
            if (pcmChunks.isEmpty()) sampleRate = decoded.second
            val normalizedSamples = if (decoded.second == sampleRate) {
                decoded.first
            } else {
                resampleTo(decoded.first, decoded.second, sampleRate)
            }
            pcmChunks.add(normalizedSamples)
            totalSamples += normalizedSamples.size
        }
        if (totalSamples <= 0) {
            throw IllegalStateException("谷歌在线语音未返回有效音频，请检查网络连接或切换其他配音引擎")
        }
        var samples = FloatArray(totalSamples)
        var cursor = 0
        for ((index, chunkSamples) in pcmChunks.withIndex()) {
            if (index % 8 == 0) {
                checkOpen()
                checkActive()
            }
            System.arraycopy(chunkSamples, 0, samples, cursor, chunkSamples.size)
            cursor += chunkSamples.size
        }
        if (sampleRate != TARGET_RATE_HZ) {
            samples = resampleTo(samples, sampleRate, TARGET_RATE_HZ)
            sampleRate = TARGET_RATE_HZ
        }
        return TtsAudio(samples, sampleRate)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw java.util.concurrent.CancellationException("谷歌在线语音实例已关闭")
    }

    private fun splitForRequest(text: String): List<String> {
        if (text.length <= MAX_QUERY_CHARS) return listOf(text)
        val breakers = setOf('。', '！', '？', '；', '，', '、', ',', '.', '!', '?', ';', ' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (current.length + 1 > MAX_QUERY_CHARS && current.isNotEmpty()) {
                out.add(current.toString().trim())
                current = StringBuilder()
            }
            current.append(ch)
            if (ch in breakers && current.length >= SOFT_CHUNK_CHARS) {
                out.add(current.toString().trim())
                current = StringBuilder()
            }
        }
        if (current.isNotEmpty()) out.add(current.toString().trim())
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(MAX_QUERY_CHARS)) }
    }

    private fun fetchAndDecodeWithFallback(
        text: String,
        requestedSpeed: Float,
        checkActive: () -> Unit,
    ): Pair<FloatArray, Int> {
        val normalizedSpeed = TtsProviderSpeedPolicy.normalize(requestedSpeed)
        val nativeSpeed = TtsProviderSpeedPolicy.googleNativeSpeed(normalizedSpeed)
        val encoded = URLEncoder.encode(text, "UTF-8")
        val baseUrls = listOf(
            "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&ttsspeed=$nativeSpeed&q=$encoded",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=zh-CN&ttsspeed=$nativeSpeed&q=$encoded",
            "https://translate.google.com/translate_tts?ie=UTF-8&client=dict-chrome-ex&tl=zh-CN&ttsspeed=$nativeSpeed&q=$encoded",
        )
        val firstEndpoint = preferredEndpoint.coerceIn(0, baseUrls.lastIndex)
        val order = List(baseUrls.size) { (firstEndpoint + it) % baseUrls.size }
        val urls = order.map { baseUrls[it] }
        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            checkOpen()
            checkActive()
            var stage = "HTTP 音频请求"
            var responseBytes = 0
            try {
                val bytes = httpGet(url)
                responseBytes = bytes.size
                checkOpen()
                checkActive()
                stage = "MP3 音频解码"
                val decoded = decodeMp3(bytes, checkActive)
                checkOpen()
                checkActive()
                stage = "音频变速处理"
                val postStretchRatio = TtsProviderSpeedPolicy.googlePostStretchRatio(normalizedSpeed)
                val samples = if (postStretchRatio < 0.999f) {
                    Wsola.stretch(decoded.first, postStretchRatio)
                } else {
                    decoded.first
                }
                checkOpen()
                checkActive()
                preferredEndpoint = order[index]
                return samples to decoded.second
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                lastError = e
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "未知" }
                val path = runCatching { android.net.Uri.parse(url).path }.getOrNull().orEmpty().take(160)
                val willTryNext = index < urls.lastIndex
                val httpFailure = e as? GoogleTtsHttpException
                AppLogStore.warning(
                    appCtx,
                    "GoogleTTS",
                    "Google 在线 TTS 当前接口端点失败，${if (willTryNext) "继续尝试下一个端点" else "已无备用端点"}",
                    e,
                    diagnosticContext = mapOf(
                        "服务商" to "Google Translate TTS",
                        "失败阶段" to stage,
                        "当前端点序号" to (order[index] + 1).toString(),
                        "候选端点总数" to urls.size.toString(),
                        "请求主机" to host,
                        "请求路径（不含查询参数）" to path,
                        "完整请求 URL 是否写入日志" to "否",
                        "当前文本字符数" to text.length.toString(),
                        "当前字幕正文是否写入日志" to "否",
                        "请求语速" to normalizedSpeed.toString(),
                        "HTTP 返回音频字节数" to responseBytes.toString(),
                        "HTTP 状态码" to (httpFailure?.statusCode?.toString() ?: "未收到非成功响应"),
                        "HTTP 原因短语" to httpFailure?.statusMessage.orEmpty().ifBlank { "未提供" }.take(120),
                        "响应 Content-Type" to httpFailure?.contentType.orEmpty().ifBlank { "未提供" },
                        "响应 Content-Length" to (httpFailure?.contentLength?.toString() ?: "未知"),
                        "响应 Retry-After" to httpFailure?.retryAfter.orEmpty().ifBlank { "未提供" },
                        "响应 Server" to httpFailure?.server.orEmpty().ifBlank { "未提供" },
                        "MP3 最低有效字节数" to MIN_MP3_BYTES.toString(),
                        "异常类型" to e.javaClass.name,
                        "异常摘要" to (e.message ?: "无异常消息").take(320),
                        "失败分类" to when {
                            e.message.orEmpty().contains("HTTP 401") || e.message.orEmpty().contains("HTTP 403") -> "HTTP 鉴权/访问拒绝"
                            e.message.orEmpty().contains("HTTP 429") -> "HTTP 限流"
                            e.message.orEmpty().contains("HTTP 5") -> "HTTP 服务端错误"
                            e.message.orEmpty().contains("过短") -> "响应音频为空或过短"
                            e.message.orEmpty().contains("解码") || stage.contains("解码") -> "MP3 音频解码"
                            else -> "网络请求或音频处理"
                        },
                        "是否继续备用端点" to willTryNext.toString(),
                        "重试策略" to if (willTryNext) "切换到下一 Google TTS 端点" else "全部端点已尝试",
                    ),
                )
                android.util.Log.w(TAG, "Google TTS endpoint failed: ${e.message}")
            }
        }
        val finalFailure = IllegalStateException(
            "谷歌在线语音请求或解码失败（${lastError?.message ?: "网络不可达"}），请检查网络或切换其他配音引擎",
            lastError,
        )
        AppLogStore.error(
            appCtx,
            "GoogleTTS",
            "Google 在线 TTS 所有备用端点均失败",
            finalFailure,
            diagnosticContext = mapOf(
                "服务商" to "Google Translate TTS",
                "候选端点总数" to urls.size.toString(),
                "已尝试端点主机" to urls.mapNotNull { runCatching { android.net.Uri.parse(it).host }.getOrNull() }.distinct().joinToString(", "),
                "当前文本字符数" to text.length.toString(),
                "当前字幕正文是否写入日志" to "否",
                "请求语速" to normalizedSpeed.toString(),
                "最后异常类型" to (lastError?.javaClass?.name ?: "未知"),
                "最后异常摘要" to (lastError?.message ?: "未提供").take(320),
                "恢复建议" to "检查网络可达性、Google TTS 接口返回状态及 MP3 解码器；也可切换其他配音引擎",
            ),
        )
        throw finalFailure
    }

    private fun httpGet(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GOOGLE_TTS_UA)
            .header("Referer", "https://translate.google.com/")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw GoogleTtsHttpException(
                    statusCode = resp.code,
                    statusMessage = resp.message,
                    contentType = resp.header("Content-Type"),
                    contentLength = resp.body?.contentLength()?.takeIf { it >= 0L },
                    retryAfter = resp.header("Retry-After"),
                    server = resp.header("Server"),
                )
            }
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (body.size < MIN_MP3_BYTES) throw IllegalStateException("响应音频过短：${body.size} 字节")
            return body
        }
    }

    private class GoogleTtsHttpException(
        val statusCode: Int,
        val statusMessage: String,
        val contentType: String?,
        val contentLength: Long?,
        val retryAfter: String?,
        val server: String?,
    ) : IllegalStateException("HTTP $statusCode${statusMessage.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")

    private fun decodeMp3(bytes: ByteArray, checkActive: () -> Unit): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IllegalStateException("谷歌在线语音响应不是有效音频")
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("谷歌在线语音未返回音频格式")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<FloatArray>(4)
            var totalSamples = 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1).coerceAtLeast(1)
            var inputEos = false
            var outputEos = false
            val info = MediaCodec.BufferInfo()
            var loops = 0

            while (!outputEos && loops++ < 10_000) {
                if (loops % 16 == 0) {
                    checkOpen()
                    checkActive()
                }
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("谷歌在线语音解码器输入缓冲区不可用")
                        val sampleSize = extractor.sampleSize
                        if (sampleSize <= 0L) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        } else {
                            input.clear()
                            val read = extractor.readSampleData(input, 0)
                            if (read <= 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Keep feeding frames without waiting on output; only block once all input is queued.
                val outputIndex = codec.dequeueOutputBuffer(info, if (inputEos) 10_000L else 0L)
                when {
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.order(ByteOrder.LITTLE_ENDIAN)
                            val shortCount = info.size / 2
                            val frameCount = shortCount / channelCount
                            if (frameCount > 0) {
                                val samples = FloatArray(frameCount)
                                for (frame in 0 until frameCount) {
                                    var sum = 0f
                                    for (ch in 0 until channelCount) {
                                        sum += output.getShort(info.offset + (frame * channelCount + ch) * 2) / 32768f
                                    }
                                    samples[frame] = (sum / channelCount).coerceIn(-1f, 1f)
                                }
                                chunks.add(samples)
                                totalSamples += samples.size
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = runCatching { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channelCount).coerceAtLeast(1)
                    }
                }
            }
            if (!outputEos) {
                throw IllegalStateException("谷歌在线语音解码超时，未取得完整音频")
            }
            if (totalSamples == 0) {
                throw IllegalStateException("谷歌在线语音解码结果为空")
            }
            val merged = FloatArray(totalSamples)
            var cursor = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, merged, cursor, chunk.size)
                cursor += chunk.size
            }
            return merged to sampleRate
        } finally {
            runCatching { extractor.release() }
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }
    }

    private fun resampleTo(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate
        val outLen = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val pos = i * ratio
            val low = pos.toInt().coerceAtMost(samples.size - 1)
            val high = (low + 1).coerceAtMost(samples.size - 1)
            val frac = (pos - low).toFloat()
            out[i] = samples[low] * (1f - frac) + samples[high] * frac
        }
        return out
    }

    private companion object {
        const val TAG = "GoogleTts"
        const val GOOGLE_TTS_UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val TARGET_RATE_HZ = 24_000
        const val MAX_QUERY_CHARS = 160
        const val SOFT_CHUNK_CHARS = 85
        const val MIN_MP3_BYTES = 128
    }
}
