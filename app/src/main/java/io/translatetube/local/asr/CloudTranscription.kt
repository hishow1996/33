package io.translatetube.local.asr

import android.content.Context
import io.translatetube.local.translate.Crypto
import io.translatetube.local.yt.executeCancellableHttpCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/** Reads provider keys from encrypted SharedPreferences values written by SettingsScreen. */
object CloudTranscriptionSettings {
    fun apiKey(context: Context, provider: String): String {
        if (provider != "elevenlabs" && provider != "cartesia" && provider != "kawa") return ""
        val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("asr_" + provider + "_key", "").orEmpty()
        return Crypto.decrypt(stored).trim()
    }
}

/** Batch cloud transcription for ElevenLabs Scribe, Cartesia Ink, and Kawa. Call from Dispatchers.IO. */
object CloudTranscription {
    data class Result(
        val cues: List<Triple<Long, Long, String>>,
        val language: String,
    )

    internal data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

    /** One Kawa upload with a one-second leading context window and a non-overlapping cue ownership window. */
    internal data class KawaChunkRange(
        val offsetBytes: Long,
        val sizeBytes: Long,
        val ownedStartMs: Long,
        val ownedEndMs: Long,
    )

    /** Split provider words at long silences instead of stretching a caption across the pause. */
    internal const val MAX_WORD_GAP_MS = 800L

    /** Bound JSON allocation on memory-constrained phones when a provider returns malformed data. */
    internal const val MAX_RESPONSE_BODY_BYTES = 16L * 1024L * 1024L

