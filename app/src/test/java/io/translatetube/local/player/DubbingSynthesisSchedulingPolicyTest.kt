package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSynthesisSchedulingPolicyTest {
    @Test
    fun forwardMovementInsidePromotedWindowDoesNotRequireAnotherFullReorder() {
        assertFalse(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 16,
                queueWindowStart = 10,
                currentLookAhead = 8,
                requestedLookAhead = 4,
            ),
        )
        assertFalse(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 18,
                queueWindowStart = 10,
                currentLookAhead = 8,
                requestedLookAhead = 8,
            ),
        )
    }

    @Test
    fun refreshesAfterWindowIsPassedOrPlayheadSeeksBackward() {
        assertTrue(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 19,
                queueWindowStart = 10,
                currentLookAhead = 8,
                requestedLookAhead = 8,
            ),
        )
        assertTrue(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 9,
                queueWindowStart = 10,
                currentLookAhead = 8,
                requestedLookAhead = 2,
            ),
        )
    }

    @Test
    fun expandingLookAheadRefreshesEvenWhilePlayheadIsInsideWindow() {
        assertTrue(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 12,
                queueWindowStart = 10,
                currentLookAhead = 4,
                requestedLookAhead = 7,
            ),
        )
    }

    @Test
    fun missingWindowRefreshesAndLargeIndexesDoNotOverflow() {
        assertTrue(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = 0,
                queueWindowStart = -1,
                currentLookAhead = 2,
                requestedLookAhead = 2,
            ),
        )
        assertFalse(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = Int.MAX_VALUE - 1,
                queueWindowStart = Int.MAX_VALUE - 2,
                currentLookAhead = 2,
                requestedLookAhead = 2,
            ),
        )
        assertFalse(
            DubbingSynthesisSchedulingPolicy.shouldRefreshPriorityWindow(
                currentIndex = -1,
                queueWindowStart = -1,
                currentLookAhead = 2,
                requestedLookAhead = 2,
            ),
        )
    }

    @Test
    fun deferredWorkerUsesAOneSecondFallbackInsteadOfRapidPolling() {
        assertEquals(1_000L, DubbingSynthesisSchedulingPolicy.DEFER_RECHECK_DELAY_MS)
    }

    @Test
    fun keepsCurrentAndUpcomingCuesEligibleDuringPlayback() {
        assertFalse(DubbingSynthesisSchedulingPolicy.shouldDefer(10, 10, 4, true))
        assertFalse(DubbingSynthesisSchedulingPolicy.shouldDefer(14, 10, 4, true))
    }

    @Test
    fun defersDistantFutureAndBehindPlayheadCuesDuringPlayback() {
        assertTrue(DubbingSynthesisSchedulingPolicy.shouldDefer(15, 10, 4, true))
        assertTrue(DubbingSynthesisSchedulingPolicy.shouldDefer(9, 10, 4, true))
    }

    @Test
    fun doesNotDeferWhenPlaybackIsInactiveOrPlayheadIsUnknown() {
        assertFalse(DubbingSynthesisSchedulingPolicy.shouldDefer(100, 10, 2, false))
        assertFalse(DubbingSynthesisSchedulingPolicy.shouldDefer(100, -1, 2, true))
    }

    @Test
    fun negativeLookAheadDoesNotPromoteExtraCues() {
        assertFalse(DubbingSynthesisSchedulingPolicy.shouldDefer(10, 10, -1, true))
        assertTrue(DubbingSynthesisSchedulingPolicy.shouldDefer(11, 10, -1, true))
    }
}
