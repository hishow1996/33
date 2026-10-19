package io.translatetube.local.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
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
    override val cacheKey: String = "web-v2"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text) {}

    override fun synthCancellable(
        text: String,
        speed: Float,
        speakerId: Int,
        checkActive: () -> Unit,
    ): TtsAudio = synthInternal(text, checkActive)

    private fun synthInternal(text: String, checkActive: () -> Unit): TtsAudio {
        checkActive()
        val spoken = text.trim()
        if (spoken.isBlank()) return TtsAudio(FloatArray(0), TARGET_RATE_HZ)
        TtsTextPolicy.validate(spoken)

        val chunks = WebTtsTextChunkPolicy.split(spoken)
        val audioChunks = ArrayList<FloatArray>(chunks.size)
        var totalSamples = 0

        for (chunk in chunks) {
            checkActive()
            val (decodedSamples, sourceRate) = fetchAndDecodeWithFallback(chunk)
            checkActive()
            if (sourceRate !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
                throw IllegalStateException("在线语音返回了不支持的采样率：${sourceRate}Hz")
            }
            if (decodedSamples.isEmpty()) continue

            // Normalize each provider's output before joining. Otherwise a Baidu-first response
            // could force higher-rate Youdao chunks down to 16 kHz before the final upsample.
            val normalized = if (sourceRate == TARGET_RATE_HZ) {
                decodedSamples
            } else {
                resampleTo(decodedSamples, sourceRate, TARGET_RATE_HZ)
            }
            if (normalized.isNotEmpty()) {
                audioChunks.add(normalized)
                totalSamples = Math.addExact(totalSamples, normalized.size)
            }
        }

        if (totalSamples == 0) {
            throw IllegalStateException("在线语音返回了空音频，请稍后重试或切换本地引擎")
        }

        // Merge once rather than repeatedly copying the growing array for every sentence chunk.
        val merged = FloatArray(totalSamples)
        var cursor = 0
        for (part in audioChunks) {
            checkActive()
            System.arraycopy(part, 0, merged, cursor, part.size)
            cursor += part.size
        }
        return TtsAudio(merged, TARGET_RATE_HZ)
    }

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    /**
     * Fall back on both transport and decode errors. Web translation sites sometimes return
     * HTML/challenge bodies with HTTP 200, so an HTTP-success check alone is not sufficient.
     */
    private fun fetchAndDecodeWithFallback(text: String): Pair<FloatArray, Int> {
        val sources: List<Pair<String, (String) -> ByteArray>> = listOf(
            "youdao" to ::fetchYoudao,
            "baidu" to ::fetchBaidu,
        )
        var lastError: Exception? = null
        for ((provider, fetch) in sources) {
            try {
                val bytes = fetch(text)
                val decoded = decodeMp3(bytes)
                if (decoded.first.isEmpty() || decoded.second !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
                    throw IllegalStateException("音频解码结果无效：rate=${decoded.second}, samples=${decoded.first.size}")
                }
                return decoded
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                lastError = error
                android.util.Log.w(TAG_WEB_TTS, "$provider tts failed: ${error.message}")
            }
        }
        throw IllegalStateException(
            "在线语音两个来源都不可用，请检查网络后重试，或在设置中切换本地引擎",
            lastError,
        )
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
            val body = resp.body ?: throw IllegalStateException("响应缺少音频内容")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_AUDIO_RESPONSE_BYTES) {
                throw IllegalStateException("音频响应超过安全上限：${declaredLength} 字节")
            }
            val initialCapacity = if (declaredLength in 1..MAX_AUDIO_RESPONSE_BYTES.toLong()) {
                declaredLength.toInt()
            } else {
                8_192
            }
            val bytes = ByteArrayOutputStream(initialCapacity).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_AUDIO_RESPONSE_BYTES) {
                            throw IllegalStateException("音频响应超过安全上限：${total} 字节")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
            if (bytes.size < MIN_MP3_BYTES) {
                throw IllegalStateException("响应过短：${bytes.size} 字节")
            }
            return bytes
        }
    }

    /** MediaCodec 解码 MP3，并将输出统一转换成单声道 FloatArray。 */
    private fun decodeMp3(bytes: ByteArray): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val dataSource = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0L || position >= bytes.size.toLong()) return -1
                val length = minOf(size, bytes.size - position.toInt())
                if (length <= 0) return -1
                System.arraycopy(bytes, position.toInt(), buffer, offset, length)
                return length
            }

            override fun getSize(): Long = bytes.size.toLong()
            override fun close() = Unit
        }
        try {
            extractor.setDataSource(dataSource)
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
                throw IllegalStateException("在线语音响应不是有效音频")
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("音频响应缺少 MIME 类型")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outputChunks = ArrayList<FloatArray>(8)
            var totalSamples = 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, MAX_CHANNELS)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            var loops = 0

            while (!sawOutputEos && loops < MAX_DECODE_LOOPS) {
                loops++
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_WAIT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("解码器输入缓冲区不可用")
                        val sampleSize = extractor.sampleSize
                        if (sampleSize < 0L) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            if (sampleSize > inputBuffer.capacity().toLong()) {
                                throw IllegalStateException("音频数据包超过解码器输入缓冲区")
                            }
                            inputBuffer.clear()
                            val readSize = extractor.readSampleData(inputBuffer, 0)
                            if (readSize <= 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, readSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_WAIT_US)
                when {
                    outputIndex >= 0 -> {
                        try {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (bufferInfo.size > 0 && outputBuffer != null) {
                                val outputFormat = codec.outputFormat
                                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    .coerceIn(1, MAX_CHANNELS)
                                pcmEncoding = runCatching {
                                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)

                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> throw IllegalStateException(
                                        "解码器返回了不支持的 PCM 格式：${pcmEncoding}",
                                    )
                                }
                                val frameBytes = bytesPerSample * channels
                                val endOffset = bufferInfo.offset + bufferInfo.size
                                if (bufferInfo.offset < 0 || endOffset > outputBuffer.capacity()) {
                                    throw IllegalStateException("解码器返回了无效的 PCM 缓冲区范围")
                                }
                                outputBuffer.order(PCM_BYTE_ORDER)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(endOffset)
                                val frameCount = bufferInfo.size / frameBytes
                                val mono = FloatArray(frameCount)
                                for (frame in 0 until frameCount) {
                                    var mixed = 0f
                                    repeat(channels) {
                                        val value = when (pcmEncoding) {
                                            AudioFormat.ENCODING_PCM_FLOAT ->
                                                outputBuffer.float.coerceIn(-1f, 1f)
                                            AudioFormat.ENCODING_PCM_16BIT ->
                                                outputBuffer.short / 32768f
                                            AudioFormat.ENCODING_PCM_8BIT ->
                                                (((outputBuffer.get().toInt() and 0xff) - 128) / 128f)
                                            else -> 0f // Unreachable after validation above.
                                        }
                                        mixed += value
                                    }
                                    mono[frame] = (mixed / channels).coerceIn(-1f, 1f)
                                }
                                if (mono.isNotEmpty()) {
                                    outputChunks.add(mono)
                                    totalSamples = Math.addExact(totalSamples, mono.size)
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            .coerceIn(1, MAX_CHANNELS)
                        pcmEncoding = runCatching {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                    }
                }
            }

            if (!sawOutputEos) {
                throw IllegalStateException("在线语音音频解码超时，未读到结束标记")
            }
            if (totalSamples == 0) throw IllegalStateException("在线语音解码结果为空")
            if (sampleRate !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
                throw IllegalStateException("解码器返回了无效采样率：${sampleRate}Hz")
            }

            val merged = FloatArray(totalSamples)
            var cursor = 0
            for (part in outputChunks) {
                System.arraycopy(part, 0, merged, cursor, part.size)
                cursor += part.size
            }
            return merged to sampleRate
        } finally {
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }
    }

    /** 线性插值重采样（TTS 语音对插值失真不敏感），统一采样率用于段间无缝拼接。 */
    private fun resampleTo(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        require(fromRate in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ && toRate in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "无效的音频重采样率：$fromRate -> $toRate"
        }
        if (fromRate == toRate) return samples
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
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MAX_SAMPLE_RATE_HZ = 192_000
        const val MAX_CHANNELS = 8
        const val MAX_DECODE_LOOPS = 4_000
        const val MAX_AUDIO_RESPONSE_BYTES = 2 * 1024 * 1024
        const val CODEC_WAIT_US = 10_000L
        const val MIN_MP3_BYTES = 256
        val PCM_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN
    }
}
