package io.translatetube.local.player

internal data class DubbingOutputPcm(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * Prepares TTS PCM for the dubbing mix bus.
 *
 * The bus resamples every cue to the audio sink's rate exactly once, so the PCM must keep the
 * TTS engine's native sample rate. An earlier version down-sampled 24 kHz Kokoro/Edge output to
 * 16 kHz first: with no low-pass filter that folds high frequencies back into the voice (muffled,
 * slightly "buzzy" speech) and then forced a second lossy up-sample to 44.1/48 kHz.
 *
 * Only validation and sanitizing happen here; the output is always mono float in [-1, 1].
 */
internal object DubbingOutputPcmPolicy {
    private const val MIN_SUPPORTED_RATE = 8_000
    private const val MAX_SUPPORTED_RATE = 192_000

    fun normalize(
        samples: FloatArray,
        sampleRate: Int,
        checkActive: () -> Unit = {},
    ): DubbingOutputPcm {
        checkActive()
        require(sampleRate in MIN_SUPPORTED_RATE..MAX_SUPPORTED_RATE) {
            "不支持的配音采样率：${sampleRate} Hz"
        }
        if (samples.isEmpty()) return DubbingOutputPcm(samples, sampleRate)

        // Normal TTS output is already clean: reuse the cached array instead of copying it.
        // NaN/Infinity or out-of-range floats must never reach the mixer, so those get a clean copy.
        var needsSanitizing = false
        for (index in samples.indices) {
            if ((index and 0x1fff) == 0) checkActive()
            val sample = samples[index]
            if (!sample.isFinite() || sample < -1.0f || sample > 1.0f) {
                needsSanitizing = true
                break
            }
        }
        if (!needsSanitizing) return DubbingOutputPcm(samples, sampleRate)
        val sanitized = FloatArray(samples.size) { index ->
            if ((index and 0x1fff) == 0) checkActive()
            val sample = samples[index]
            if (sample.isFinite()) sample.coerceIn(-1.0f, 1.0f) else 0.0f
        }
        return DubbingOutputPcm(sanitized, sampleRate)
    }
}
