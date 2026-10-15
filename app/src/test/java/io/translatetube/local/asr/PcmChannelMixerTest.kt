package io.translatetube.local.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun bulkDownmixChecksCancellationAndRejectsOversizedAggregateBeforeAllocation() {
        val repeatedChunk = ShortArray(8_000_000)
        val oversizedError = runCatching {
            // Reuse the same backing array; the aggregate exceeds the budget without allocating
            // a second large input buffer and must be rejected before allocating its output.
            PcmChannelMixer.downmixToMono(listOf(repeatedChunk, repeatedChunk, repeatedChunk), 2)
        }.exceptionOrNull()
        assertTrue(oversizedError is IllegalArgumentException)

        var checks = 0
        val error = runCatching {
            PcmChannelMixer.downmixToMono(listOf(ShortArray(100_000)), 1) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException("stale downmix")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(3, checks)
    }

    @Test
    fun largeDownmixChecksCancellationBetweenOutputChunks() {
        var checks = 0
        val error = runCatching {
            PcmChannelMixer.downmixChunk(ShortArray(100_000), 2) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException("stale decode")
            }
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(3, checks)
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
