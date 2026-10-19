package io.translatetube.local.player

/**
 * Retry synthesis as soon as a cue is meaningfully longer than its real timeline window. A new
 * render is preferred over extreme playback-rate changes. If a provider still returns a long cue,
 * preserve all validated PCM and let the non-pausing timeline scheduler handle the overrun.
 */
internal object DubbingSynthesisPolicy {
    /** Maximum additional renders after the first successful PCM synthesis. */
    const val MAX_ADAPTIVE_SYNTHESIS_RETRIES = 2
    private const val LONG_CUE_RATIO = 1.15
    private const val TARGET_CUE_FILL = 0.90


    /**
     * Keep all audio that passes the global PCM validation limit. The subtitle window is a timing
     * constraint, not a storage limit: cropping the PCM at a multiple of cue duration silently
     * removes the end of spoken sentences. The caller validates generated arrays against the same
     * global sample ceiling before caching them.
     */
    fun maxUsefulSampleCount(
        sampleRate: Int,
        cueDurationMs: Long,
        maxSamples: Int = DubbingPcmValidation.MAX_SAMPLE_COUNT,
    ): Int {
        if (sampleRate !in 8_000..192_000 || cueDurationMs <= 0L || maxSamples <= 0) return 0
        return minOf(maxSamples, DubbingPcmValidation.MAX_SAMPLE_COUNT)
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
