package io.translatetube.local.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** TTS 统一输出：合成的 PCM 音频 */
data class TtsAudio(val samples: FloatArray, val sampleRate: Int)

interface TtsEngine {
    val name: String
    /** Stable synthesis configuration identity, used to avoid stale PCM cache hits. */
    val cacheKey: String get() = name
    fun synth(text: String, speed: Float = 1.0f, speakerId: Int = -1): TtsAudio
    fun close()
}

/** Kokoro v1.1 int8 本地神经 TTS（中文主力） */
class KokoroTts(private val ctx: Context, private val speakerId: Int = 14, private val numThreads: Int = 2) : TtsEngine {
    override val name = "Kokoro"
    override val cacheKey: String get() = "$name-default-${speakerId.coerceIn(0, 53)}"
    private val tts: OfflineTts

    init {
        val m = io.translatetube.local.data.ModelFiles.ttsDir(ctx).absolutePath + "/"
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "${m}model.int8.onnx",
                    voices = "${m}voices.bin",
                    tokens = "${m}tokens.txt",
                    dataDir = "${m}espeak-ng-data",
                    lexicon = "${m}lexicon-zh.txt",
                    dictDir = "${m}dict",
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = "${m}phone-zh.fst,${m}date-zh.fst,${m}number-zh.fst",
            maxNumSentences = 2,
        )
        // assets=null → sherpa-onnx 走文件系统绝对路径加载（方案 B）
        tts = OfflineTts(null, config)
    }

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) return TtsAudio(FloatArray(0), 24000)
        val selectedSpeaker = (if (speakerId >= 0) speakerId else this.speakerId).coerceIn(0, 53)
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        val audio = tts.generate(normalizedText, selectedSpeaker, safeSpeed)
        val sampleRate = audio.sampleRate
        if (sampleRate !in 8_000..192_000 || audio.samples.isEmpty()) {
            android.util.Log.w("KokoroTts", "Invalid generated audio: rate=$sampleRate samples=\${audio.samples.size}")
            return TtsAudio(FloatArray(0), sampleRate.coerceIn(8_000, 192_000))
        }
        // Discard NaN/Infinity and prevent invalid floats from reaching AudioTrack.
        val clean = FloatArray(audio.samples.size) { i ->
            val value = audio.samples[i]
            if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        }
        return TtsAudio(clean, sampleRate)
    }

    override fun close() = tts.release()
}

/** 系统 TTS：等待合成回调完成后再读取 WAV，避免读到尚未写完的文件。 */
class SystemTts(context: Context) : TtsEngine {
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    // System voices can change in Android settings. Include the active engine and Chinese
    // voice identity so cached PCM from a previous system voice is not reused incorrectly.
    override val cacheKey: String
        get() {
            val engine = ttsRef.get()
            val voice = engine?.voice
            return listOf(
                name,
                engine?.defaultEngine.orEmpty(),
                voice?.name.orEmpty(),
                voice?.locale?.toString().orEmpty(),
            ).joinToString("|")
        }
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var activeError = false

