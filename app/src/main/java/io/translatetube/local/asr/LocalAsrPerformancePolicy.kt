package io.translatetube.local.asr

import android.util.Log
import io.translatetube.local.TranslateTubeApp
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.performance.DevicePerformancePolicy

/**
 * Conservative sherpa-onnx CPU scheduling:
 * - Keep two workers while video playback and dubbing are both enabled.
 * - Let high-headroom devices use up to four SenseVoice workers and three Whisper workers.
 * - Reduce the budget while playback and dubbing are both active or system pressure increases.
 */
internal object LocalAsrPerformancePolicy {
    fun recommendedThreadCount(
        modelId: String,
        processorCount: Int,
        isPlaying: Boolean,
        dubbingEnabled: Boolean,
    ): Int {
        val cores = processorCount.coerceAtLeast(1)
        val dynamic = runCatching {
            DevicePerformanceManager.snapshot(TranslateTubeApp.instance).copy(processorCount = cores)
        }.getOrNull()
        if (dynamic != null) {
            return DevicePerformancePolicy.recommendedAsrThreads(
                dynamic,
                modelId,
                isPlaying || DevicePerformanceManager.isPlaybackLoadActive(),
                dubbingEnabled,
            )
        }
        if (cores <= 2) return 1
        if (modelId == "sensevoice" && cores >= 8 && !(isPlaying && dubbingEnabled)) return 3
        return 2.coerceAtMost(cores)
    }
}

/**
 * Device-side timing for real-world tuning. No audio or transcript content is logged.
 * RTF below 1 means model inference took less time than the audio duration.
 */
internal object LocalAsrPerformanceLogger {
    fun log(
        modelId: String,
        numThreads: Int,
        decodedSegments: Int,
        audioMs: Long,
        sampleReadMs: Long,
        inferenceMs: Long,
        batchMs: Long,
    ) {
        val rtf = if (audioMs > 0L) inferenceMs.toDouble() / audioMs.toDouble() else 0.0
        Log.i(
            "LocalAsrPerf",
            "model=$modelId threads=$numThreads segments=$decodedSegments " +
                "audioMs=$audioMs sampleReadMs=$sampleReadMs inferenceMs=$inferenceMs " +
                "batchMs=$batchMs inferenceRtf=$rtf",
        )
    }
}
