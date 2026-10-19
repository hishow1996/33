package io.translatetube.local.player

/**
 * Gradually repay dubbing timeline debt without pausing video or making every normal cue sound fast.
 * More queued cues indicate more drift; the requested rate rises gently and is clamped by the
 * separate catch-up ceiling in DubbingTiming.
 */
internal object DubbingRecoveryRatePolicy {
    private const val MAX_QUEUE_DEPTH_FOR_RATE = 10
    private const val RATE_STEP_PER_QUEUED_CUE = 0.11f

    fun rateForQueueDepth(queueDepth: Int): Float {
        if (queueDepth <= 0) return 1.0f
        val steps = queueDepth.coerceAtMost(MAX_QUEUE_DEPTH_FOR_RATE)
        return (1.0f + steps * RATE_STEP_PER_QUEUED_CUE)
            .coerceIn(1.0f, DubbingTiming.MAX_CATCH_UP_SYNC_RATE)
    }
}
