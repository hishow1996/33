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

    /**
     * If playback already requested the active cue again, its failed attempt is provisional:
     * keep the row pending until the queued retry finishes instead of publishing a silent marker.
     */
    fun shouldKeepCuePendingAfterFailure(
        retryRequestedWhileActive: Boolean,
        deterministicFailure: Boolean = false,
    ): Boolean =
        retryRequestedWhileActive && !deterministicFailure

    /** Requeue only when the active-cue retry is still needed and the worker can accept it. */
    fun shouldRequeueAfterActiveSynthesis(
        retryRequested: Boolean,
        translatedCue: Boolean,
        hasReusableAudio: Boolean,
        workerActive: Boolean,
        released: Boolean,
        fallbackRequested: Boolean,
        alreadyQueued: Boolean,
        deterministicFailure: Boolean = false,
    ): Boolean =
        retryRequested &&
            !deterministicFailure &&
            translatedCue &&
            !hasReusableAudio &&
            workerActive &&
            !released &&
            !fallbackRequested &&
            !alreadyQueued
}
