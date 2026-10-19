package io.translatetube.local.player

/**
 * Retry synthesis as soon as a cue is meaningfully longer than its real timeline window. A new
 * render is preferred over aggressive playback-rate changes; if the provider cannot shorten it
 * enough, DubbingTiming preserves the remaining PCM tail and PlaybackService holds video safely.
 */
internal object DubbingSynthesisPolicy {
    private const val LONG_CUE_RATIO = 1.15
    private const val TARGET_CUE_FILL = 0.90
    // This is a storage/PCM safety bound, not the normal synchronization playback rate. A cue that
    // exceeds 1.35x is allowed to finish while video is held, so preserve a larger bounded tail.
    private const val MAX_CACHED_CUE_DURATION_RATIO = 6.0

    /**
     * Retain long-cue tails that may finish while the video is held. Bound the cache payload to a
     * multiple of the subtitle window and the global PCM sample ceiling to protect device memory.
     */
    fun maxUsefulSampleCount(
        sampleRate: Int,
        cueDurationMs: Long,
        maxSamples: Int = DubbingPcmValidation.MAX_SAMPLE_COUNT,
    ): Int {
        if (sampleRate !in 8_000..192_000 || cueDurationMs <= 0L || maxSamples <= 0) return 0
        val useful = kotlin.math.ceil(
            cueDurationMs.toDouble() * sampleRate.toDouble() *
                MAX_CACHED_CUE_DURATION_RATIO / 1_000.0
        )
        if (!useful.isFinite() || useful >= maxSamples.toDouble()) return maxSamples
        return useful.toInt().coerceIn(1, maxSamples)
    }

    fun recommendedSpeed(
        baseSpeed: Float,
        sampleCount: Int,
        sampleRate: Int,
        cueDurationMs: Long,
    ): Float {
        val base = baseSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 3.0f) ?: 1.0f
        if (sampleCount <= 0 || sampleRate !in 8_000..192_000 || cueDurationMs <= 0L) return base

        val audioDurationMs = sampleCount.toDouble() * 1_000.0 / sampleRate
        val cueMs = cueDurationMs.toDouble()
        if (!audioDurationMs.isFinite() || audioDurationMs <= cueMs * LONG_CUE_RATIO) return base

        // Estimate a useful retry rate, leaving a little room for natural pauses. Provider output
        // may not scale linearly, so the caller accepts the retry only if it really is shorter.
        val estimate = base.toDouble() * audioDurationMs / (cueMs * TARGET_CUE_FILL)
        return estimate.coerceIn(base.toDouble(), 3.0).toFloat()
    }
}
