package io.translatetube.local.asr

/** Validates interleaved PCM byte buffers before channel mixing or sample conversion. */
internal object PcmFrameLayout {
    /**
     * Choose a bounded number of interleaved source samples without splitting a channel frame.
     * [remainingSamples] must already describe complete frames from a validated codec buffer.
     */
    fun boundedChunkSampleCount(
        remainingSamples: Int,
        maxChunkSamples: Int,
        channels: Int,
    ): Int {
        require(remainingSamples >= 0) { "剩余 PCM 样本数不能为负数" }
        require(maxChunkSamples > 0) { "PCM 分块上限必须为正数" }
        require(channels in 1..8) { "不支持的 PCM 声道数：$channels" }
        require(remainingSamples % channels == 0) { "剩余 PCM 样本数未按声道帧对齐" }
        if (remainingSamples == 0) return 0
        val alignedLimit = maxChunkSamples - maxChunkSamples % channels
        require(alignedLimit > 0) { "PCM 分块上限小于一个完整声道帧" }
        return minOf(remainingSamples, alignedLimit)
    }

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
