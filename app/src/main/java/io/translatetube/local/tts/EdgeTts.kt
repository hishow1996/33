package io.translatetube.local.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Microsoft Edge Read Aloud online neural TTS; distinct from System TTS and Youdao/Baidu. */
internal class EdgeTts : TtsEngine {
    override val name = "微软 Edge TTS"
    private val closed = AtomicBoolean(false)
    private val activeSockets = CopyOnWriteArraySet<WebSocket>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override val cacheKey: String get() = "$name|edge-readaloud-v2|zh-neural"

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text, speed, speakerId) {}

    override fun synthCancellable(
        text: String,
        speed: Float,
        speakerId: Int,
        checkActive: () -> Unit,
    ): TtsAudio = synthInternal(text, speed, speakerId, checkActive)

    private fun synthInternal(
        text: String,
        speed: Float,
        speakerId: Int,
        checkActive: () -> Unit,
    ): TtsAudio {
        checkOpen()
        checkActive()
        // 控制符、表情等字符会让服务端直接返回 0 字节音频，发送前先清掉。
        val spoken = TtsTextPolicy.sanitizeForSpeech(text)
        if (spoken.isBlank() || !TtsTextPolicy.hasSpeakableContent(spoken)) {
            return TtsAudio(FloatArray(0), TARGET_RATE_HZ)
        }
        TtsTextPolicy.validate(spoken)
        val voice = VOICES[Math.floorMod(if (speakerId < 0) 0 else speakerId, VOICES.size)]
        val rate = (((speed.takeIf { it.isFinite() } ?: 1.0f) - 1.0f) * 100f)
            .roundToInt().coerceIn(-50, 100)
        val decodedParts = ArrayList<FloatArray>()
        for (part in splitForRequest(spoken)) {
            checkOpen()
            checkActive()
            val decoded = synthesizePart(part, voice, rate, checkActive)
            if (decoded.isNotEmpty()) decodedParts += decoded
        }
        if (decodedParts.isEmpty()) {
            throw IllegalStateException("微软 Edge TTS 没有返回可解码的音频，请检查网络后重试")
        }
        val total = decodedParts.sumOf { it.size.toLong() }
        require(total in 1..MAX_OUTPUT_SAMPLES) { "微软 Edge TTS 返回的音频长度超出安全范围" }
        val merged = FloatArray(total.toInt())
        var cursor = 0
        decodedParts.forEach { System.arraycopy(it, 0, merged, cursor, it.size); cursor += it.size }
        return TtsAudio(merged, TARGET_RATE_HZ)
    }

    /**
     * 合成一段文本。服务端偶尔对同一段文本返回 0 字节音频（限流、特殊内容）：先用新连接原样重试，
     * 仍然拿不到就按句读切成小段逐段合成，只有"确实没有可念内容"的小段才允许静音跳过。
     */
    private fun synthesizePart(
        part: String,
        voice: String,
        ratePercent: Int,
        checkActive: () -> Unit,
    ): FloatArray {
        var lastEmpty: EmptyAudioException? = null
        for (attempt in 0 until EMPTY_AUDIO_ATTEMPTS) {
            checkOpen()
            checkActive()
            try {
                val mp3 = synthesizeMp3(part, voice, ratePercent)
                checkOpen()
                checkActive()
                return decodeMp3(mp3).first
            } catch (empty: EmptyAudioException) {
                lastEmpty = empty
                if (attempt < EMPTY_AUDIO_ATTEMPTS - 1) sleepChecking(EMPTY_AUDIO_RETRY_DELAY_MS * (attempt + 1), checkActive)
            }
        }
        val pieces = TtsTextPolicy.splitIntoPieces(part)
        if (pieces.size > 1) {
            val decodedPieces = ArrayList<FloatArray>()
            for (piece in pieces) {
                if (!TtsTextPolicy.hasSpeakableContent(piece)) continue
                var pieceAudio: FloatArray? = null
                for (attempt in 0 until 2) {
                    checkOpen()
                    checkActive()
                    try {
                        val mp3 = synthesizeMp3(piece, voice, ratePercent)
                        checkOpen()
                        checkActive()
                        pieceAudio = decodeMp3(mp3).first
                        break
                    } catch (empty: EmptyAudioException) {
                        lastEmpty = empty
                        if (attempt == 0) sleepChecking(EMPTY_AUDIO_RETRY_DELAY_MS, checkActive)
                    }
                }
                val audio = pieceAudio ?: throw lastEmpty!!
                if (audio.isNotEmpty()) decodedPieces += audio
            }
            if (decodedPieces.isNotEmpty()) {
                val merged = FloatArray(decodedPieces.sumOf { it.size })
                var cursor = 0
                decodedPieces.forEach { System.arraycopy(it, 0, merged, cursor, it.size); cursor += it.size }
                return merged
            }
        }
        throw lastEmpty ?: IllegalStateException("微软 Edge TTS 没有返回可解码的音频，请检查网络后重试")
    }

    private fun sleepChecking(totalMs: Long, checkActive: () -> Unit) {
        var remaining = totalMs
        while (remaining > 0L) {
            checkActive()
            val slice = minOf(100L, remaining)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    /** 服务端正常结束请求，但没有给出足够的音频数据。 */
    private class EmptyAudioException(message: String) : IllegalArgumentException(message)

    private fun synthesizeMp3(text: String, voice: String, ratePercent: Int): ByteArray {
        checkOpen()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val securityToken = EdgeTtsSecurity.generateSecMsGec()
        val url = "$WSS_URL&ConnectionId=$connectionId&Sec-MS-GEC=$securityToken&Sec-MS-GEC-Version=$GEC_VERSION"
        val output = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", EDGE_ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Cookie", "muid=$connectionId;")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    failure.compareAndSet(null, IllegalStateException("微软 Edge TTS 已关闭"))
                    finished.countDown()
                    webSocket.cancel()
                    return
                }
                try {
                    val config = "X-Timestamp:${timestamp()}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    val ssml = """<?xml version="1.0" encoding="UTF-8"?><speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN"><voice name="${voice}"><prosody pitch="+0Hz" rate="${ratePercent}%" volume="+0%">${xmlEscape(text)}</prosody></voice></speak>"""
                    val ssmlMessage = "X-RequestId:${connectionId}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n${ssml}"
                    if (!webSocket.send(config) || !webSocket.send(ssmlMessage)) {
                        throw IllegalStateException("无法发送微软 Edge TTS 合成请求")
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    finished.countDown()
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val separator = text.indexOf("\r\n\r\n")
                val header = if (separator >= 0) text.substring(0, separator) else text
                val path = header.lineSequence().firstOrNull { it.startsWith("Path:", true) }
                    ?.substringAfter(':')?.trim()?.lowercase(Locale.ROOT)
                when (path) {
                    "turn.end" -> finished.countDown()
                    "response" -> {
                        val body = if (separator >= 0) text.substring(separator + 4) else ""
                        if (body.contains("Forbidden", true) || body.contains("Unauthorized", true)) {
                            failure.compareAndSet(null, IllegalStateException("微软 Edge TTS 服务拒绝了请求，可能需要更新协议"))
                            finished.countDown()
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val packet = bytes.toByteArray()
                    if (packet.size < 2) return
                    val headerLength = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
                    val audioOffset = 2 + headerLength
                    require(audioOffset in 2..packet.size) { "微软 Edge TTS 返回的音频包头无效" }
                    if (audioOffset < packet.size) synchronized(output) {
                        output.write(packet, audioOffset, packet.size - audioOffset)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    finished.countDown()
                    webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(
                    null,
                    IllegalStateException(
                        "微软 Edge TTS WebSocket 连接失败${response?.code?.let { "（HTTP $it）" } ?: ""}：${t.message ?: "未知网络错误"}",
                        t,
                    ),
                )
                finished.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finished.countDown()
            }
        })
        activeSockets.add(socket)
        try {
            if (!finished.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                socket.cancel()
                throw IllegalStateException("微软 Edge TTS 等待合成超时（${SYNTHESIS_TIMEOUT_SECONDS} 秒）")
            }
            failure.get()?.let { throw it }
            val mp3 = synchronized(output) { output.toByteArray() }
            if (mp3.size < MIN_MP3_BYTES) throw EmptyAudioException("微软 Edge TTS 返回音频过短（${mp3.size} 字节）")
            return mp3
        } finally {
            activeSockets.remove(socket)
            runCatching { socket.close(1000, "done") }
        }
    }

    private fun decodeMp3(bytes: ByteArray): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = candidate
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) throw IllegalStateException("微软 Edge TTS 返回的 MP3 无法识别")
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val parts = ArrayList<FloatArray>()
            var totalSamples = 0
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inputEos = false
            var outputEos = false
            val info = MediaCodec.BufferInfo()
            var loops = 0
            while (!outputEos && loops++ < MAX_DECODE_LOOPS) {
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: throw IllegalStateException("MP3 解码器输入缓冲区不可用")
                        val sampleSize = extractor.sampleSize
                        if (sampleSize <= 0L) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            require(sampleSize <= input.capacity().toLong()) { "Edge TTS 音频块超出解码缓冲区" }
                            input.clear()
                            val read = extractor.readSampleData(input, 0)
                            if (read <= 0) {
                                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
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
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val isFloat = runCatching {
                                codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            }.getOrDefault(false)
                            if (isFloat) {
                                val samples = FloatArray(output.remaining() / 4)
                                for (i in samples.indices) samples[i] = output.getFloat().coerceIn(-1f, 1f)
                                parts += samples
                                totalSamples += samples.size
                            } else {
                                val samples = FloatArray(output.remaining() / 2)
                                for (i in samples.indices) samples[i] = output.short / 32768f
                                parts += samples
                                totalSamples += samples.size
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            require(totalSamples > 0) { "微软 Edge TTS 音频解码结果为空" }
            val merged = FloatArray(totalSamples)
            var cursor = 0
            parts.forEach { System.arraycopy(it, 0, merged, cursor, it.size); cursor += it.size }
            return resampleTo(merged, sampleRate, TARGET_RATE_HZ) to TARGET_RATE_HZ
        } finally {
            runCatching { extractor.release() }
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }

    private fun resampleTo(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (samples.isEmpty() || fromRate == toRate) return samples
        val length = (samples.size.toDouble() * toRate / fromRate).toInt().coerceAtLeast(1)
        return FloatArray(length) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val low = pos.toInt().coerceIn(0, samples.lastIndex)
            val high = (low + 1).coerceAtMost(samples.lastIndex)
            val fraction = (pos - low).toFloat()
            samples[low] * (1f - fraction) + samples[high] * fraction
        }
    }

    private fun splitForRequest(text: String): List<String> {
        val output = ArrayList<String>()
        val current = StringBuilder()
        var bytes = 0
        for (ch in text) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_REQUEST_BYTES && current.isNotEmpty()) {
                output += current.toString()
                current.setLength(0)
                bytes = 0
            }
            current.append(ch)
            bytes += size
            if (bytes >= SOFT_REQUEST_BYTES && ch in SENTENCE_BREAKS) {
                output += current.toString()
                current.setLength(0)
                bytes = 0
            }
        }
        if (current.isNotEmpty()) output += current.toString()
        return output.filter { it.isNotBlank() }
    }

    private fun checkOpen() = check(!closed.get()) { "微软 Edge TTS 引擎已关闭" }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSockets.forEach { runCatching { it.cancel() } }
        activeSockets.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun timestamp(): String = SimpleDateFormat(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
        .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

    private companion object {
        const val TARGET_RATE_HZ = 24_000
        const val MAX_REQUEST_BYTES = 3_500
        const val SOFT_REQUEST_BYTES = 1_700
        const val MIN_MP3_BYTES = 256
        const val EMPTY_AUDIO_ATTEMPTS = 3
        const val EMPTY_AUDIO_RETRY_DELAY_MS = 400L
        const val SYNTHESIS_TIMEOUT_SECONDS = 45L
        const val MAX_DECODE_LOOPS = 10_000
        const val MAX_OUTPUT_SAMPLES = 24_000L * 90L
        const val GEC_VERSION = "2-143.0.3650.75"
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.3650.75"
        val VOICES = arrayOf(
            "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-XiaoyiNeural",
            "zh-CN-YunyangNeural", "zh-CN-XiaohanNeural", "zh-CN-XiaomengNeural",
        )
        val SENTENCE_BREAKS = setOf('。', '！', '？', '；', '，', '、', ' ', '\n')
    }
}

/** Current Edge Read Aloud v2 Sec-MS-GEC generator, ported from edge-tts 7.3.1. */
internal object EdgeTtsSecurity {
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
    private const val TICKS_PER_SECOND = 10_000_000L
    private val SHA_INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85L.toInt(), 0x3C6EF372, 0xA54FF53AL.toInt(),
        0x510E527F, 0x9B05688CL.toInt(), 0x1F83D9ABL.toInt(), 0x5BE0CD19,
    )
    private val SHA_STATE = intArrayOf(
        0xFD9FBEA2L.toInt(), 0x91243CE7L.toInt(), 0xBE499094L.toInt(), 0xCD2E1E0AL.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4L.toInt(), 0x03EFFBD2,
    )
    private val SHA_SCHEDULE = intArrayOf(
        0xEB24BF9EL.toInt(), 0x1558691F, 0xA0C09EE2L.toInt(), 0x0CFA32BA,
        0xA622EE8CL.toInt(), 0xD1DD9491L.toInt(), 0xC0DD25A0L.toInt(), 0xF9E70AB6L.toInt(),
        0xF0C31573L.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060L.toInt(), 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,
        0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,
        0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,
        0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,
        0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,
        0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,
        0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,
        0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L,
    ).map { it.toInt() }.toIntArray()
    private val CS_COEFFICIENTS = intArrayOf(
        0xE82E91B1L.toInt(), 0x44DE24C6, 0x3BEB65BC, 0xC89C5933L.toInt(),
        0x8D1EC211L.toInt(), 0x314EF5D5, 0xE92491B1L.toInt(), 0xF0A5B9D5L.toInt(),
        0x06064CF3, 0xDA79FA60L.toInt(), 0xBED20560L.toInt(), 0x89F9C9F8L.toInt(),
        0x35FA38C6, 0xCB49E3C6L.toInt(),
    )
    private val CS_OFFSETS = intArrayOf(
        0xB32700D0L.toInt(), 0x88DFF51DL.toInt(), 0x29514038, 0xB328CBDFL.toInt(),
        0xD405B4D5L.toInt(), 0x26E55F3C, 0x607801C6, 0x82B2C1FBL.toInt(),
        0xFA51A3DAL.toInt(), 0x207CC464, 0x3C3C74F3, 0xD2D14B61L.toInt(),
        0xBB04A2C1L.toInt(), 0xC5626167L.toInt(),
    )
    private const val CS_FACTOR = 0x82893933
    private const val CS_ADJUSTMENT_0 = 0xF2F90B1A
    private const val CS_ADJUSTMENT_1 = 0x0B1A0000

    fun generateSecMsGec(nowMs: Long = System.currentTimeMillis()): String {
        // Match edge-tts upstream exactly: use floating-point Unix seconds for the Windows
        // epoch conversion, round down to the 5-minute bucket, then format 100-ns ticks with
        // zero decimal places. Integer-second multiplication differs for some buckets and can
        // produce a token that the current Microsoft service rejects while ending with no audio.
        var ticks = nowMs.toDouble() / 1_000.0 + WINDOWS_EPOCH_SECONDS.toDouble()
        ticks -= ticks % 300.0
        ticks *= TICKS_PER_SECOND.toDouble()
        val tickString = String.format(Locale.US, "%.0f", ticks)
        val data = (tickString + TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = shaDigest(data)
        val cs = csDigest(data, digest)
        val prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(cs.first).putInt(cs.second).array()
        return hex(prefix) + "-" + hex(digest)
    }

    private fun rol16(x: Int): Int = (x shl 16) or (x ushr 16)
    private fun ror(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
    private fun sigma0(x: Int): Int = ror(x, 7) xor ror(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int): Int = ror(x, 17) xor ror(x, 19) xor (x ushr 10)

    private fun expand(w: IntArray, from: Int) {
        for (i in from until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(init: IntArray, w: IntArray, start: Int): IntArray {
        var a=init[0]; var b=init[1]; var c=init[2]; var d=init[3]
        var e=init[4]; var f=init[5]; var g=init[6]; var h=init[7]
        val constants = if (start == 14) K.copyOf().also { it[14] = 0xC9DC06A7L.toInt() } else K
        for (i in start until 64) {
            val s1=ror(e,6) xor ror(e,11) xor ror(e,25)
            val ch=(e and f) xor (e.inv() and g)
            val t1=h+s1+ch+constants[i]+w[i]
            val s0=ror(a,2) xor ror(a,13) xor ror(a,22)
            val maj=(a and b) xor (a and c) xor (b and c)
            val t2=s0+maj
            h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2
        }
        return intArrayOf(a,b,c,d,e,f,g,h)
    }

    private fun initState(firstSeven: ByteArray): IntArray {
        val w=IntArray(64)
        w[14]=((firstSeven[0].toInt() and 255) shl 16) or ((firstSeven[1].toInt() and 255) shl 8) or (firstSeven[2].toInt() and 255)
        w[15]=((firstSeven[3].toInt() and 255) shl 24) or ((firstSeven[4].toInt() and 255) shl 16) or ((firstSeven[5].toInt() and 255) shl 8) or (firstSeven[6].toInt() and 255)
        for(i in 16..29) {
            var v=SHA_SCHEDULE[i-16]
            if(i-2>=14) v += if(i==16) sigma1(w[i-2]) xor 0x000B92C0 else sigma1(w[i-2])
            if(i-7>=14) v += w[i-7]
            if(i-15>=14) v += if(i==29) sigma0(w[i-15]) xor 0x059C0B80 else sigma0(w[i-15])
            w[i]=v
        }
        w[30]=sigma1(w[28])+w[23]+sigma0(w[15])+w[14]+0xC9DC06A7L.toInt()-K[14]
        expand(w,31)
        val result=rounds(SHA_STATE,w,14)
        return IntArray(8) { result[it]+SHA_INITIAL[it] }
    }

    private fun shaDigest(data: ByteArray): ByteArray {
        val length=57+data.size
        val tail=ByteArrayOutputStream()
        tail.write(data,7,data.size-7)
        tail.write(0x80)
        repeat(((55-length)%64+64)%64) { tail.write(0) }
        tail.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(length.toLong()*8L).array())
        val input=tail.toByteArray()
        var state=initState(data.copyOfRange(0,7))
        var off=0
        while(off<input.size) {
            val w=IntArray(64)
            for(i in 0 until 16) {
                val j=off+i*4
                w[i]=((input[j].toInt() and 255) shl 24) or ((input[j+1].toInt() and 255) shl 16) or ((input[j+2].toInt() and 255) shl 8) or (input[j+3].toInt() and 255)
            }
            expand(w,16)
            val next=rounds(state,w,0)
            state=IntArray(8) { state[it]+next[it] }
            off+=64
        }
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply { state.forEach { putInt(it) } }.array()
    }

    private fun csDigest(data: ByteArray,digest: ByteArray): Pair<Int,Int> {
        val d=ByteBuffer.wrap(digest,0,8).order(ByteOrder.LITTLE_ENDIAN)
        val keyA=d.int or 1; val keyB=d.int or 1
        val factorA=keyA*CS_FACTOR; val factorB=keyB*CS_FACTOR
        val terms=ArrayList<Int>(); val offsets=ArrayList<Int>()
        for(i in CS_COEFFICIENTS.indices) {
            terms.add((CS_COEFFICIENTS[i] * (if (i % 2 == 0) factorA else factorB)).toInt())
            offsets.add(CS_OFFSETS[i])
        }
        val padded=ByteArray(data.size+1)
        System.arraycopy(data,0,padded,1,data.size)
        val aligned=padded.copyOf(((padded.size+3)/4)*4)
        val words=ArrayList<Int>()
        for(i in aligned.indices step 4) {
            words += (aligned[i].toInt() and 255) or ((aligned[i+1].toInt() and 255) shl 8) or ((aligned[i+2].toInt() and 255) shl 16) or ((aligned[i+3].toInt() and 255) shl 24)
        }
        if(words.size%2!=0) words+=0
        words.forEachIndexed { i, word ->
            var term=word * if(i%2==0) keyA else keyB
            var off=rol16(word) * if(i%2==0) 0xEF0569FBL.toInt() else 0xC31713DBL.toInt()
            if (i == 0) { term += (CS_ADJUSTMENT_0 * factorA).toInt(); off += CS_ADJUSTMENT_1 }
            terms.add(term); offsets.add(off)
        }
        var swapAcc=0; var swapLast=0; var state=0; var acc=0
        for(i in terms.indices step 2) {
            val x=terms[i]; val y=terms[i+1]
            var t=rol16(offsets[i]+x); var q=t*0x76F50F34
            swapAcc += rol16(q)*0xC3EFEA97L.toInt()+q
            t=rol16(offsets[i+1]+y); q=t*0x1881CE3C
            swapLast=rol16(q)*0x35BD1EC9L.toInt()+q
            swapAcc += swapLast
            state=state*keyA+x
            state=rol16(state)*0xCF98B111L.toInt()
            state=rol16(state)*0x87085B9FL.toInt()
            state=rol16(state)*0x12CEB96DL.toInt()
            state=rol16(state)*0x257E1D83
            acc+=state
            t=state*keyB+y
            t=rol16(t)*0xA27416F5L.toInt()
            t=rol16(t)*0xD38396FFL.toInt()
            state=rol16(rol16(t)*0x7C932B89L.toInt())*0xBFA49F69L.toInt()
            acc+=state
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size*2) {
        bytes.forEach { append(String.format(Locale.US,"%02X",it.toInt() and 255)) }
    }
}
