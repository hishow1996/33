package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackWritePolicyTest {
    @Test
    fun retriesTemporaryZeroProgressAndResumesAtTheExactOffset() {
        val results = ArrayDeque(listOf(0, 0, 2, 0, 2))
        val requests = mutableListOf<Pair<Int, Int>>()
        val written = AudioTrackWritePolicy.writeFully(
            sampleCount = 4,
            write = { offset, remaining ->
                requests += offset to remaining
                results.removeFirst()
            },
            pauseAfterZeroProgress = {},
        )

        assertEquals(4, written)
        assertEquals(
            listOf(0 to 4, 0 to 4, 0 to 4, 2 to 2, 2 to 2),
            requests,
        )
    }

    @Test
    fun stopsAfterBoundedZeroProgressInsteadOfSpinningForever() {
        var attempts = 0
        val written = AudioTrackWritePolicy.writeFully(
            sampleCount = 8,
            write = { _, _ -> attempts++; 0 },
            pauseAfterZeroProgress = {},
        )

        assertEquals(0, written)
        assertEquals(AudioTrackWritePolicy.MAX_ZERO_PROGRESS_RETRIES + 1, attempts)
    }

    @Test
    fun propagatesAudioTrackErrorsAndRejectsImpossibleWriteCounts() {
        assertEquals(
            -6,
            AudioTrackWritePolicy.writeFully(4, { _, _ -> -6 }, pauseAfterZeroProgress = {}),
        )
        assertEquals(
            0,
            AudioTrackWritePolicy.writeFully(4, { _, _ -> 5 }, pauseAfterZeroProgress = {}),
        )
    }

    @Test
    fun completesWhenThereIsNothingToWrite() {
        var called = false
        assertEquals(0, AudioTrackWritePolicy.writeFully(0, { _, _ -> called = true; 0 }))
        assertTrue(!called)
    }
}
