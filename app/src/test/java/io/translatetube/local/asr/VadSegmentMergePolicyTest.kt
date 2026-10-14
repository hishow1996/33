package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class VadSegmentMergePolicyTest {
    @Test
    fun continuousSpeechCannotMergeIntoAnOversizedWhisperSegment() {
        val segments = listOf(
            0L to 12_000L,
            12_000L to 24_000L,
            24_000L to 36_000L,
            36_000L to 48_000L,
        )

        assertEquals(
            listOf(0L to 24_000L, 24_000L to 48_000L),
            VadSegmentMergePolicy.merge(segments),
        )
    }

    @Test
    fun aSingleOversizedVadSpanIsSplitIntoWhisperSafePieces() {
        assertEquals(
            listOf(0L to 25_000L, 25_000L to 50_000L, 50_000L to 60_000L),
            VadSegmentMergePolicy.merge(listOf(0L to 60_000L)),
        )
    }

    @Test
    fun nearbyCuesMergeOnlyWhenTheCombinedDurationStaysWithinLimit() {
        val segments = listOf(
            0L to 10_000L,
            10_300L to 21_000L,
            21_200L to 29_000L,
        )

        assertEquals(
            listOf(0L to 21_000L, 21_200L to 29_000L),
            VadSegmentMergePolicy.merge(segments),
        )
    }

    @Test
    fun longPausesRemainSeparateAndTinySegmentsAreDiscarded() {
        val segments = listOf(
            0L to 200L,
            400L to 1_000L,
            1_399L to 2_000L,
            2_500L to 3_100L,
        )

        assertEquals(
            listOf(400L to 2_000L, 2_500L to 3_100L),
            VadSegmentMergePolicy.merge(segments),
        )
    }

    @Test
    fun paddedFinalWindowIsClippedToTheOriginalAudioLength() {
        assertEquals(9_900L to 10_000L, VadSegmentMergePolicy.clipToSampleBounds(9_900, 10_400, 10_000))
    }

    @Test
    fun ignoresInvalidOrOutOfRangeVadWindows() {
        assertEquals(null, VadSegmentMergePolicy.clipToSampleBounds(-1, 20, 100))
        assertEquals(null, VadSegmentMergePolicy.clipToSampleBounds(100, 120, 100))
        assertEquals(null, VadSegmentMergePolicy.clipToSampleBounds(10, 10, 100))
    }

    @Test
    fun everyMergedSegmentRespectsWhisperSafeDuration() {
        val segments = (0..20).map { index ->
            val start = index * 12_000L
            start to start + 12_000L
        }

        assertEquals(
            true,
            VadSegmentMergePolicy.merge(segments).all { (start, end) ->
                end - start <= VadSegmentMergePolicy.MAX_MERGED_SEGMENT_MS
            },
        )
    }
}
