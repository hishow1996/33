package io.translatetube.local.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmChannelMixerTest {
    @Test
    fun monoInputIsPreserved() {
        assertArrayEquals(
            shortArrayOf(-10, 0, 20),
            PcmChannelMixer.downmixToMono(listOf(shortArrayOf(-10, 0, 20)), 1),
        )
    }

    @Test
    fun stereoFramesAreAveragedWithoutChangingDuration() {
        assertArrayEquals(
            shortArrayOf(0, 300),
            PcmChannelMixer.downmixToMono(
                listOf(shortArrayOf(-100, 100), shortArrayOf(200, 400)),
                2,
            ),
        )
    }

    @Test
    fun sixChannelFramesProduceExactlyOneMonoSamplePerFrame() {
        val samples = shortArrayOf(
            600, 0, 0, 0, 0, 0,
            0, 600, 600, 0, 0, 0,
        )
        assertArrayEquals(
            shortArrayOf(100, 200),
            PcmChannelMixer.downmixToMono(listOf(samples), 6),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChunkThatSplitsMultichannelFrame() {
        PcmChannelMixer.downmixToMono(listOf(shortArrayOf(1, 2, 3)), 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedChannelCount() {
        PcmChannelMixer.downmixToMono(listOf(shortArrayOf(1)), 0)
    }
}
