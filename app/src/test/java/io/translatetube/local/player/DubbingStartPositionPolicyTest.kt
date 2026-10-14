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
}
