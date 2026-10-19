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
        assertEquals(1.55f, DubbingRecoveryRatePolicy.rateForQueueDepth(5), 0.001f)
        assertEquals(2.1f, DubbingRecoveryRatePolicy.rateForQueueDepth(10), 0.001f)
    }

    @Test
    fun extremeBacklogsStayWithinTheCatchUpCeiling() {
        assertEquals(
            DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
            DubbingRecoveryRatePolicy.rateForQueueDepth(100),
            0.001f,
        )
    }
}
