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
        rate(queueDepth = queueDepth, latenessMs = 0L)

    /**
     * Queue depth estimates how many sentences are waiting; lateness captures actual timeline debt.
     * Each contributes a bounded amount, so a single cue that is many seconds late can still recover
     * instead of being treated as a tiny backlog.
     */
    fun rateForBacklog(
        queueDepth: Int,
        latenessMs: Long,
        /** True while a [DubbingLagTracker] says catch-up is under way (hysteresis). */
        catchingUp: Boolean = latenessMs >= DubbingTiming.LAG_TOLERANCE_MS,
    ): Float {
        // Speech may trail the picture by a couple of seconds without any speed-up.
        if (!catchingUp || latenessMs < DubbingTiming.LAG_RESUME_MS) return 1.0f
        return maxOf(rate(queueDepth, latenessMs), DubbingTiming.MIN_ACTIVE_CATCH_UP_RATE)
    }

    private fun rate(queueDepth: Int, latenessMs: Long): Float {
        val steps = queueDepth.coerceAtLeast(0).coerceAtMost(MAX_QUEUE_DEPTH_FOR_RATE)
        val queuePressure = steps * RATE_STEP_PER_QUEUED_CUE
        val safeLatenessMs = latenessMs.coerceAtLeast(0L).coerceAtMost(11_000L)
        val latenessPressure = safeLatenessMs.toFloat() / 10_000.0f
        // Rare, large lag may use a higher (still bounded) rate; everything else stays gentle.
        val ceiling = if (latenessMs > DubbingTiming.LARGE_LAG_MS) {
            DubbingTiming.LARGE_LAG_RECOVERY_SYNC_RATE
        } else {
            DubbingTiming.LAG_RECOVERY_SYNC_RATE
        }
        return (1.0f + queuePressure + latenessPressure).coerceIn(1.0f, ceiling)
    }
}
