package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSynthesisFailureRecoveryPolicyTest {
    @Test
    fun schedulesOneBackgroundRetryForTransientFailure() {
        assertTrue(
            DubbingSynthesisFailureRecoveryPolicy.shouldScheduleRetry(
                retriesAlreadyScheduled = 0,
                deterministicFailure = false,
                released = false,
            )
        )
    }

    @Test
    fun doesNotRetryDeterministicInputOrSizeFailures() {
        assertFalse(
            DubbingSynthesisFailureRecoveryPolicy.shouldScheduleRetry(
                retriesAlreadyScheduled = 0,
                deterministicFailure = true,
                released = false,
            )
        )
    }

    @Test
    fun doesNotScheduleMoreThanOneBackgroundRetry() {
        assertFalse(
            DubbingSynthesisFailureRecoveryPolicy.shouldScheduleRetry(
                retriesAlreadyScheduled = DubbingSynthesisFailureRecoveryPolicy.MAX_BACKGROUND_RETRIES_PER_CUE,
                deterministicFailure = false,
                released = false,
            )
        )
    }

    @Test
    fun neverSchedulesRetryAfterRelease() {
        assertFalse(
            DubbingSynthesisFailureRecoveryPolicy.shouldScheduleRetry(
                retriesAlreadyScheduled = 0,
                deterministicFailure = false,
                released = true,
            )
        )
    }
}
