package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DubbingStartPositionPolicyTest {
    @Test
    fun usesFreshTickerPositionAfterCacheLoadDelay() {
        assertEquals(
            650L,
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = 4, positionMs = 650L),
            ),
        )
    }

    @Test
    fun doesNotStartAnOldCueWhenPlaybackHasMovedIntoAGap() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = -1, positionMs = 950L),
            ),
        )
    }

    @Test
    fun doesNotStartAnOldCueAfterNextCueBecomesActive() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = 5, positionMs = 720L),
            ),
        )
    }

    @Test
    fun fallsBackToRequestedPositionBeforeFirstTickerSnapshot() {
        assertEquals(
            430L,
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(),
            ),
        )
    }
    @Test
    fun doesNotRestartAFutureCueAfterBackwardSeekDuringPcmLoad() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 2_000L, lineEndMs = 2_800L,
                requestedPositionMs = 2_050L,
                // User sought back to an earlier caption while this cue was loading.
                latestCursor = DubbingTimelineCursor(lineIndex = 1, positionMs = 650L),
            ),
        )
    }

    @Test
    fun doesNotUseOriginalRequestWhenLiveCursorIsBeforeTheCueEvenIfIndexMatches() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 2_000L, lineEndMs = 2_800L,
                requestedPositionMs = 2_050L,
                latestCursor = DubbingTimelineCursor(lineIndex = 4, positionMs = 1_900L),
            ),
        )
    }

}
