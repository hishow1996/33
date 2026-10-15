package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameLayoutTest {
    @Test
    fun boundedChunkSizesNeverSplitChannelFrames() {
        for (channels in 1..8) {
            val remaining = 200_000 - (200_000 % channels)
            var offset = 0
            while (offset < remaining) {
                val count = PcmFrameLayout.boundedChunkSampleCount(
                    remainingSamples = remaining - offset,
                    maxChunkSamples = 64 * 1024,
                    channels = channels,
                )
                assertEquals(true, count in 1..(64 * 1024))
                assertEquals(0, count % channels)
                offset += count
            }
            assertEquals(remaining, offset)
        }
    }

    @Test
    fun acceptsAlignedInterleavedPcmFrames() {
        assertEquals(true, PcmFrameLayout.isValidBuffer(0, 4096, 2, 2))
        assertEquals(true, PcmFrameLayout.isValidBuffer(16, 8192, 4, 2))
        assertEquals(true, PcmFrameLayout.isValidBuffer(4, 128, 4, 1))
    }

    @Test
    fun rejectsBuffersThatSplitSamplesOrChannelFrames() {
        assertEquals(false, PcmFrameLayout.isValidBuffer(1, 16, 2, 1))
        assertEquals(false, PcmFrameLayout.isValidBuffer(0, 3, 2, 1))
        // These offsets align to samples, but split the stereo and 3-channel frames.
        assertEquals(false, PcmFrameLayout.isValidBuffer(2, 8, 2, 2))
        assertEquals(false, PcmFrameLayout.isValidBuffer(4, 12, 2, 3))
        assertEquals(false, PcmFrameLayout.isValidBuffer(0, 6, 2, 2))
        assertEquals(false, PcmFrameLayout.isValidBuffer(-2, 8, 2, 2))
    }

    @Test
    fun rejectsUnsupportedPcmLayouts() {
        assertEquals(false, PcmFrameLayout.isValidBuffer(0, 8, 0, 2))
        assertEquals(false, PcmFrameLayout.isValidBuffer(0, 8, 2, 9))
    }
}
