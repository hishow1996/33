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
    fun synth(text: String, speed: Float = 1.0f, speakerId: Int = -1): TtsAudio
    fun close()
}

/** Kokoro v1.1 int8 本地神经 TTS（中文主力） */
class KokoroTts(private val ctx: Context, private val speakerId: Int = 14, private val numThreads: Int = 2) : TtsEngine {
    override val name = "Kokoro"
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
        val selectedSpeaker = if (speakerId >= 0) speakerId else this.speakerId
        val audio = tts.generate(text, selectedSpeaker, speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    override fun close() = tts.release()
}

/** 系统 TTS：等待合成回调完成后再读取 WAV，避免读到尚未写完的文件。 */
class SystemTts(context: Context) : TtsEngine {
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
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
            val fallbackResult = tts.setLanguage(Locale.getDefault())
            if (fallbackResult < TextToSpeech.LANG_AVAILABLE) {
                android.util.Log.e("SystemTts", "No supported TTS language installed")
            } else {
                android.util.Log.w("SystemTts", "Chinese TTS unavailable; using device default locale")
            }
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
        val tts = ttsRef.get() ?: return TtsAudio(FloatArray(0), 16000)
        if (!ok || text.isBlank()) return TtsAudio(FloatArray(0), 16000)

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
                android.util.Log.w("SystemTts", "synthesizeToFile rejected request, result=" + result)
                return TtsAudio(FloatArray(0), 16000)
            }
            if (!completed.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                android.util.Log.w("SystemTts", "Timed out waiting for system TTS synthesis")
                runCatching { tts.stop() }
                return TtsAudio(FloatArray(0), 16000)
            }
            if (activeError) {
                android.util.Log.w("SystemTts", "System TTS synthesis callback reported an error")
                return TtsAudio(FloatArray(0), 16000)
            }
            val audio = readWav(tmp)
            if (audio.samples.isEmpty()) {
                android.util.Log.w("SystemTts", "System TTS produced an empty/unsupported WAV")
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
    private fun readWav(f: java.io.File): TtsAudio {
        val bytes = f.readBytes()
        if (bytes.size < 44) return TtsAudio(FloatArray(0), 16000)
        var offset = 12
        var sampleRate = 16000
        var dataStart = -1
        var dataLen = 0
        fun i32(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                ((bytes[p + 3].toInt() and 0xFF) shl 24)

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = i32(offset + 4).coerceAtLeast(0)
            val chunkDataStart = offset + 8
            val available = (bytes.size - chunkDataStart).coerceAtLeast(0)
            val safeSize = chunkSize.coerceAtMost(available)
            when (id) {
                "fmt " -> if (safeSize >= 16) {
                    // WAVEFORMAT: format(2), channels(2), sampleRate(4), byteRate(4)...
                    sampleRate = i32(chunkDataStart + 4).takeIf { it in 8_000..192_000 } ?: 16_000
                }
                "data" -> {
                    dataStart = chunkDataStart
                    dataLen = safeSize
                }
            }
            if (dataStart >= 0) break
            offset = chunkDataStart + safeSize + (safeSize and 1)
        }
        if (dataStart < 0 || dataLen < 2) return TtsAudio(FloatArray(0), sampleRate)
        val count = dataLen / 2
        val bb = java.nio.ByteBuffer.wrap(bytes, dataStart, count * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(count) { bb.short / 32768f }
        return TtsAudio(samples, sampleRate)
    }

    override fun close() {
        activeLatch?.countDown()
        try { ttsRef.get()?.stop(); ttsRef.get()?.shutdown() } catch (_: Exception) {}
        ttsRef.set(null)
    }
}
