package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingRetryQueuePolicyTest {
    @Test
    fun cacheMissForCurrentlySynthesizingCueIsDeferred() {
        assertEquals(
            DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES,
            DubbingRetryQueuePolicy.decide(index = 5, activeIndex = 5, alreadyQueued = false),
        )
    }

    @Test
    fun cacheMissDoesNotDuplicateAnAlreadyQueuedCue() {
        assertEquals(
            DubbingRetryQueueDecision.IGNORE,
            DubbingRetryQueuePolicy.decide(index = 5, activeIndex = -1, alreadyQueued = true),
        )
    }

    @Test
    fun missingCueIsEnqueuedWhenNotActiveOrAlreadyQueued() {
        assertEquals(
            DubbingRetryQueueDecision.ENQUEUE,
            DubbingRetryQueuePolicy.decide(index = 5, activeIndex = 2, alreadyQueued = false),
        )
    }

    @Test
    fun invalidIndexIsNeverAddedToRetryQueue() {
        assertEquals(
            DubbingRetryQueueDecision.IGNORE,
            DubbingRetryQueuePolicy.decide(index = -1, activeIndex = -1, alreadyQueued = false),
        )
    }

    @Test
    fun anActiveCueIsDeferredEvenIfItWasAlsoQueuedAgain() {
        assertEquals(
            DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES,
            DubbingRetryQueuePolicy.decide(index = 5, activeIndex = 5, alreadyQueued = true),
        )
    }
}
