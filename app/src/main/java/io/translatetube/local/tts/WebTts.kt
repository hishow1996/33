package io.translatetube.local.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 在线语音引擎（无需模型、无需 API Key）：调用有道/百度网页端朗读接口拿 MP3，
 * MediaCodec 解码为 PCM FloatArray 供 DubbingEngine 按时间轴播放。
 *
 * 双源互备：主源有道（24kHz，音质较好），失败自动切百度（16kHz），两者都失败才抛出
 * ——上层现有的 fallback 链（请求备用引擎/回落原声）继续兜底。
 * 两源采样率不同：解码后统一线性重采样到 24kHz，保证同一句内多段拼接无音调跳变。
 *
 * 为什么它治"断断续续"：合成发生在服务端，一句话亚秒级返回（本地模型要念多久就合成多久），
 * 预合成永远追得上播放时间轴，行间空窗消失。
 */
internal class WebTts : TtsEngine {

    override val name: String = "在线语音"
    override val cacheKey: String = "web-v1"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        val spoken = text.trim()
        if (spoken.isBlank()) return TtsAudio(FloatArray(0), TARGET_RATE_HZ)
        TtsTextPolicy.validate(spoken)
        val chunks = splitForRequest(spoken)
        var samples = FloatArray(0)
        var rate = TARGET_RATE_HZ
        for (chunk in chunks) {
            val mp3 = fetchWithFallback(chunk)
            val decoded = decodeMp3(mp3)
            if (decoded.second <= 0 || decoded.first.isEmpty()) continue
            if (samples.isEmpty()) {
                samples = decoded.first
                rate = decoded.second
            } else if (decoded.second == rate) {
                samples += decoded.first
            } else {
                // 相邻段采样率不同：把新段重采样到已确定的基准率再拼。
                samples += resampleTo(decoded.first, decoded.second, rate)
            }
        }
        if (samples.isEmpty()) throw IllegalStateException("在线语音返回了空音频，请稍后重试或切换本地引擎")
        if (rate != TARGET_RATE_HZ) {
            samples = resampleTo(samples, rate, TARGET_RATE_HZ)
            rate = TARGET_RATE_HZ
        }
        return TtsAudio(samples, rate)
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }

    /** 单次请求按 UTF-8 字节预算切句，优先在句读处断开，拼接后的 PCM 在时间轴上无缝。 */
    private fun splitForRequest(text: String): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_QUERY_BYTES) return listOf(text)
        val breakers = setOf('。', '！', '？', '；', '，', '、', ' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        var currentBytes = 0
        for (ch in text) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (currentBytes + chBytes > MAX_QUERY_BYTES && current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
                currentBytes = 0
            }
            current.append(ch)
            currentBytes += chBytes
            if (ch in breakers && currentBytes >= SOFT_CHUNK_BYTES) {
                out.add(current.toString())
                current = StringBuilder()
                currentBytes = 0
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(64)) }
    }

    /** 主源有道失败（网络/空响应/非 200）时自动切百度，双源都失败抛异常走上层 fallback。 */
    private fun fetchWithFallback(text: String): ByteArray {
        val youdaoMp3 = runCatching { fetchYoudao(text) }
            .onFailure { android.util.Log.w(TAG_WEB_TTS, "youdao tts failed: ${it.message}") }
            .getOrNull()
        if (youdaoMp3 != null) return youdaoMp3
        val baiduMp3 = runCatching { fetchBaidu(text) }
            .onFailure { android.util.Log.w(TAG_WEB_TTS, "baidu tts failed: ${it.message}") }
            .getOrNull()
        if (baiduMp3 != null) return baiduMp3
        throw IllegalStateException("在线语音两个来源都不可达，请检查网络后重试，或在设置中切换本地引擎")
    }

    private fun fetchYoudao(text: String): ByteArray {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        return httpGet(
            "https://dict.youdao.com/dictvoice?type=2&le=zh&audio=$encoded",
            referer = "https://dict.youdao.com/",
        )
    }

    private fun fetchBaidu(text: String): ByteArray {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        return httpGet(
            "https://fanyi.baidu.com/gettts?lan=zh&text=$encoded&spd=5&source=web",
            referer = "https://fanyi.baidu.com/",
        )
    }

    private fun httpGet(url: String, referer: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body?.bytes().orEmpty()
            if (body.size < MIN_MP3_BYTES) throw IllegalStateException("响应过短：${body.size} 字节")
            return body
        }
    }

    /** MediaCodec 同步模式解码 MP3 → 16bit PCM → FloatArray（-1..1）与采样率。 */
    private fun decodeMp3(bytes: ByteArray): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(bytes, 0, bytes.size)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IllegalStateException("在线语音响应不是有效音频")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ArrayList<FloatArray>(4)
            var totalFrames = 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            var loops = 0
            while (!sawOutputEos && loops < 10_000) {
                loops++
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
                        val chunk = if (extractor.sampleSize > input.capacity()) input.capacity() else extractor.sampleSize
                        if (chunk <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            input.clear()
                            extractor.readSampleData(input, 0)
                            codec.queueInputBuffer(inIndex, 0, chunk, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex >= 0 -> {
                        val pcmBuffer = codec.getOutputBuffer(outIndex)!!
                        pcmBuffer.order(PCM_BYTE_ORDER)
                        val shorts = ShortArray(bufferInfo.size / 2)
                        for (i in shorts.indices) shorts[i] = pcmBuffer.getShort(i * 2)
                        val floats = FloatArray(shorts.size) { shorts[it] / 32768f }
                        out.add(floats)
                        totalFrames += floats.size
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            if (totalFrames == 0) throw IllegalStateException("在线语音解码结果为空")
            val merged = FloatArray(totalFrames)
            var cursor = 0
            for (part in out) {
                System.arraycopy(part, 0, merged, cursor, part.size)
                cursor += part.size
            }
            return merged to sampleRate
        } finally {
            runCatching { extractor.release() }
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
        }
    }

    /** 线性插值重采样（TTS 语音对插值失真不敏感），统一采样率用于段间无缝拼接。 */
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
        const val TAG_WEB_TTS = "WebTts"
        const val WEB_UA = "Mozilla/5.0 (Linux; Android 9; COR-AL10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        const val TARGET_RATE_HZ = 24_000
        const val MAX_QUERY_BYTES = 1_600
        const val SOFT_CHUNK_BYTES = 900
        const val MIN_MP3_BYTES = 256
        val PCM_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN
    }
}
