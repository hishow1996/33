package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSynthesisSchedulingPolicyTest {
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
