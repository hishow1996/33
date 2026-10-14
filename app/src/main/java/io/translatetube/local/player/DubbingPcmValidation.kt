package io.translatetube.local.player

import kotlin.math.abs

/** Shared validation for newly synthesized and cached PCM so silent audio is never marked ready. */
internal object DubbingPcmValidation {
    // Keep in sync with DubbingEngine's bounded PCM cache reader.
    const val MAX_SAMPLE_COUNT = 16_000_000
    private const val MIN_AUDIBLE_ABS_SAMPLE = 0.00001f

    fun isAudibleSample(sample: Float): Boolean =
        sample.isFinite() && abs(sample) > MIN_AUDIBLE_ABS_SAMPLE

    fun isSampleCountAllowed(sampleCount: Int): Boolean =
        sampleCount in 1..MAX_SAMPLE_COUNT

    fun isValidAudio(
        sampleRate: Int,
        samples: FloatArray,
        checkActive: () -> Unit = {},
    ): Boolean {
        if (sampleRate !in 8_000..192_000 || !isSampleCountAllowed(samples.size)) return false
        var audible = false
        for (index in samples.indices) {
            // TTS engines can return large PCM arrays. Allow the caller to stop validation if its
            // playback session was replaced instead of scanning a stale buffer to completion.
            if ((index and 0x1fff) == 0) checkActive()
            val sample = samples[index]
            if (!sample.isFinite()) return false
            if (!audible && isAudibleSample(sample)) audible = true
        }
        return audible
    }
}
