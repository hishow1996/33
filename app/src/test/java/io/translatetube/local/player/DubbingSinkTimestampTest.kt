package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingSinkTimestampTest {
    private val firstPeriodOffsetUs = 1_000_000_000_000L

    @Test
    fun removesFirstPeriodRendererOffsetSoSinkTimeMatchesSubtitleTime() {
        // 12.5 s into the media: ExoPlayer reports 10^12 us + 12.5 s to the audio sink.
        val mediaUs = DubbingSinkTimestamp.toMediaTimeUs(
            presentationTimeUs = firstPeriodOffsetUs + 12_500_000L,
            outputStreamOffsetUs = firstPeriodOffsetUs,
        )
        assertEquals(12_500_000L, mediaUs)
    }

    @Test
    fun followsLaterStreamOffsetsFromPlaylistTransitions() {
        val secondOffsetUs = firstPeriodOffsetUs + 90_000_000L
        val mediaUs = DubbingSinkTimestamp.toMediaTimeUs(
            presentationTimeUs = secondOffsetUs + 1_000_000L,
            outputStreamOffsetUs = secondOffsetUs,
        )
        assertEquals(1_000_000L, mediaUs)
    }

    @Test
    fun zeroOffsetLeavesTimestampUntouched() {
        assertEquals(
            3_000_000L,
            DubbingSinkTimestamp.toMediaTimeUs(presentationTimeUs = 3_000_000L, outputStreamOffsetUs = 0L),
        )
    }

    @Test
    fun smallNegativeStartIsTreatedAsTimeZero() {
        assertEquals(
            0L,
            DubbingSinkTimestamp.toMediaTimeUs(
                presentationTimeUs = firstPeriodOffsetUs - 23_000L,
                outputStreamOffsetUs = firstPeriodOffsetUs,
            ),
        )
    }

    @Test
    fun implausibleOrUnsetTimestampsAreRejected() {
        val unset = Long.MIN_VALUE + 1
        assertEquals(
            DubbingSinkTimestamp.INVALID_US,
            DubbingSinkTimestamp.toMediaTimeUs(presentationTimeUs = unset, outputStreamOffsetUs = firstPeriodOffsetUs),
        )
        assertEquals(
            DubbingSinkTimestamp.INVALID_US,
            DubbingSinkTimestamp.toMediaTimeUs(presentationTimeUs = 5_000_000L, outputStreamOffsetUs = unset),
        )
        // Far before the stream start: the offset is clearly wrong, don't guess.
        assertEquals(
            DubbingSinkTimestamp.INVALID_US,
            DubbingSinkTimestamp.toMediaTimeUs(
                presentationTimeUs = 1_000_000L,
                outputStreamOffsetUs = firstPeriodOffsetUs,
            ),
        )
    }
}
