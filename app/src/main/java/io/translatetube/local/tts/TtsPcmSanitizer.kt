package io.translatetube.local.tts

/** Normalize generated PCM in place so invalid samples never reach AudioTrack without copying the buffer. */
internal object TtsPcmSanitizer {
    fun sanitizeInPlace(samples: FloatArray): FloatArray {
        for (index in samples.indices) {
            val value = samples[index]
            samples[index] = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        }
        return samples
    }
}