    /**
     * Cartesia accepts documented ISO-639-1 codes; "auto" is a UI sentinel, not an API code.
     * Both "no" and "nn" are supported and must retain their distinct meanings.
     */
    internal fun cartesiaLanguageCode(language: String): String? {
        val normalized = language.trim().lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty() || normalized == "auto") return null
        return normalized
    }

    /** Kawa's automatic-detection mode is represented by omitting the language field. */
    internal fun kawaLanguageCode(language: String): String? =
        language.trim().takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }

    /**
     * Kawa's asynchronous job endpoint can be briefly rate-limited or unavailable. Retry only
     * transient network/HTTP failures; authentication and task/schema errors should fail promptly.
     */
    internal fun shouldRetryKawaPoll(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is java.util.concurrent.CancellationException }) return false
        val messages = chain.mapNotNull { it.message }.joinToString(" ")
        val statusCode = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)
            .find(messages)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (statusCode != null) {
            return statusCode in setOf(408, 425, 429, 500, 502, 503, 504)
        }
        return chain.any { it is IOException }
    }

    /** Read UTF-8 response data with a hard byte limit and cooperative cancellation checks. */
    internal fun readBoundedUtf8(
        input: InputStream,
        maxBytes: Long = MAX_RESPONSE_BODY_BYTES,
        checkActive: () -> Unit = {},
    ): String {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "云端响应大小上限无效" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        var totalBytes = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > maxBytes - totalBytes) {
                throw IOException("云端转写响应超过安全上限（${maxBytes} 字节），请切换本地 Whisper 或缩短音频后重试")
            }
            output.write(buffer, 0, count)
            totalBytes += count.toLong()
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun readResponseBody(body: ResponseBody?, checkActive: () -> Unit): String {
        if (body == null) return ""
        if (body.contentLength() > MAX_RESPONSE_BODY_BYTES) {
            throw IOException("云端转写响应超过 16 MB 安全上限，请切换本地 Whisper 或缩短音频后重试")
        }
        return body.byteStream().use { input ->
            readBoundedUtf8(input, checkActive = checkActive)
        }
    }

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
        require(pcmFile.file.isFile && pcmFile.file.length() == dataBytes) { "PCM 缓存长度与声明的采样数不一致，无法创建 WAV 文件" }

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

    suspend fun transcribe(
        provider: String,
        apiKey: String,
        language: String,
        wavFile: File,
        durationMs: Long,
        checkActive: () -> Unit = {},
    ): Result {
        require(provider == "elevenlabs" || provider == "cartesia" || provider == "kawa") { "未知云端转写服务" }
        require(apiKey.isNotBlank()) { "请先在设置 → 转写引擎中填写 API Key" }
        require(wavFile.isFile && wavFile.length() > 44L) { "待转写音频为空" }
        if (durationMs <= 0L) throw IOException("待转写音频时长无效，无法建立字幕时间轴")
        checkActive()
        val ownerJob = currentCoroutineContext()[Job]
        if (provider == "kawa") return transcribeKawa(apiKey, language, wavFile, durationMs, checkActive, ownerJob)

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
                    cartesiaLanguageCode(language)?.let { addFormDataPart("language", it) }
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
        val responseText = executeCancellableHttpCall(client.newCall(request), ownerJob) { response ->
            val body = readResponseBody(response.body, checkActive)
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
            val interval = providerWordRange(startSeconds, endSeconds, safeDuration) ?: continue
            words += TimedWord(token, interval.first, interval.second)
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


    /** Kawa limits uploads to 10 MB. Process one 8 MiB PCM chunk at a time to bound disk usage. */
    private suspend fun transcribeKawa(
        apiKey: String,
        language: String,
        wavFile: File,
        durationMs: Long,
        checkActive: () -> Unit,
        ownerJob: Job?,
    ): Result {
        val pcmBytes = wavFile.length() - 44L
        if (pcmBytes <= 0L || pcmBytes % 2L != 0L) {
            throw IOException("待转写 WAV 音频为空或 PCM 数据未按 16 位采样对齐")
        }
        val ranges = kawaChunkRangesWithContext(pcmBytes)
        if (ranges.isEmpty()) throw IOException("待转写 WAV 音频为空")
        val cues = ArrayList<Triple<Long, Long, String>>()
        var detectedLanguage = language

        // Do not create every chunk up front: for a long recording that duplicates almost the
        // entire WAV on disk and can exhaust free storage before the first request is uploaded.
        java.io.RandomAccessFile(wavFile, "r").use { input ->
            for (range in ranges) {
                val offsetBytes = range.offsetBytes
                val size = range.sizeBytes
                checkActive()
                ownerJob?.ensureActive()
                val offsetMs = offsetBytes / 32L // 16 kHz * mono * PCM16 = 32 bytes per millisecond.
                val chunk = File.createTempFile("asr-kawa-", ".wav", wavFile.parentFile)
                try {
                    val header = ByteArray(44)
                    input.seek(0L)
                    input.readFully(header)
                    val little = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    little.putInt(4, (36L + size).toInt())
                    little.putInt(40, size.toInt())

                    FileOutputStream(chunk).use { output ->
                        output.write(header)
                        input.seek(44L + offsetBytes)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = size
                        while (remaining > 0L) {
                            checkActive()
                            ownerJob?.ensureActive()
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) throw IOException("Kawa 分段音频读取不完整")
                            if (n > 0) {
                                output.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        output.flush()
                    }

                    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", chunk.name, chunk.asRequestBody("audio/wav".toMediaType()))
                        .apply {
                            kawaLanguageCode(language)?.let { addFormDataPart("language", it) }
                        }
                        .build()
                    val create = Request.Builder().url("https://api.makimoto.ai/v1/transcriptions")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(form)
                        .build()
                    val jobId = executeCancellableHttpCall(client.newCall(create), ownerJob) { response ->
                        val body = readResponseBody(response.body, checkActive)
                        checkActive()
                        if (!response.isSuccessful) {
                            val message = runCatching {
                                JSONObject(body).optJSONObject("error")?.optString("message")
                            }.getOrNull()
                            val advice = when (response.code) {
                                401, 403 -> "Kawa API Key 无效或没有访问权限"
                                413 -> "Kawa 单次上传上限为 10 MB"
                                415 -> "Kawa 只接受 MP3 或 WAV 音频"
                                429 -> "Kawa 请求被限流或免费转写额度已用完，请稍后重试并检查账户额度"
                                else -> message?.takeIf { it.isNotBlank() }?.take(200) ?: "请检查网络和 Kawa 账户"
                            }
                            throw IOException("Kawa 云端转写失败（HTTP " + response.code + "）：" + advice)
                        }
                        JSONObject(body).optString("job_id").takeIf { it.isNotBlank() }
                            ?: throw IOException("Kawa 没有返回转写任务编号")
                    }

                    // The service has accepted the file; keep only one request chunk while polling.
                    chunk.delete()

                    var result: JSONObject? = null
                    var attempts = 0
                    while (attempts++ < 180) {
                        checkActive()
                        ownerJob?.ensureActive()
                        val poll = Request.Builder()
                            .url("https://api.makimoto.ai/v1/transcriptions/$jobId")
                            .header("Authorization", "Bearer " + apiKey)
                            .get()
                            .build()
                        val status = try {
                            executeCancellableHttpCall(client.newCall(poll), ownerJob) { response ->
                                val body = readResponseBody(response.body, checkActive)
                                checkActive()
                                if (!response.isSuccessful) {
                                    throw IOException("Kawa 查询转写状态失败（HTTP " + response.code + "）")
                                }
                                JSONObject(body)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: IOException) {
                            if (attempts >= 180 || !shouldRetryKawaPoll(error)) throw error
                            // Keep the remote task alive through brief rate limits/transient outages,
                            // while bounding extra polling traffic and preserving prompt cancellation.
                            delay((500L + attempts.toLong() * 250L).coerceAtMost(5_000L))
                            continue
                        }
                        when (val stateName = status.optString("status")) {
                            "succeeded" -> {
                                result = status.optJSONObject("result")
                                    ?: throw IOException("Kawa 已报告转写完成，但响应缺少有效 result 数据")
                                break
                            }
                            "failed" -> throw IOException(
                                "Kawa 转写任务失败：" +
                                    status.optJSONObject("error")?.optString("message").orEmpty()
                                        .ifBlank { "服务端处理失败" }.take(200)
                            )
                            "queued", "processing" -> Unit
                            else -> throw IOException("Kawa 返回未知转写状态：" + stateName.take(80))
                        }
                        delay(2_000L)
                    }

                    val obj = result ?: throw IOException("Kawa 转写等待超时，请稍后重试")
                    obj.optString("language").takeIf { it.isNotBlank() }?.let { detectedLanguage = it }
                    val transcript = obj.optJSONArray("transcript")
                        ?: throw IOException("Kawa 返回结果缺少分段时间戳")
                    for (i in 0 until transcript.length()) {
                        checkActive()
                        val item = transcript.optJSONObject(i) ?: continue
                        val text = item.optString("text").trim()
                        val start = item.optDouble("time_start", Double.NaN)
                        val end = item.optDouble("time_end", Double.NaN)
                        if (text.isBlank()) continue
                        val chunkDurationMs = (size + 31L) / 32L
                        val timeline = mapChunkCue(
                            offsetMs = offsetMs,
                            localStartSeconds = start,
                            localEndSeconds = end,
                            chunkDurationMs = chunkDurationMs,
                            totalDurationMs = durationMs,
                        ) ?: continue
                        // The adjacent chunk owns its leading context interval. This keeps a cue
                        // near a hard upload boundary from being emitted twice.
                        if (timeline.first < range.ownedStartMs || timeline.first >= range.ownedEndMs) continue
                        cues += Triple(timeline.first, timeline.second, text)
                    }
                } finally {
                    runCatching { chunk.delete() }
                }
            }
        }
        if (cues.isEmpty()) throw IOException("Kawa 没有返回有效字幕时间轴；可切回本地 Whisper 重试")
        return Result(cues.sortedBy { it.first }, detectedLanguage)
    }

    /**
     * Convert a provider's chunk-local seconds to the whole-media timeline without allowing an
     * invalid timestamp to overflow when the chunk offset is added. Provider times occasionally
     * exceed the uploaded chunk; clamp only the end, and reject starts outside that chunk.
     */
    internal fun mapChunkCue(
        offsetMs: Long,
        localStartSeconds: Double,
        localEndSeconds: Double,
        chunkDurationMs: Long,
        totalDurationMs: Long,
    ): Pair<Long, Long>? {
        if (offsetMs < 0L || chunkDurationMs <= 0L || totalDurationMs <= 0L) return null
        if (!localStartSeconds.isFinite() || !localEndSeconds.isFinite() ||
            localStartSeconds < 0.0 || localEndSeconds <= localStartSeconds
        ) return null

        val globalOffset = offsetMs.coerceAtMost(totalDurationMs)
        val remainingMediaMs = totalDurationMs - globalOffset
        val usableChunkMs = minOf(chunkDurationMs, remainingMediaMs)
        if (usableChunkMs <= 0L) return null

        // Compare before conversion: multiplying a huge but finite Double by 1000 can overflow.
        if (localStartSeconds >= usableChunkMs.toDouble() / 1_000.0) return null
        val startMs = (localStartSeconds * 1_000.0).toLong().coerceIn(0L, usableChunkMs - 1L)
        val endMsDouble = localEndSeconds * 1_000.0
        val endMs = if (!endMsDouble.isFinite() || endMsDouble >= usableChunkMs.toDouble()) {
            usableChunkMs
        } else {
            endMsDouble.toLong().coerceAtLeast(startMs + 1L).coerceAtMost(usableChunkMs)
        }
        return (globalOffset + startMs) to (globalOffset + endMs)
    }

    /**
     * Normalize a provider's seconds-based word range before it enters subtitle grouping.
     * Reject zero-length, negative, non-finite, or post-media starts; clamp only an end that
     * extends beyond the media so malformed data cannot invent a final 1 ms caption.
     */
    internal fun providerWordRange(
        startSeconds: Double,
        endSeconds: Double,
        durationMs: Long,
    ): Pair<Long, Long>? {
        if (durationMs <= 0L ||
            !startSeconds.isFinite() || !endSeconds.isFinite() ||
            startSeconds < 0.0 || endSeconds <= startSeconds
        ) return null

        val startMsDouble = startSeconds * 1_000.0
        if (!startMsDouble.isFinite() || startMsDouble >= durationMs.toDouble()) return null
        val safeStart = startMsDouble.toLong().coerceIn(0L, durationMs - 1L)

        val endMsDouble = endSeconds * 1_000.0
        val safeEnd = if (!endMsDouble.isFinite() || endMsDouble >= durationMs.toDouble()) {
            durationMs
        } else {
            endMsDouble.toLong().coerceAtLeast(safeStart + 1L).coerceAtMost(durationMs)
        }
        return safeStart to safeEnd
    }

    internal fun kawaChunkRanges(pcmBytes: Long): List<Pair<Long, Long>> {
        if (pcmBytes <= 0L || pcmBytes % 2L != 0L) return emptyList()
        val chunkBytes = 8L * 1024L * 1024L
        val result = ArrayList<Pair<Long, Long>>()
        var offset = 0L
        while (offset < pcmBytes) {
            val size = minOf(chunkBytes, pcmBytes - offset)
            result += offset to size
            offset += size
        }
        return result
    }

    /**
     * Adds one second of preceding audio to each Kawa request after the first. The upload stays
     * below Kawa's 10 MB limit, while the ownership windows remain contiguous and non-overlapping.
     */
    internal fun kawaChunkRangesWithContext(pcmBytes: Long): List<KawaChunkRange> {
        if (pcmBytes <= 0L || pcmBytes % 2L != 0L) return emptyList()
        val chunkBytes = 8L * 1024L * 1024L
        // 16 kHz mono PCM16 is 32 bytes per millisecond; align overlap to a whole second.
        val contextBytes = 32_000L
        val result = ArrayList<KawaChunkRange>()
        var primaryOffset = 0L
        while (primaryOffset < pcmBytes) {
            val offset = (primaryOffset - contextBytes).coerceAtLeast(0L)
            val end = primaryOffset + minOf(chunkBytes, pcmBytes - primaryOffset)
            val isLast = end == pcmBytes
            val ownedStartMs = offset / 32L
            val ownedEndMs = if (isLast) pcmBytes / 32L else (end - contextBytes) / 32L
            val size = end - offset
            if (size <= 0L || size % 2L != 0L || ownedEndMs <= ownedStartMs) {
                return emptyList()
            }
            result += KawaChunkRange(
                offsetBytes = offset,
                sizeBytes = size,
                ownedStartMs = ownedStartMs,
                ownedEndMs = ownedEndMs,
            )
            if (isLast) break
            primaryOffset = end
        }
        return result
    }

    internal fun groupWords(words: List<TimedWord>, durationMs: Long): List<Triple<Long, Long, String>> {
        // Defend this grouping boundary too, rather than assuming every caller has already
        // validated provider data. Clip only word ends; words starting outside the media are junk.
        if (durationMs <= 0L || words.isEmpty()) return emptyList()
        val orderedWords = words.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && it.startMs >= 0L &&
                it.startMs < durationMs && it.endMs > it.startMs }
            .map { it.copy(endMs = it.endMs.coerceAtMost(durationMs)) }
            .filter { it.endMs > it.startMs }
            .sortedWith(compareBy<TimedWord> { it.startMs }.thenBy { it.endMs })
            .toList()
        if (orderedWords.isEmpty()) return emptyList()
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

        for (word in orderedWords) {
            val pauseMs = if (text.isNotEmpty()) word.startMs - cueEnd else 0L
            if (text.isNotEmpty() && pauseMs > MAX_WORD_GAP_MS) flush()
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
