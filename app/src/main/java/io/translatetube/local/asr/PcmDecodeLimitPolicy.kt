package io.translatetube.local.asr

/** Computes a duration bound from the PCM format actually emitted by MediaCodec. */
internal object PcmDecodeLimitPolicy {
    fun maxSourceSamples(sampleRate: Int, channels: Int, maxDurationMs: Long): Long {
        require(sampleRate in 1_000..384_000) { "不支持的输入采样率：${sampleRate}" }
        require(channels in 1..8) { "不支持的音频声道数：${channels}" }
        require(maxDurationMs >= 0L) { "音频解码时长上限不能为负数" }

        val samplesPerSecond = sampleRate.toLong() * channels.toLong()
        // Keep the same representable hard bound for unlimited or extremely long requests.
        // Check before multiplying so a large finite duration cannot overflow Long and
        // accidentally collapse the allowed decode length to a single channel frame.
        val rawLimit = if (
            maxDurationMs == Long.MAX_VALUE ||
            maxDurationMs > Long.MAX_VALUE / samplesPerSecond
        ) {
            Int.MAX_VALUE.toLong()
        } else {
            (samplesPerSecond * maxDurationMs / 1_000L)
                .coerceAtLeast(channels.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return rawLimit - rawLimit % channels
    }
}
