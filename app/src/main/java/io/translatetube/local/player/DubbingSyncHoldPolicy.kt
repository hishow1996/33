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

    /** When video will pause, do not inherit its speed multiplier for the voice output. */
    fun playbackRate(syncRate: Float, playerSpeed: Float, willHoldVideo: Boolean): Float {
        val safeSyncRate = syncRate.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safePlayerSpeed = playerSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        return (safeSyncRate * if (willHoldVideo) 1f else safePlayerSpeed)
            .coerceIn(0.5f, DubbingTiming.MAX_OUTPUT_RATE)
    }
}
