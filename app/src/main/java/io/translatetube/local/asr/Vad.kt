package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream

/** silero VAD 语音分段：全片 PCM → [(startMs, endMs)] */
fun vadSegments(ctx: Context, pcm: FloatArray, sr: Int = 16000): List<Pair<Long, Long>> {
    val vad = newVad(ctx, sr)
    val segments = mutableListOf<Pair<Long, Long>>()
    try {
        val window = 512
        var offset = 0
        while (offset + window <= pcm.size) {
            vad.acceptWaveform(pcm.copyOfRange(offset, offset + window))
            drainVad(vad, sr, segments)
            offset += window
        }
        vad.flush()
        drainVad(vad, sr, segments)
    } finally {
        runCatching { vad.release() }
    }
    return mergeVadSegments(segments)
}

/** File-backed VAD: reads one Silero window at a time without allocating the full-track FloatArray. */
fun vadSegmentsFromFile(
    ctx: Context,
    pcmFile: AudioExtract.PcmFile,
    sr: Int = 16000,
): List<Pair<Long, Long>> {
    require(pcmFile.sampleRate == sr) { "PCM 文件采样率与 VAD 配置不一致" }
    val vad = newVad(ctx, sr)
    val segments = mutableListOf<Pair<Long, Long>>()
    val window = 512
    val windowBytes = ByteArray(window * 2)
    try {
        BufferedInputStream(FileInputStream(pcmFile.file), 32 * 1024).use { input ->
            var remainingSamples = pcmFile.sampleCount
            while (remainingSamples >= window) {
                readFully(input, windowBytes)
                val floats = FloatArray(window)
                var byteIndex = 0
                for (sampleIndex in 0 until window) {
                    val raw = (windowBytes[byteIndex].toInt() and 0xff) or
                        ((windowBytes[byteIndex + 1].toInt() and 0xff) shl 8)
                    floats[sampleIndex] = raw.toShort() / 32768f
                    byteIndex += 2
                }
                vad.acceptWaveform(floats)
                drainVad(vad, sr, segments)
                remainingSamples -= window
            }
        }
        vad.flush()
        drainVad(vad, sr, segments)
    } finally {
        runCatching { vad.release() }
    }
    return mergeVadSegments(segments)
}

private fun newVad(ctx: Context, sr: Int): Vad {
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
    return Vad(null, config)
}

private fun drainVad(vad: Vad, sr: Int, segments: MutableList<Pair<Long, Long>>) {
    while (!vad.empty()) {
        val segment = vad.front()
        segments += ((segment.start * 1000L) / sr) to
            (((segment.start + segment.samples.size) * 1000L) / sr)
        vad.pop()
    }
}

private fun readFully(input: InputStream, target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = input.read(target, offset, target.size - offset)
        if (count < 0) throw java.io.EOFException("PCM 临时文件提前结束")
        if (count == 0) continue
        offset += count
    }
}

private fun mergeVadSegments(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
    VadSegmentMergePolicy.merge(segments)
