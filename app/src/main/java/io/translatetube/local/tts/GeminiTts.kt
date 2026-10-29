package io.translatetube.local.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.translate.Crypto
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Gemini 原生语音合成引擎（Gemini TTS）：
 * 使用 Gemini 2.5 Flash / Pro Preview TTS 的 AUDIO 模态输出高自然度中文配音，
 * 自动复用应用中已配置的 Gemini API Key，并支持多角色音色切换。
 */
internal object GeminiTtsVoices {
    data class VoiceSpec(val id: String, val label: String)

    val ALL: List<VoiceSpec> = listOf(
        VoiceSpec("Kore", "Kore · 柔和女声"),
        VoiceSpec("Puck", "Puck · 活力男声"),
        VoiceSpec("Aoede", "Aoede · 自然女声"),
        VoiceSpec("Charon", "Charon · 沉稳男声"),
        VoiceSpec("Fenrir", "Fenrir · 浑厚男声"),
        VoiceSpec("Zephyr", "Zephyr · 轻快女声"),
        VoiceSpec("Leda", "Leda · 清亮女声"),
        VoiceSpec("Orus", "Orus · 清晰男声"),
    )

    const val DEFAULT_VOICE = "Kore"
    const val PREF_VOICE_KEY = "gemini_tts_voice"

    fun normalizeVoice(voiceName: String?): String {
        val trimmed = voiceName?.trim().orEmpty()
        return ALL.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.id ?: DEFAULT_VOICE
    }

    fun labelFor(voiceName: String?): String {
        val canonical = normalizeVoice(voiceName)
        return ALL.firstOrNull { it.id == canonical }?.label ?: "$canonical · 默认音色"
    }

    fun voiceForSpeaker(speakerId: Int, defaultVoice: String): String {
        val canonicalDefault = normalizeVoice(defaultVoice)
        if (speakerId < 0) return canonicalDefault
        return ALL[Math.floorMod(speakerId, ALL.size)].id
    }

    fun resolveApiKey(context: Context): String {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val freeEncrypted = prefs.getString("free_gemini_key", "").orEmpty().trim()
        if (freeEncrypted.isNotBlank()) {
            val decrypted = runCatching { Crypto.decrypt(freeEncrypted) }.getOrDefault("").trim()
            if (decrypted.isNotBlank()) return decrypted
        }
        val profileKey = runCatching {
            runBlocking {
                AppDatabase.get(appCtx).apiProfileDao().enabled()
                    .firstOrNull { it.type.equals("GEMINI", ignoreCase = true) && it.apiKey.isNotBlank() }
                    ?.apiKey
            }
        }.getOrNull().orEmpty().trim()
        if (profileKey.isNotBlank()) {
            val decrypted = runCatching { Crypto.decrypt(profileKey) }.getOrDefault("").trim()
            if (decrypted.isNotBlank()) return decrypted
        }
        return ""
    }
}

