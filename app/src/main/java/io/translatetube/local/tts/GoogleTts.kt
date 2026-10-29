package io.translatetube.local.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
internal class GoogleTts : TtsEngine {

    override val name: String = "谷歌在线语音"
    override val cacheKey: String = "google-tts-v1"

    private val closed = AtomicBoolean(false)

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
        val chunks = splitForRequest(spoken)
        var samples = FloatArray(0)
        var rate = TARGET_RATE_HZ
        for (chunk in chunks) {
            checkOpen()
            checkActive()
            val mp3 = fetchWithFallback(chunk)
            checkOpen()
            checkActive()
            val decoded = decodeMp3(mp3, checkActive)
            if (decoded.second <= 0 || decoded.first.isEmpty()) continue
            if (samples.isEmpty()) {
                samples = decoded.first
                rate = decoded.second
            } else if (decoded.second == rate) {
                samples += decoded.first
            } else {
                samples += resampleTo(decoded.first, decoded.second, rate)
            }
        }
        if (samples.isEmpty()) {
            throw IllegalStateException("谷歌在线语音未返回有效音频，请检查网络连接或切换其他配音引擎")
        }
        if (rate != TARGET_RATE_HZ) {
            samples = resampleTo(samples, rate, TARGET_RATE_HZ)
            rate = TARGET_RATE_HZ
        }
        return TtsAudio(samples, rate)
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

    private fun fetchWithFallback(text: String): ByteArray {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urls = listOf(
            "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&ttsspeed=1&q=$encoded",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=zh-CN&ttsspeed=1&q=$encoded",
            "https://translate.google.com/translate_tts?ie=UTF-8&client=dict-chrome-ex&tl=zh-CN&q=$encoded",
        )
        var lastError: Throwable? = null
        for (url in urls) {
            checkOpen()
            try {
                return httpGet(url)
            } catch (e: Throwable) {
                lastError = e
                android.util.Log.w(TAG, "Google TTS endpoint failed: ${e.message}")
            }
        }
        throw IllegalStateException(
            "谷歌在线语音连接失败（${lastError?.message ?: "网络不可达"}），请检查网络或切换其他配音引擎",
            lastError,
        )
    }

    private fun httpGet(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GOOGLE_TTS_UA)
            .header("Referer", "https://translate.google.com/")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (body.size < MIN_MP3_BYTES) throw IllegalStateException("响应音频过短：${body.size} 字节")
            return body
        }
    }

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

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
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
