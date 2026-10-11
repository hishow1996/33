package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** silero VAD 语音分段：全片 PCM → [(startMs, endMs)] */
fun vadSegments(ctx: Context, pcm: FloatArray, sr: Int = 16000): List<Pair<Long, Long>> {
    val config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = io.translatetube.local.data.ModelFiles.asrDir(ctx).absolutePath + "/silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = 0.3f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 12f,
        ),
        sampleRate = sr,
        numThreads = 1,
        provider = "cpu",
        debug = false,
    )
    val vad = Vad(null, config)
    val segs = mutableListOf<Pair<Long, Long>>()
    try {
        val window = 512
        var i = 0
        while (i + window <= pcm.size) {
            vad.acceptWaveform(pcm.copyOfRange(i, i + window))
            while (!vad.empty()) {
                val s = vad.front()
                segs += ((s.start * 1000L) / sr) to (((s.start + s.samples.size) * 1000L) / sr)
                vad.pop()
            }
            i += window
        }
        vad.flush()
        while (!vad.empty()) {
            val s = vad.front()
            segs += ((s.start * 1000L) / sr) to (((s.start + s.samples.size) * 1000L) / sr)
            vad.pop()
        }
    } finally {
        // VAD 推理异常时也释放 native 资源。
        runCatching { vad.release() }
    }
    // 过滤极短段 & 合并间隔 <0.4s 的相邻段
    val merged = mutableListOf<Pair<Long, Long>>()
    for ((a, b) in segs) {
        if (b - a < 300) continue
        val last = merged.lastOrNull()
        if (last != null && a - last.second < 400) {
            merged[merged.size - 1] = last.first to b
        } else merged += a to b
    }
    return merged
}
