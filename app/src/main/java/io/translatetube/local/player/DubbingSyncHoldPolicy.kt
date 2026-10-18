package io.translatetube.local.player

internal object DubbingSyncHoldPolicy {
    fun requiresVideoHold(
        estimatedSpeechMs: Long,
        cueDurationMs: Long,
        baseSpeechRate: Float,
        maxSyncRate: Float = DubbingTiming.MAX_SYNC_RATE,
    ): Boolean {
        if (
            estimatedSpeechMs <= 0L ||
            cueDurationMs <= 0L ||
            !baseSpeechRate.isFinite() ||
            baseSpeechRate <= 0f ||
            !maxSyncRate.isFinite() ||
            maxSyncRate < 1f
        ) return false
        val safeBaseRate = baseSpeechRate.coerceIn(0.5f, 3.0f)
        val safeMaxRate = maxSyncRate.coerceAtMost(DubbingTiming.MAX_SYNC_RATE)
        val availableSpeechBudgetMs = cueDurationMs.toDouble() * safeBaseRate * safeMaxRate
        return estimatedSpeechMs.toDouble() > availableSpeechBudgetMs
    }

    /**
     * Estimate only the extra synchronization factor. Never slow short utterances merely to fill
     * their subtitle window; let them finish naturally at the selected speech rate.
     */
    fun timelineRate(
        estimatedSpeechMs: Long,
        cueDurationMs: Long,
        maxSyncRate: Float = DubbingTiming.MAX_SYNC_RATE,
    ): Float {
        if (estimatedSpeechMs <= 0L || cueDurationMs <= 0L) return 1.0f
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE)
            ?: DubbingTiming.MAX_SYNC_RATE
        return (estimatedSpeechMs.toDouble() / cueDurationMs.toDouble())
            .coerceIn(1.0, safeMaxRate.toDouble())
            .toFloat()
    }

    /** When video will pause, do not inherit its speed multiplier for the voice output. */
    fun playbackRate(syncRate: Float, playerSpeed: Float, willHoldVideo: Boolean): Float {
        val safeSyncRate = syncRate.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE) ?: 1f
        val safePlayerSpeed = playerSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        return (safeSyncRate * if (willHoldVideo) 1f else safePlayerSpeed)
            .coerceIn(0.5f, DubbingTiming.MAX_OUTPUT_RATE)
    }
}
