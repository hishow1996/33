package io.translatetube.local.player

/** Keep invalid gain/sample values from propagating NaN or infinity into the PCM output. */
internal object DubbingGainPolicy {
    /** Invalid gain fails closed to silence; valid gain is constrained to the supported range. */
    fun sanitizeGain(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    /** A malformed PCM sample must never poison the output buffer. */
    fun sanitizePcmSample(value: Float): Float =
        value.takeIf { it.isFinite() } ?: 0f

    /** Apply the live dubbing gate at render time, not only when the PCM queue is read. */
    fun applyDubbingGain(sample: Float, gain: Float, enabled: Boolean): Float =
        if (enabled) sanitizePcmSample(sample) * sanitizeGain(gain) else 0f
}
