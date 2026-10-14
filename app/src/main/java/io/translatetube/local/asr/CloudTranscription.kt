package io.translatetube.local.asr

import android.content.Context
import io.translatetube.local.translate.Crypto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Reads provider keys from encrypted SharedPreferences values written by SettingsScreen. */
object CloudTranscriptionSettings {
    fun apiKey(context: Context, provider: String): String {
        if (provider != "elevenlabs" && provider != "cartesia") return ""
        val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("asr_" + provider + "_key", "").orEmpty()
        return Crypto.decrypt(stored).trim()
    }
}

/** Batch cloud transcription for ElevenLabs Scribe and Cartesia Ink. Call from Dispatchers.IO. */
object CloudTranscription {
    data class Result(
        val cues: List<Triple<Long, Long, String>>,
        val language: String,
    )

    private data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(20, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Wrap the local 16kHz mono PCM16 cache as WAV; the video frames are never uploaded. */
    fun writeWavFile(
        pcmFile: AudioExtract.PcmFile,
        output: File,
        checkActive: () -> Unit = {},
    ) {
        require(pcmFile.sampleRate == 16_000) { "云端转写需要 16kHz PCM 音频" }
        val dataBytes = pcmFile.sampleCount * 2L
        require(dataBytes in 2L..(0xffff_ffffL - 36L)) { "音频太大，无法安全创建 WAV 文件" }
        require(pcmFile.file.isFile && pcmFile.file.length() >= dataBytes) { "待转写音频缓存不完整，请重试" }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charset.forName("US-ASCII")))
        header.putInt((36L + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charset.forName("US-ASCII")))
        header.put("fmt ".toByteArray(Charset.forName("US-ASCII")))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(pcmFile.sampleRate)
        header.putInt(pcmFile.sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charset.forName("US-ASCII")))
        header.putInt(dataBytes.toInt())

        FileInputStream(pcmFile.file).use { input ->
            BufferedOutputStream(FileOutputStream(output)).use { dest ->
                dest.write(header.array())
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (copied < dataBytes) {
                    checkActive()
                    val wanted = minOf(buffer.size.toLong(), dataBytes - copied).toInt()
                    val count = input.read(buffer, 0, wanted)
                    if (count < 0) throw IOException("音频缓存提前结束，无法创建完整 WAV")
                    if (count == 0) continue
                    dest.write(buffer, 0, count)
                    copied += count
                }
            }
        }
    }

    fun transcribe(
        provider: String,
        apiKey: String,
        wavFile: File,
        durationMs: Long,
        checkActive: () -> Unit = {},
    ): Result {
        require(provider == "elevenlabs" || provider == "cartesia") { "未知云端转写服务" }
        require(apiKey.isNotBlank()) { "请先在设置 → 转写引擎中填写 API Key" }
        require(wavFile.isFile && wavFile.length() > 44L) { "待转写音频为空" }
        checkActive()

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", wavFile.name, wavFile.asRequestBody("audio/wav".toMediaType()))
            .apply {
                if (provider == "elevenlabs") {
                    addFormDataPart("model_id", "scribe_v2")
                    addFormDataPart("tag_audio_events", "false")
                    addFormDataPart("timestamps_granularity", "word")
                } else {
                    addFormDataPart("model", "ink-whisper")
                    addFormDataPart("timestamp_granularities[]", "word")
                }
            }
            .build()

        val request = Request.Builder()
            .url(if (provider == "elevenlabs") {
                "https://api.elevenlabs.io/v1/speech-to-text"
            } else {
                "https://api.cartesia.ai/stt"
            })
            .apply {
                if (provider == "elevenlabs") {
                    header("xi-api-key", apiKey)
                } else {
                    header("Authorization", "Bearer " + apiKey)
                    header("Cartesia-Version", "2026-08-14")
                }
            }
            .post(form)
            .build()

        checkActive()
        val responseText = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            checkActive()
            if (!response.isSuccessful) {
                val serverMessage = try {
                    val obj = JSONObject(body)
                    obj.optString("detail").takeIf { it.isNotBlank() }
                        ?: obj.optString("message").takeIf { it.isNotBlank() }
                        ?: obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                val advice = when (response.code) {
                    401, 403 -> "API Key 无效或当前账户没有转写权限"
                    413 -> "音频文件超过服务商允许的大小"
                    429 -> "云端转写额度或请求频率已达到上限"
                    else -> serverMessage?.take(240) ?: "请检查网络、模型权限和服务商账户"
                }
                val providerName = if (provider == "elevenlabs") "ElevenLabs" else "Cartesia"
                throw IOException(providerName + " 云端转写失败（HTTP " + response.code + "）：" + advice)
            }
            body
        }

        val root = try {
            JSONObject(responseText)
        } catch (error: Exception) {
            throw IOException("云端转写返回的内容无法解析", error)
        }
        val wordsArray = root.optJSONArray("words")
            ?: root.optJSONObject("transcript")?.optJSONArray("words")
            ?: throw IOException("云端转写没有返回词级时间戳，请检查服务商模型与接口权限")
        val safeDuration = durationMs.coerceAtLeast(1L)
        val words = ArrayList<TimedWord>(wordsArray.length())

        for (index in 0 until wordsArray.length()) {
            if ((index and 0xff) == 0) checkActive()
            val item = wordsArray.optJSONObject(index) ?: continue
            if (item.optString("type") == "spacing" || item.optString("type") == "audio_event") continue
            val token = item.optString("text").trim().ifBlank { item.optString("word").trim() }
            if (token.isBlank()) continue
            val startSeconds = item.optDouble("start", Double.NaN)
            val endSeconds = item.optDouble("end", Double.NaN)
            if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds < startSeconds) continue
            val startMs = (startSeconds * 1000.0).toLong().coerceIn(0L, safeDuration - 1L)
            val endMs = (endSeconds * 1000.0).toLong().coerceIn(startMs + 1L, safeDuration)
            words += TimedWord(token, startMs, endMs)
        }

        val cues = groupWords(words, safeDuration)
        if (cues.isEmpty()) {
            throw IOException("云端识别没有生成有效时间轴字幕；可切回本地 Whisper 重试")
        }
        val language = root.optString("language_code").ifBlank {
            root.optString("language").ifBlank {
                root.optJSONObject("transcript")?.optString("language").orEmpty()
            }
        }
        return Result(cues, language)
    }

    private fun groupWords(words: List<TimedWord>, durationMs: Long): List<Triple<Long, Long, String>> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<Triple<Long, Long, String>>()
        val text = StringBuilder()
        var cueStart = 0L
        var cueEnd = 0L
        var count = 0

        fun flush() {
            val value = text.toString().trim()
            if (value.isNotBlank() && cueEnd > cueStart) {
                val start = cueStart.coerceIn(0L, durationMs - 1L)
                val end = cueEnd.coerceIn(start + 1L, durationMs)
                result += Triple(start, end, value)
            }
            text.clear()
            count = 0
            cueStart = 0L
            cueEnd = 0L
        }

        for (word in words) {
            if (text.isNotEmpty() &&
                (count >= 14 || text.length >= 48 || word.endMs - cueStart >= 6_500L)
            ) flush()
            if (text.isEmpty()) cueStart = word.startMs
            if (text.isNotEmpty() && needsAsciiSpace(text.last(), word.text.first())) text.append(' ')
            text.append(word.text)
            cueEnd = maxOf(cueEnd, word.endMs)
            count++
            val last = word.text.last()
            if (last in ".!?。！？；;：:" || count >= 14 || text.length >= 48 || cueEnd - cueStart >= 6_500L) {
                flush()
            }
        }
        flush()
        return result
    }

    private fun needsAsciiSpace(previous: Char, next: Char): Boolean {
        fun asciiWord(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        return asciiWord(previous) && asciiWord(next)
    }
}
