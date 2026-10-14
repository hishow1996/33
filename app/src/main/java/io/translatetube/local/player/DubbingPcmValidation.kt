package io.translatetube.local.player

import kotlin.math.abs

/** Shared validation for newly synthesized and cached PCM so silent audio is never marked ready. */
internal object DubbingPcmValidation {
    // Keep in sync with DubbingEngine's bounded PCM cache reader.
    const val MAX_SAMPLE_COUNT = 16_000_000
    private const val MIN_AUDIBLE_ABS_SAMPLE = 0.00001f

    fun isAudibleSample(sample: Float): Boolean =
        sample.isFinite() && abs(sample) > MIN_AUDIBLE_ABS_SAMPLE

    fun isValidAudio(sampleRate: Int, samples: FloatArray): Boolean {
        if (sampleRate !in 8_000..192_000 || samples.isEmpty() || samples.size > MAX_SAMPLE_COUNT) return false
        var audible = false
        for (sample in samples) {
            if (!sample.isFinite()) return false
            if (!audible && isAudibleSample(sample)) audible = true
        }
        return audible
    }
}
