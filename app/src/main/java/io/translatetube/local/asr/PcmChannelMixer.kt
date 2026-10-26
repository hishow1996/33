package io.translatetube.local.asr

/** Pure PCM16 channel mixer, kept separate so multichannel handling can be tested on the JVM. */
internal object PcmChannelMixer {
    // Bulk conversion is only for short analysis windows; long recordings must use downmixChunk.
    internal const val MAX_BULK_MONO_SAMPLES = 8 * 1024 * 1024
    fun downmixChunk(
        chunk: ShortArray,
        channels: Int,
        checkActive: () -> Unit = {},
    ): ShortArray {
        require(channels in 1..8) { "Unsupported PCM channel count: $channels" }
        require(chunk.size % channels == 0) { "PCM chunk splits a multichannel frame" }
        checkActive()
        if (channels == 1) return chunk.copyOf()

        val mono = ShortArray(chunk.size / channels)
        if (channels == 2) {
            // Stereo is by far the common case; plain Int math (the sum of two shorts cannot
            // overflow and the average cannot leave the Short range) gives the same result.
            var outputIndex = 0
            var offset = 0
            while (offset < chunk.size) {
                if ((outputIndex and 0x1fff) == 0) checkActive()
                mono[outputIndex++] = ((chunk[offset] + chunk[offset + 1]) / 2).toShort()
                offset += 2
            }
            return mono
        }
        var outputIndex = 0
        var offset = 0
        while (offset < chunk.size) {
            if ((outputIndex and 0x1fff) == 0) checkActive()
            var sum = 0L
            for (channel in 0 until channels) sum += chunk[offset + channel].toLong()
            mono[outputIndex++] = (sum / channels)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
            offset += channels
        }
        return mono
    }

    fun downmixToMono(
        chunks: Iterable<ShortArray>,
        channels: Int,
        checkActive: () -> Unit = {},
    ): ShortArray {
        require(channels in 1..8) { "Unsupported PCM channel count: $channels" }
        var totalSamples = 0L
        for (chunk in chunks) {
            checkActive()
            require(totalSamples <= Long.MAX_VALUE - chunk.size.toLong()) { "PCM sample count is too large" }
            totalSamples += chunk.size.toLong()
        }
        require(totalSamples % channels == 0L) { "PCM data does not contain complete channel frames" }
        val frameCount = totalSamples / channels
        require(frameCount <= MAX_BULK_MONO_SAMPLES.toLong()) {
            "批量混音超过 ${MAX_BULK_MONO_SAMPLES} 个单声道采样点，请改用分块混音以控制内存"
        }

        checkActive()
        val mono = ShortArray(frameCount.toInt())
        var outputIndex = 0
        for (chunk in chunks) {
            checkActive()
            require(chunk.size % channels == 0) { "PCM chunk splits a multichannel frame" }
            var offset = 0
            while (offset < chunk.size) {
                if ((outputIndex and 0x1fff) == 0) checkActive()
                var sum = 0L
                for (channel in 0 until channels) {
                    sum += chunk[offset + channel].toLong()
                }
                mono[outputIndex++] = (sum / channels)
                    .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                    .toShort()
                offset += channels
            }
        }
        return mono
    }
}
