package io.translatetube.local.asr

/** Converts MediaCodec float PCM into the signed 16-bit format used by the streaming resampler. */
internal object Pcm16SampleConverter {
    fun floatToPcm16(sample: Float): Short {
        require(sample.isFinite()) { "PCM 浮点样本包含 NaN 或 Infinity" }
        val clipped = sample.coerceIn(-1.0f, 1.0f)
        return if (clipped <= -1.0f) Short.MIN_VALUE else (clipped * Short.MAX_VALUE).toInt().toShort()
    }
}