    init {
        val tts = TextToSpeech(appCtx) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        ttsRef.set(tts)
        if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS) || !ok) {
            runCatching { tts.shutdown() }
            throw IllegalStateException("系统 TTS 初始化失败")
        }

        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            // This mode must remain the selected system TTS engine. Do not silently switch
            // to another locale or another engine when Chinese voice data is missing.
            runCatching { tts.shutdown() }
            throw IllegalStateException("系统 TTS 未安装或不支持中文语音数据")
        }

        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) activeLatch?.countDown()
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    activeError = true
                    activeLatch?.countDown()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == activeUtteranceId) {
                    activeError = true
                    activeLatch?.countDown()
                }
            }
        })
    }

    @Synchronized
    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        if (text.isBlank()) return TtsAudio(FloatArray(0), 16000)
        val tts = ttsRef.get()
            ?: throw IllegalStateException("系统 TTS 实例已经关闭")
        if (!ok) throw IllegalStateException("系统 TTS 尚未初始化成功")

        val tmp = java.io.File.createTempFile("tts_", ".wav", contextCacheDir())
        val utteranceId = "system-tts-" + System.nanoTime()
        val completed = java.util.concurrent.CountDownLatch(1)
        activeUtteranceId = utteranceId
        activeLatch = completed
        activeError = false
        try {
            tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            val result = tts.synthesizeToFile(text, android.os.Bundle(), tmp, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 拒绝了本次合成（状态码 $result）")
            }
            if (!completed.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { tts.stop() }
                throw IllegalStateException("系统 TTS 合成超时")
            }
            if (activeError) {
                throw IllegalStateException("系统 TTS 合成回调返回错误")
            }
            val audio = readWav(tmp)
            if (audio.samples.isEmpty()) {
                throw IllegalStateException("系统 TTS 已返回，但生成的 WAV 没有可播放音频")
            }
            return audio
        } finally {
            activeUtteranceId = null
            activeLatch = null
            tmp.delete()
        }
    }

    private fun contextCacheDir(): java.io.File =
        java.io.File(appCtx.cacheDir, "tts_tmp").apply { mkdirs() }

    /** Traverse RIFF chunks; sample rate is fmt data +4, not the byteRate field. */
    /** Decode common WAV PCM/float formats to mono float PCM for AudioTrack. */
    private fun readWav(f: java.io.File): TtsAudio {
        val bytes = f.readBytes()
        if (bytes.size < 44) throw IllegalStateException("系统 TTS WAV 文件过短")

        var offset = 12
        var formatCode = 0
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16
        var dataStart = -1
        var dataLen = 0

        fun u16(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
        fun i32(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                ((bytes[p + 3].toInt() and 0xFF) shl 24)

        // Continue scanning after the data chunk too; WAV chunks are not required to be
        // arranged in exactly the same order by every system TTS provider.
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = i32(offset + 4).coerceAtLeast(0)
            val chunkDataStart = offset + 8
            val available = (bytes.size - chunkDataStart).coerceAtLeast(0)
            val safeSize = chunkSize.coerceAtMost(available)
            when (id) {
                "fmt " -> if (safeSize >= 16) {
                    formatCode = u16(chunkDataStart)
                    channels = u16(chunkDataStart + 2)
                    sampleRate = i32(chunkDataStart + 4)
                    bitsPerSample = u16(chunkDataStart + 14)
                }
                "data" -> {
                    dataStart = chunkDataStart
                    dataLen = safeSize
                }
            }
            val next = chunkDataStart.toLong() + safeSize.toLong() + (safeSize and 1)
            if (next > Int.MAX_VALUE) break
            offset = next.toInt()
        }

        if (dataStart < 0 || dataLen <= 0) {
            throw IllegalStateException("系统 TTS WAV 中没有 data 音频块")
        }
        if (sampleRate !in 8_000..192_000) {
            throw IllegalStateException("系统 TTS WAV 采样率无效：$sampleRate")
        }
        if (channels !in 1..8) {
            throw IllegalStateException("系统 TTS WAV 声道数不支持：$channels")
        }
        val bytesPerSample = when {
            formatCode == 1 && bitsPerSample in listOf(8, 16, 24, 32) -> bitsPerSample / 8
            formatCode == 3 && bitsPerSample == 32 -> 4
            else -> throw IllegalStateException("系统 TTS WAV 格式不支持：format=$formatCode, bits=$bitsPerSample")
        }
        val frameSize = bytesPerSample * channels
        val frameCount = dataLen / frameSize
        if (frameCount <= 0) throw IllegalStateException("系统 TTS WAV 没有完整音频帧")

        val samples = FloatArray(frameCount)
        var p = dataStart
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until channels) {
                val value = when {
                    formatCode == 3 && bitsPerSample == 32 -> Float.fromBits(i32(p))
                    bitsPerSample == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    bitsPerSample == 16 -> {
                        val raw = u16(p)
                        (if (raw >= 32768) raw - 65536 else raw) / 32768f
                    }
                    bitsPerSample == 24 -> {
                        var raw = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 2].toInt() and 0xFF) shl 16)
                        if ((raw and 0x800000) != 0) raw = raw or -0x1000000
                        raw / 8_388_608f
                    }
                    else -> i32(p) / 2_147_483_648f
                }
                sum += if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
                p += bytesPerSample
            }
            samples[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        if (samples.none { kotlin.math.abs(it) > 0.00001f }) {
            throw IllegalStateException("系统 TTS WAV 只有静音采样")
        }
        return TtsAudio(samples, sampleRate)
    }

    override fun close() {
        activeLatch?.countDown()
        try { ttsRef.get()?.stop(); ttsRef.get()?.shutdown() } catch (_: Exception) {}
        ttsRef.set(null)
    }
}
