package io.translatetube.local.tts

/** Normalize generated PCM in place so invalid samples never reach AudioTrack without copying the buffer. */
internal object TtsPcmSanitizer {
    private const val CHECK_MASK = 0x1fff

    fun sanitizeInPlace(samples: FloatArray, checkActive: () -> Unit = {}): FloatArray {
        checkActive()
        for (index in samples.indices) {
            if ((index and CHECK_MASK) == 0) checkActive()
            val value = samples[index]
            samples[index] = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        }
        checkActive()
        return samples
    }
}
