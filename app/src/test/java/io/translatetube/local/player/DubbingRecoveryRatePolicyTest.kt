package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingRecoveryRatePolicyTest {
    @Test
    fun noBacklogKeepsNaturalRate() {
        assertEquals(1.0f, DubbingRecoveryRatePolicy.rateForQueueDepth(0), 0.001f)
        assertEquals(1.0f, DubbingRecoveryRatePolicy.rateForQueueDepth(-2), 0.001f)
    }

    @Test
    fun rateRisesGraduallyWithTheNumberOfQueuedCues() {
        assertEquals(1.11f, DubbingRecoveryRatePolicy.rateForQueueDepth(1), 0.001f)
        assertEquals(DubbingTiming.LAG_RECOVERY_SYNC_RATE, DubbingRecoveryRatePolicy.rateForQueueDepth(5), 0.001f)
        assertEquals(DubbingTiming.LAG_RECOVERY_SYNC_RATE, DubbingRecoveryRatePolicy.rateForQueueDepth(10), 0.001f)
    }

    @Test
    fun actualTimelineDebtRaisesRecoveryRateEvenWithOneQueuedCue() {
        assertEquals(1.0f, DubbingRecoveryRatePolicy.rateForBacklog(0, -100L), 0.001f)
        assertEquals(1.5f, DubbingRecoveryRatePolicy.rateForBacklog(0, 5_000L), 0.001f)
        assertEquals(1.5f, DubbingRecoveryRatePolicy.rateForBacklog(5, 5_000L), 0.001f)
        assertEquals(1.3f, DubbingRecoveryRatePolicy.rateForBacklog(0, 3_000L), 0.001f)
    }

    @Test
    fun rareLargeLagMayUseTheHigherCeilingButNothingBeyondIt() {
        assertEquals(1.7f, DubbingRecoveryRatePolicy.rateForBacklog(0, 8_000L), 0.001f)
        assertEquals(1.7f, DubbingRecoveryRatePolicy.rateForBacklog(10, 11_000L), 0.001f)
        assertEquals(1.5f, DubbingRecoveryRatePolicy.rateForBacklog(10, 5_000L), 0.001f)
    }

    @Test
    fun catchUpKeepsGoingBelowTheToleranceUntilTheLagIsClosed() {
        val tracker = DubbingLagTracker()
        assertEquals(false, tracker.update(1_500L))
        assertEquals(true, tracker.update(2_500L))
        // Already catching up: 1.2s of lag is still repaid, with a real (not ~1.0x) rate.
        assertEquals(true, tracker.update(1_200L))
        val rate = DubbingRecoveryRatePolicy.rateForBacklog(0, 1_200L, catchingUp = true)
        assertEquals(DubbingTiming.MIN_ACTIVE_CATCH_UP_RATE, rate, 0.001f)
        // Closed: back under the resume threshold, normal speed again and it does not restart at 1.5s.
        assertEquals(false, tracker.update(700L))
        assertEquals(1.0f, DubbingRecoveryRatePolicy.rateForBacklog(0, 700L, catchingUp = true), 0.001f)
        assertEquals(false, tracker.update(1_500L))
    }

    @Test
    fun lateCuesWithinTheToleranceAreNotSpedUp() {
        assertEquals(1.0f, DubbingRecoveryRatePolicy.rateForBacklog(8, 1_999L), 0.001f)
    }

    @Test
    fun extremeBacklogsStayWithinTheCatchUpCeiling() {
        assertEquals(
            DubbingTiming.LAG_RECOVERY_SYNC_RATE,
            DubbingRecoveryRatePolicy.rateForQueueDepth(100),
            0.001f,
        )
    }
}
