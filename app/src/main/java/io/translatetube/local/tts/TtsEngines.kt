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
    fun synth(text: String, speed: Float = 1.0f): TtsAudio
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

    override fun synth(text: String, speed: Float): TtsAudio {
        val audio = tts.generate(text, speakerId, speed)
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    override fun close() = tts.release()
}

/** 系统 TTS（华为自带，兜底） */
class SystemTts(context: Context) : TtsEngine {
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var ok = false

    init {
        val tts = TextToSpeech(appCtx) { status ->
            ok = status == TextToSpeech.SUCCESS
            if (ok) {
                try {
                    ttsRef.get()?.language = Locale.SIMPLIFIED_CHINESE
                    ttsRef.get()?.setSpeechRate(1.0f)
                } catch (_: Exception) {}
            }
            ready.countDown()
        }
        ttsRef.set(tts)
        ready.await()
    }

    override fun synth(text: String, speed: Float): TtsAudio {
        if (!ok) return TtsAudio(FloatArray(0), 16000)
        val tmp = java.io.File.createTempFile("tts_", ".wav", contextCacheDir())
        val r = ttsRef.get()?.synthesizeToFile(text, android.os.Bundle(), null, tmp.absolutePath) ?: -1
        if (r < 0) { tmp.delete(); return TtsAudio(FloatArray(0), 16000) }
        // synthesizeToFile 异步：轮询完成（一行字幕通常 <5s）
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (tmp.length() > 44) { Thread.sleep(80); break }
            Thread.sleep(50)
        }
        val pcm = readWav(tmp)
        tmp.delete()
        return pcm
    }

    private fun contextCacheDir(): java.io.File {
        val f = java.io.File(appCtx.cacheDir, "tts_tmp").apply { mkdirs() }
        return f
    }

    /** RIFF chunk 遍历解析（兼容带扩展头的 WAV），找 fmt 采样率 + data 负载 */
    private fun readWav(f: java.io.File): TtsAudio {
        val bytes = f.readBytes()
        if (bytes.size < 44) return TtsAudio(FloatArray(0), 16000)
        var offset = 12
        var sr = 16000
        var dataStart = -1
        var dataLen = 0
        val le = java.nio.ByteOrder.LITTLE_ENDIAN
        fun i32(p: Int) = ((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
            ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 3].toInt() and 0xFF) shl 24))
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = i32(offset + 4)
            when (id) {
                "fmt " -> sr = i32(offset + 16)
                "data" -> { dataStart = offset + 8; dataLen = size.coerceAtMost(bytes.size - dataStart); }
            }
            if (dataStart >= 0) break
            offset += 8 + size + (size and 1)
        }
        if (dataStart < 0) return TtsAudio(FloatArray(0), sr)
        val bb = java.nio.ByteBuffer.wrap(bytes, dataStart, dataLen).order(le)
        val n = dataLen / 2
        val samples = FloatArray(n) { bb.short / 32768f }
        return TtsAudio(samples, sr)
    }

    override fun close() {
        try { ttsRef.get()?.stop(); ttsRef.get()?.shutdown() } catch (_: Exception) {}
    }
}
