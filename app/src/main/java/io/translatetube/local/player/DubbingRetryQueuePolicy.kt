package io.translatetube.local.player

/** Decision for cache-miss recovery while the serial synthesis worker may already own a cue. */
internal enum class DubbingRetryQueueDecision {
    IGNORE,
    DEFER_UNTIL_ACTIVE_CUE_FINISHES,
    ENQUEUE,
}

internal object DubbingRetryQueuePolicy {
    fun decide(index: Int, activeIndex: Int, alreadyQueued: Boolean): DubbingRetryQueueDecision =
        when {
            index < 0 -> DubbingRetryQueueDecision.IGNORE
            index == activeIndex -> DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES
            alreadyQueued -> DubbingRetryQueueDecision.IGNORE
            else -> DubbingRetryQueueDecision.ENQUEUE
        }
}
