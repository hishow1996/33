package io.translatetube.local.player

/** Decision for cache-miss recovery while the serial synthesis worker may already own a cue. */
internal enum class DubbingRetryQueueDecision {
    IGNORE,
    DEFER_UNTIL_ACTIVE_CUE_FINISHES,
    ENSURE_WORKER,
    ENQUEUE,
}

internal object DubbingRetryQueuePolicy {
    fun decide(index: Int, activeIndex: Int, alreadyQueued: Boolean): DubbingRetryQueueDecision =
        when {
            index < 0 -> DubbingRetryQueueDecision.IGNORE
            index == activeIndex -> DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES
            alreadyQueued -> DubbingRetryQueueDecision.ENSURE_WORKER
            else -> DubbingRetryQueueDecision.ENQUEUE
        }

    /** A failure is terminal only when playback hasn't already requested an active-cue retry. */
    fun shouldDeferFailureForActiveRetry(retryRequestedWhileActive: Boolean): Boolean =
        retryRequestedWhileActive
}
