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
    @Volatile private var closed = false
    /** 最近一次转写检测到的语言码（如 "en"/"ja"） */
    @Volatile var lastLang: String = ""
        private set

    init {
        val m = io.translatetube.local.data.ModelFiles.asrDir(ctx).absolutePath + "/"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "${m}base-encoder.int8.onnx",
                    decoder = "${m}base-decoder.int8.onnx",
                ),
                tokens = "${m}base-tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        // assets=null → 文件系统模式（方案 B）
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    /** 一次转写整段 PCM（16k mono float），自动检测语言 */
    @Synchronized
    fun transcribe(pcm: FloatArray): String {
        check(!closed) { "Whisper 识别器已关闭" }
        require(pcm.isNotEmpty()) { "没有可供 Whisper 转写的音频样本" }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(pcm, sampleRate = 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            result.lang?.takeIf { it.isNotBlank() }?.let { lastLang = it }
            return result.text
        } finally {
            // sherpa streams own native memory; always release even when decode/result throws.
            runCatching { stream.release() }
        }
    }

    /** 长音频分段转写：30s 窗口 + 1s 重叠，返回带时间轴文本 */
    @Synchronized
    fun transcribeSegmented(
        pcm: FloatArray,
        onProgress: (doneSec: Long, totalSec: Long) -> Unit = { _, _ -> },
    ): List<Pair<Long, String>> {
        check(!closed) { "Whisper 识别器已关闭" }
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
    @Synchronized
    fun transcribeBySegments(
        pcm: FloatArray,
        segs: List<Pair<Long, Long>>,
        checkActive: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Triple<Long, Long, String>> {
        check(!closed) { "Whisper 识别器已关闭" }
        val sr = 16000
        val durationMs = pcm.size.toLong() * 1000L / sr
        val out = mutableListOf<Triple<Long, Long, String>>()
        segs.forEachIndexed { i, (a, b) ->
            // Cancel between native calls. The current sherpa decode itself is synchronous and
            // cannot be preempted safely while native memory is in use.
            checkActive()
            val startMs = a.coerceIn(0L, durationMs)
            val endMs = b.coerceIn(startMs, durationMs)
            val s0 = (startMs * sr / 1000L).toInt()
            val s1 = (endMs * sr / 1000L).toInt()
            if (s1 > s0) {
                val text = transcribe(pcm.copyOfRange(s0, s1)).trim()
                checkActive()
                if (text.isNotEmpty()) out += Triple(startMs, endMs, text)
            }
            onProgress(i + 1, segs.size)
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer.release()
    }
}