internal class GeminiTts(
    context: Context,
) : TtsEngine {

    private val appCtx = context.applicationContext
    override val name: String = "Gemini TTS"

    private val closed = AtomicBoolean(false)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun configuredDefaultVoice(): String {
        val prefs = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return GeminiTtsVoices.normalizeVoice(prefs.getString(GeminiTtsVoices.PREF_VOICE_KEY, GeminiTtsVoices.DEFAULT_VOICE))
    }

    override val cacheKey: String
        get() = "gemini-tts-v1-${configuredDefaultVoice()}"

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
        val apiKey = GeminiTtsVoices.resolveApiKey(appCtx)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Gemini TTS 需要 API Key，请在「设置 → 配音设置」或「API 管理」中填入 Google Gemini API Key")
        }
        val spoken = TtsTextPolicy.sanitizeForSpeech(text)
        if (spoken.isBlank() || !TtsTextPolicy.hasSpeakableContent(spoken)) {
            return TtsAudio(FloatArray(0), TARGET_RATE_HZ)
        }
        TtsTextPolicy.validate(spoken)
        val voiceName = GeminiTtsVoices.voiceForSpeaker(speakerId, configuredDefaultVoice())
        val chunks = splitForRequest(spoken)
        var samples = FloatArray(0)
        var rate = TARGET_RATE_HZ

        for (chunk in chunks) {
            checkOpen()
            checkActive()
            val (chunkSamples, chunkRate) = synthesizeChunk(apiKey, chunk, voiceName, checkActive)
            if (chunkRate <= 0 || chunkSamples.isEmpty()) continue
            if (samples.isEmpty()) {
                samples = chunkSamples
                rate = chunkRate
            } else if (chunkRate == rate) {
                samples += chunkSamples
            } else {
                samples += resampleTo(chunkSamples, chunkRate, rate)
            }
        }
        if (samples.isEmpty()) {
            throw IllegalStateException("Gemini TTS 未返回可播放的音频，请检查网络或 API Key 配额")
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
        if (closed.get()) throw java.util.concurrent.CancellationException("Gemini TTS 实例已关闭")
    }

    private fun splitForRequest(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val breakers = setOf('。', '！', '？', '；', '\n')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (current.length + 1 > MAX_CHUNK_CHARS && current.isNotEmpty()) {
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
        return out.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(MAX_CHUNK_CHARS)) }
    }

    private fun synthesizeChunk(
        apiKey: String,
        text: String,
        voiceName: String,
        checkActive: () -> Unit,
    ): Pair<FloatArray, Int> {
        var lastError: Throwable? = null
        for (model in CANDIDATE_MODELS) {
            checkOpen()
            checkActive()
            try {
                return callGeminiAudio(apiKey, model, text, voiceName, checkActive)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                lastError = e
                val msg = e.message.orEmpty()
                // Authentication or invalid key errors should fail fast without trying fallback models.
                if (msg.contains("API_KEY_INVALID", ignoreCase = true) ||
                    msg.contains("HTTP 401") ||
                    msg.contains("HTTP 403")
                ) {
                    break
                }
                android.util.Log.w(TAG, "Gemini TTS model $model failed: $msg")
            }
        }
        throw IllegalStateException(
            "Gemini TTS 合成失败：${lastError?.message ?: "未知错误"}",
            lastError,
        )
    }

    private fun callGeminiAudio(
        apiKey: String,
        model: String,
        text: String,
        voiceName: String,
        checkActive: () -> Unit,
    ): Pair<FloatArray, Int> {
        val isDedicatedTts = model.contains("tts", ignoreCase = true)
        val promptText = if (isDedicatedTts) {
            text
        } else {
            "请直接用自然流畅的中文朗读以下内容，只输出朗读语音，不要添加任何额外词语：\n$text"
        }

        val bodyJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", promptText)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName),
                            ),
                        ),
                    )
                },
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val responseText = client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errDetail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("").ifBlank { raw.take(160) }
                throw IllegalStateException("HTTP ${resp.code}: $errDetail")
            }
            raw
        }

        checkOpen()
        checkActive()

        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini 未返回 candidates")
        for (i in 0 until candidates.length()) {
            val content = candidates.optJSONObject(i)?.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val inlineData = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                    ?: continue
                val mimeType = inlineData.optString("mimeType")
                    .ifBlank { inlineData.optString("mime_type") }
                val b64 = inlineData.optString("data")
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        return decodeAudioPayload(bytes, mimeType, checkActive)
                    }
                }
            }
        }
        throw IllegalStateException("Gemini 响应中未包含音频数据")
    }

    private fun decodeAudioPayload(
        bytes: ByteArray,
        mimeType: String,
        checkActive: () -> Unit,
    ): Pair<FloatArray, Int> {
        val lowerMime = mimeType.lowercase(Locale.ROOT)
        // 1. Raw 16-bit Little-Endian PCM (e.g. "audio/L16;codec=pcm;rate=24000" or "audio/pcm;rate=24000")
        if (lowerMime.contains("l16") || lowerMime.contains("pcm")) {
            val rate = Regex("""rate\s*=\s*(\d+)""")
                .find(lowerMime)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 8_000..96_000 }
                ?: TARGET_RATE_HZ
            val sampleCount = bytes.size / 2
            if (sampleCount <= 0) throw IllegalStateException("Gemini PCM 音频数据为空")
            val buf = ByteBuffer.wrap(bytes, 0, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                samples[i] = (buf.short / 32768f).coerceIn(-1f, 1f)
            }
            return samples to rate
        }

        // 2. RIFF WAV header
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return decodeWavBytes(bytes)
        }

        // 3. Compressed container (MP3 / OGG / AAC)
        return decodeWithMediaCodec(bytes, checkActive)
    }

    private fun decodeWavBytes(bytes: ByteArray): Pair<FloatArray, Int> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buf.getShort(22).toInt().coerceAtLeast(1)
        val sampleRate = buf.getInt(24).coerceIn(8_000, 96_000)
        val bitsPerSample = buf.getShort(34).toInt()
        var offset = 12
        var dataOffset = 44
        var dataSize = bytes.size - 44
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(offset + 4).coerceAtLeast(0)
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                break
            }
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        if (bitsPerSample != 16 || dataSize < 2) {
            throw IllegalStateException("不支持的 WAV 位深：$bitsPerSample")
        }
        val frameCount = (dataSize / 2) / channels
        val samples = FloatArray(frameCount)
        val pcmBuf = ByteBuffer.wrap(bytes, dataOffset, frameCount * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += pcmBuf.short / 32768f
            }
            samples[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        return samples to sampleRate
    }

    private fun decodeWithMediaCodec(bytes: ByteArray, checkActive: () -> Unit): Pair<FloatArray, Int> {
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
                throw IllegalStateException("Gemini 音频格式无法识别")
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Gemini 音频缺少 MIME 类型")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<FloatArray>(4)
            var totalSamples = 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
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
                            ?: throw IllegalStateException("解码器输入缓冲区不可用")
                        val read = extractor.readSampleData(input, 0)
                        if (read <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.order(ByteOrder.LITTLE_ENDIAN)
                            val count = info.size / 2
                            val samples = FloatArray(count)
                            for (i in 0 until count) {
                                samples[i] = output.getShort(info.offset + i * 2) / 32768f
                            }
                            chunks.add(samples)
                            totalSamples += samples.size
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
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
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
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
        const val TAG = "GeminiTts"
        const val TARGET_RATE_HZ = 24_000
        const val MAX_CHUNK_CHARS = 240
        const val SOFT_CHUNK_CHARS = 120
        val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
        )
    }
}
