package io.translatetube.local.player

/**
 * Chooses one bounded TTS retry when generated speech is materially longer than its subtitle cue.
 * This keeps timing policy independent of Android classes so it can be unit tested.
 */
internal object DubbingSynthesisPolicy {
    private const val LONG_CUE_RATIO = 1.15
    private const val TARGET_CUE_FILL = 0.90

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
