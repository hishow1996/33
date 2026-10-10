package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** Whisper base 多语言本地转写（sherpa-onnx） */
class WhisperEngine(ctx: Context) : AutoCloseable {
    private val recognizer: OfflineRecognizer
    /** 最近一次转写检测到的语言码（如 "en"/"ja"） */
    @Volatile var lastLang: String = ""
        private set

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "whisper/base-encoder.int8.onnx",
                    decoder = "whisper/base-decoder.int8.onnx",
                ),
                tokens = "whisper/base-tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = ctx.assets, config = config)
    }

    /** 一次转写整段 PCM（16k mono float），自动检测语言 */
    fun transcribe(pcm: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(pcm, sampleRate = 16000)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        val text = result.text
        result.lang?.takeIf { it.isNotBlank() }?.let { lastLang = it }
        stream.release()
        return text
    }

    /** 长音频分段转写：30s 窗口 + 1s 重叠，返回带时间轴文本 */
    fun transcribeSegmented(
        pcm: FloatArray,
        onProgress: (doneSec: Long, totalSec: Long) -> Unit = { _, _ -> },
    ): List<Pair<Long, String>> {
        val sr = 16000
        val window = 30 * sr
        val hop = 29 * sr
        val out = mutableListOf<Pair<Long, String>>()
        var start = 0
        val totalSec = pcm.size / sr
        while (start < pcm.size) {
            val end = (start + window).coerceAtMost(pcm.size)
            val seg = pcm.copyOfRange(start, end)
            val text = transcribe(seg).trim()
            if (text.isNotEmpty()) out += (start / sr).toLong() to text
            onProgress((end / sr).toLong(), totalSec.toLong())
            if (end >= pcm.size) break
            start += hop
        }
        return out
    }

    /** VAD 分段精确转写：段起点/终点来自 VAD，行级时间轴 */
    fun transcribeBySegments(
        pcm: FloatArray,
        segs: List<Pair<Long, Long>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Triple<Long, Long, String>> {
        val sr = 16000
        val out = mutableListOf<Triple<Long, Long, String>>()
        segs.forEachIndexed { i, (a, b) ->
            val s0 = (a * sr / 1000).toInt()
            val s1 = (b * sr / 1000).toInt().coerceAtMost(pcm.size)
            if (s1 > s0) {
                val text = transcribe(pcm.copyOfRange(s0, s1)).trim()
                if (text.isNotEmpty()) out += Triple(a, b, text)
            }
            onProgress(i + 1, segs.size)
        }
        return out
    }

    override fun close() = recognizer.release()
}
