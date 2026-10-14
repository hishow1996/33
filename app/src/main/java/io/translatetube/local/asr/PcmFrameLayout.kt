package io.translatetube.local.asr

/** Validates interleaved PCM byte buffers before channel mixing or sample conversion. */
internal object PcmFrameLayout {
    fun isValidBuffer(
        offsetBytes: Int,
        sizeBytes: Int,
        bytesPerSample: Int,
        channels: Int,
    ): Boolean {
        if (offsetBytes < 0 || sizeBytes < 0) return false
        if (bytesPerSample !in 1..4 || channels !in 1..8) return false
        if (offsetBytes % bytesPerSample != 0) return false
        val bytesPerFrame = bytesPerSample * channels
        return sizeBytes % bytesPerFrame == 0
    }
}
