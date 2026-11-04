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
    fun queuedCueDoesNotDuplicateWorkButStillRequestsWorkerRecovery() {
        assertEquals(
            DubbingRetryQueueDecision.ENSURE_WORKER,
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
    fun transientSynthesisFailureKeepsCuePendingOnlyWhenActivePlaybackRequestedRetry() {
        assertEquals(true, DubbingRetryQueuePolicy.shouldKeepCuePendingAfterFailure(true))
        assertEquals(false, DubbingRetryQueuePolicy.shouldKeepCuePendingAfterFailure(false))
    }

    @Test
    fun deterministicSynthesisFailureDoesNotOverrideTheNoRetryRule() {
        assertEquals(
            false,
            DubbingRetryQueuePolicy.shouldKeepCuePendingAfterFailure(
                retryRequestedWhileActive = true,
                deterministicFailure = true,
            ),
        )
        assertEquals(
            false,
            DubbingRetryQueuePolicy.shouldRequeueAfterActiveSynthesis(
                retryRequested = true,
                translatedCue = true,
                hasReusableAudio = false,
                workerActive = true,
                released = false,
                fallbackRequested = false,
                alreadyQueued = false,
                deterministicFailure = true,
            ),
        )
    }

    @Test
    fun retryIsRequeuedWhenTheExistingCacheCannotBeReused() {
        assertEquals(
            true,
            DubbingRetryQueuePolicy.shouldRequeueAfterActiveSynthesis(
                retryRequested = true,
                translatedCue = true,
                hasReusableAudio = false,
                workerActive = true,
                released = false,
                fallbackRequested = false,
                alreadyQueued = false,
            ),
        )
    }

    @Test
    fun reusableCacheOrInactiveWorkerDoesNotRequeue() {
        val base = mapOf(
            "retryRequested" to true,
            "translatedCue" to true,
            "hasReusableAudio" to true,
            "workerActive" to true,
            "released" to false,
            "fallbackRequested" to false,
            "alreadyQueued" to false,
        )
        fun decision(overrides: Map<String, Boolean> = emptyMap()) =
            DubbingRetryQueuePolicy.shouldRequeueAfterActiveSynthesis(
                retryRequested = overrides["retryRequested"] ?: base.getValue("retryRequested"),
                translatedCue = overrides["translatedCue"] ?: base.getValue("translatedCue"),
                hasReusableAudio = overrides["hasReusableAudio"] ?: base.getValue("hasReusableAudio"),
                workerActive = overrides["workerActive"] ?: base.getValue("workerActive"),
                released = overrides["released"] ?: base.getValue("released"),
                fallbackRequested = overrides["fallbackRequested"] ?: base.getValue("fallbackRequested"),
                alreadyQueued = overrides["alreadyQueued"] ?: base.getValue("alreadyQueued"),
            )
        assertEquals(false, decision())
        assertEquals(false, decision(mapOf("hasReusableAudio" to false, "workerActive" to false)))
        assertEquals(false, decision(mapOf("hasReusableAudio" to false, "released" to true)))
        assertEquals(false, decision(mapOf("hasReusableAudio" to false, "fallbackRequested" to true)))
        assertEquals(false, decision(mapOf("hasReusableAudio" to false, "alreadyQueued" to true)))
        assertEquals(false, decision(mapOf("hasReusableAudio" to false, "retryRequested" to false)))
    }

    @Test
    fun anActiveCueIsDeferredEvenIfItWasAlsoQueuedAgain() {
        assertEquals(
            DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES,
            DubbingRetryQueuePolicy.decide(index = 5, activeIndex = 5, alreadyQueued = true),
        )
    }
}
