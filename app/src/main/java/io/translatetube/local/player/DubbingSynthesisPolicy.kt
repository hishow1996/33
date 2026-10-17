package io.translatetube.local.player

/**
 * Only pay for a second full TTS pass when the audio cannot reasonably fit inside its subtitle
 * window at the playback engine's maximum synchronization rate. This policy is shared by
 * Kokoro, Piper and System TTS, so ordinary overlong cues are sped up on the playback timeline
 * instead of being synthesized twice.
 */
internal object DubbingSynthesisPolicy {
    private const val LONG_CUE_RATIO = 2.0
    private const val TARGET_CUE_FILL = 0.90

    /**
     * Audio beyond the subtitle window multiplied by the maximum sync rate can never be played.
     * Avoid caching that unused tail on long videos; a caller still keeps the original if the
     * bounded prefix is not valid audible PCM.
     */
    fun maxUsefulSampleCount(
        sampleRate: Int,
        cueDurationMs: Long,
        maxSamples: Int = DubbingPcmValidation.MAX_SAMPLE_COUNT,
    ): Int {
        if (sampleRate !in 8_000..192_000 || cueDurationMs <= 0L || maxSamples <= 0) return 0
        val useful = kotlin.math.ceil(
            cueDurationMs.toDouble() * sampleRate.toDouble() *
                DubbingTiming.MAX_SYNC_RATE.toDouble() / 1_000.0
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
        val base = baseSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        if (sampleCount <= 0 || sampleRate !in 8_000..192_000 || cueDurationMs <= 0L) return base

        val audioDurationMs = sampleCount.toDouble() * 1_000.0 / sampleRate
        val cueMs = cueDurationMs.toDouble()
        if (!audioDurationMs.isFinite() || audioDurationMs <= cueMs * LONG_CUE_RATIO) return base

        // Estimate a useful retry rate, leaving a little room for natural pauses. Provider output
        // may not scale linearly, so the caller accepts the retry only if it really is shorter.
        val estimate = base.toDouble() * audioDurationMs / (cueMs * TARGET_CUE_FILL)
        return estimate.coerceIn(base.toDouble(), 2.0).toFloat()
    }
}
