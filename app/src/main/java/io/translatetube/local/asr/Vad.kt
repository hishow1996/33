package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream

/** silero VAD 语音分段：全片 PCM → [(startMs, endMs)] */
fun vadSegments(
    ctx: Context,
    pcm: FloatArray,
    sr: Int = 16000,
    checkActive: () -> Unit = {},
): List<Pair<Long, Long>> {
    VadSegmentMergePolicy.requireSupportedSampleRate(sr)
    val vad = newVad(ctx, sr)
    val segments = mutableListOf<Pair<Long, Long>>()
    try {
        val window = 512
        // VAD consumes each window synchronously; reuse one fixed buffer rather than allocating
        // a new FloatArray for every 32 ms window on long tracks.
        val windowSamples = FloatArray(window)
        var offset = 0
        while (offset + window <= pcm.size) {
            checkActive()
            pcm.copyInto(
                windowSamples,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = offset + window,
            )
            vad.acceptWaveform(windowSamples)
            drainVad(vad, sr, segments, pcm.size.toLong())
            offset += window
        }
        if (offset < pcm.size) {
            checkActive()
            windowSamples.fill(0f)
            pcm.copyInto(windowSamples, destinationOffset = 0, startIndex = offset, endIndex = pcm.size)
            vad.acceptWaveform(windowSamples)
            drainVad(vad, sr, segments, pcm.size.toLong())
        }
        checkActive()
        vad.flush()
        drainVad(vad, sr, segments, pcm.size.toLong())
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
    checkActive: () -> Unit = {},
    onProgressPercent: (Int) -> Unit = {},
): List<Pair<Long, Long>> {
    VadSegmentMergePolicy.requireSupportedSampleRate(sr)
    require(pcmFile.sampleRate == sr) { "PCM 文件采样率与 VAD 配置不一致" }
    require(PcmTempFilePolicy.hasCompletePcm16Payload(pcmFile.file, pcmFile.sampleCount)) {
        "PCM 临时文件缺失、为空或长度与声明的采样数不匹配"
    }
    // Fail before initializing the native VAD model when the scratch file is already truncated.
    val vad = newVad(ctx, sr)
    val segments = mutableListOf<Pair<Long, Long>>()
    val window = 512
    val windowBytes = ByteArray(window * 2)
    // Reuse one conversion window for the entire file-backed pass to avoid sustained GC churn.
    val floats = FloatArray(window)
    var lastProgressPercent = -1
    fun reportSampleProgress(processedSamples: Long) {
        val percent = MediaTimelineProgressPolicy.nextSamplePercent(
            processedSamples = processedSamples,
            totalSamples = pcmFile.sampleCount,
            previousPercent = lastProgressPercent,
        ) ?: return
        lastProgressPercent = percent
        onProgressPercent(percent)
    }
    try {
        BufferedInputStream(FileInputStream(pcmFile.file), 32 * 1024).use { input ->
            var remainingSamples = pcmFile.sampleCount
            while (remainingSamples >= window) {
                checkActive()
                readFully(input, windowBytes)
                var byteIndex = 0
                for (sampleIndex in 0 until window) {
                    val raw = (windowBytes[byteIndex].toInt() and 0xff) or
                        ((windowBytes[byteIndex + 1].toInt() and 0xff) shl 8)
                    floats[sampleIndex] = raw.toShort() / 32768f
                    byteIndex += 2
                }
                vad.acceptWaveform(floats)
                drainVad(vad, sr, segments, pcmFile.sampleCount)
                remainingSamples -= window
                reportSampleProgress(pcmFile.sampleCount - remainingSamples)
            }
            if (remainingSamples > 0L) {
                checkActive()
                val tailByteCount = remainingSamples.toInt() * 2
                windowBytes.fill(0)
                readFully(input, windowBytes, tailByteCount)
                var byteIndex = 0
                for (sampleIndex in 0 until window) {
                    val raw = (windowBytes[byteIndex].toInt() and 0xff) or
                        ((windowBytes[byteIndex + 1].toInt() and 0xff) shl 8)
                    floats[sampleIndex] = raw.toShort() / 32768f
                    byteIndex += 2
                }
                vad.acceptWaveform(floats)
                drainVad(vad, sr, segments, pcmFile.sampleCount)
            }
        }
        checkActive()
        vad.flush()
        drainVad(vad, sr, segments, pcmFile.sampleCount)
    } finally {
        runCatching { vad.release() }
    }
    val merged = mergeVadSegments(segments)
    checkActive()
    onProgressPercent(100)
    return merged
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

private fun drainVad(
    vad: Vad,
    sr: Int,
    segments: MutableList<Pair<Long, Long>>,
    maxSamples: Long,
) {
    while (!vad.empty()) {
        val segment = vad.front()
        val startSample = segment.start.toLong()
        val endSample = startSample + segment.samples.size.toLong()
        VadSegmentMergePolicy.clipToSampleBounds(startSample, endSample, maxSamples)?.let { (start, end) ->
            val startMs = VadSegmentMergePolicy.sampleIndexToMillis(start, sr)
            val endMs = VadSegmentMergePolicy.sampleIndexToMillis(end, sr)
            if (endMs > startMs) segments += startMs to endMs
        }
        vad.pop()
    }
}

internal fun readFully(input: InputStream, target: ByteArray, length: Int = target.size) {
    require(length in 0..target.size)
    var offset = 0
    while (offset < length) {
        val count = input.read(target, offset, length - offset)
        if (count < 0) throw java.io.EOFException("PCM 临时文件提前结束")
        if (count == 0) {
            // InputStream is permitted to return zero for a non-empty read. Avoid spinning forever
            // on unusual filesystem/provider behavior by forcing progress with a single-byte read.
            val value = input.read()
            if (value < 0) throw java.io.EOFException("PCM 临时文件提前结束")
            target[offset++] = value.toByte()
        } else {
            offset += count
        }
    }
}

private fun mergeVadSegments(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
    VadSegmentMergePolicy.merge(segments)
