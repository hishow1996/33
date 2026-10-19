package io.translatetube.local.player

/**
 * Gradually repay dubbing timeline debt without pausing video or making every normal cue sound fast.
 * More queued cues indicate more drift; the requested rate rises gently and is clamped by the
 * separate catch-up ceiling in DubbingTiming.
 */
internal object DubbingRecoveryRatePolicy {
    private const val MAX_QUEUE_DEPTH_FOR_RATE = 10
    private const val RATE_STEP_PER_QUEUED_CUE = 0.11f

    fun rateForQueueDepth(queueDepth: Int): Float =
        rateForBacklog(queueDepth = queueDepth, latenessMs = 0L)

    /**
     * Queue depth estimates how many sentences are waiting; lateness captures actual timeline debt.
     * Each contributes a bounded amount, so a single cue that is many seconds late can still recover
     * instead of being treated as a tiny backlog.
     */
    fun rateForBacklog(queueDepth: Int, latenessMs: Long): Float {
        val steps = queueDepth.coerceAtLeast(0).coerceAtMost(MAX_QUEUE_DEPTH_FOR_RATE)
        val queuePressure = steps * RATE_STEP_PER_QUEUED_CUE
        val safeLatenessMs = latenessMs.coerceAtLeast(0L).coerceAtMost(11_000L)
        val latenessPressure = safeLatenessMs.toFloat() / 10_000.0f
        return (1.0f + queuePressure + latenessPressure)
            .coerceIn(1.0f, DubbingTiming.MAX_CATCH_UP_SYNC_RATE)
    }
}
