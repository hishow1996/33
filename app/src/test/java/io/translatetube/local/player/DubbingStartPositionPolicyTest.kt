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
    fun refusesRequestedCueWhenLatestSeekMovedBeforeItsStart() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = -1, positionMs = 250L),
            ),
        )
    }

    @Test
    fun positionZeroIsAValidSnapshotAndDoesNotUseStaleRequestedPosition() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4, lineStartMs = 400L, lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = -1, positionMs = 0L),
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
    fun rejectsNegativeOrEmptySubtitleIntervalsEvenWithoutPlayerSnapshot() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 0,
                lineStartMs = -100L,
                lineEndMs = 500L,
                requestedPositionMs = 0L,
                latestCursor = DubbingTimelineCursor(),
            ),
        )
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 0,
                lineStartMs = 400L,
                lineEndMs = 400L,
                requestedPositionMs = 400L,
                latestCursor = DubbingTimelineCursor(),
            ),
        )
    }

    @Test
    fun missingRequestedPositionDefaultsToTheCueStartBeforeFirstSnapshot() {
        assertEquals(
            400L,
            DubbingStartPositionPolicy.choose(
                lineIndex = 4,
                lineStartMs = 400L,
                lineEndMs = 900L,
                requestedPositionMs = null,
                latestCursor = DubbingTimelineCursor(),
            ),
        )
    }

    @Test
    fun rejectsSnapshotExactlyAtCueEnd() {
        assertNull(
            DubbingStartPositionPolicy.choose(
                lineIndex = 4,
                lineStartMs = 400L,
                lineEndMs = 900L,
                requestedPositionMs = 430L,
                latestCursor = DubbingTimelineCursor(lineIndex = 4, positionMs = 900L),
            ),
        )
    }



/** Regression tests for the single-map readiness state used by the 100 ms playback ticker. */
class DubbingReadinessStateTest {
    @Test
    fun readyAudioAndSilentFallbackHaveDistinctStates() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(1, hasAudio = true)
        readiness.markPrepared(2, hasAudio = false)

        assertTrue(readiness.isReady(1))
        assertTrue(readiness.isPrepared(1))
        assertFalse(readiness.isSilent(1))
        assertTrue(readiness.isReady(2))
        assertFalse(readiness.isPrepared(2))
        assertTrue(readiness.isSilent(2))
        assertEquals(2, readiness.readyCount())
    }

    @Test
    fun pendingTransitionRemovesOldAudioAndSilentMarkers() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(1, hasAudio = true)
        readiness.markPending(1)
        assertFalse(readiness.isReady(1))
        assertFalse(readiness.isPrepared(1))
        assertFalse(readiness.isSilent(1))

        readiness.markPrepared(2, hasAudio = false)
        readiness.markPending(2)
        assertFalse(readiness.isReady(2))
    }

    @Test
    fun markingOneCuePendingDoesNotChangeOtherCueState() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(3, hasAudio = true)
        readiness.markPrepared(4, hasAudio = false)
        readiness.markPending(3)

        assertFalse(readiness.isReady(3))
        assertTrue(readiness.isSilent(4))
        assertEquals(1, readiness.readyCount())
    }

    @Test
    fun clearRemovesAllAudioAndSilentMarkers() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(0, hasAudio = true)
        readiness.markPrepared(1, hasAudio = false)
        readiness.clear()

        assertEquals(0, readiness.readyCount())
        assertFalse(readiness.isReady(0))
        assertFalse(readiness.isSilent(1))
    }

    @Test
    fun negativeIndicesDoNotEnterReadinessMap() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(-1, hasAudio = true)
        readiness.markPending(-2)

        assertEquals(0, readiness.readyCount())
        assertFalse(readiness.isReady(-1))
    }
}

}
