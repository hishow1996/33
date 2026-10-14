package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmentPolicyTest {
    @Test
    fun mergesShortGapsButStartsANewWindowBeforeMaximumDurationIsExceeded() {
        val result = VadSegmentPolicy.merge(
            listOf(
                0L to 12_000L,
                12_200L to 24_200L,
                24_400L to 36_400L,
                36_600L to 48_600L,
            )
        )

        assertEquals(
            listOf(0L to 24_200L, 24_400L to 48_600L),
            result,
        )
        assertTrue(result.all {
            it.second - it.first <= VadSegmentPolicy.MAX_SEGMENT_DURATION_MS
        })
    }

    @Test
    fun splitsUnexpectedOversizedVadWindowWithoutDroppingSamplesFromItsTimeline() {
        val result = VadSegmentPolicy.merge(listOf(1_000L to 62_000L))

        assertEquals(3, result.size)
        assertEquals(1_000L to 21_334L, result[0])
        assertEquals(21_334L to 41_667L, result[1])
        assertEquals(41_667L to 62_000L, result[2])
        assertEquals(61_000L, result.sumOf { it.second - it.first })
        assertTrue(result.all {
            it.second - it.first <= VadSegmentPolicy.MAX_SEGMENT_DURATION_MS
        })
    }

    @Test
    fun sortsInputsAndDropsInvalidOrTooShortSpeechWindows() {
        val result = VadSegmentPolicy.merge(
            listOf(
                5_000L to 6_000L,
                0L to 299L,
                1_000L to 2_000L,
                9_000L to 8_000L,
                -1L to 500L,
            )
        )

        assertEquals(listOf(1_000L to 2_000L, 5_000L to 6_000L), result)
    }

    @Test
    fun joinsOverlappingOrCloselySpacedWindowsOnlyWithinTheDurationLimit() {
        val result = VadSegmentPolicy.merge(
            listOf(
                0L to 1_000L,
                900L to 2_000L,
                2_100L to 3_000L,
            )
        )

        assertEquals(listOf(0L to 3_000L), result)
    }
}
