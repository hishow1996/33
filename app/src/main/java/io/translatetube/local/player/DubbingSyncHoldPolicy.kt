package io.translatetube.local.player

internal object DubbingSyncHoldPolicy {
    /**
     * Long sentences must not pause the video. Use a bounded speech-rate adjustment and timeline
     * scheduling instead; PCM overrun handling keeps the current utterance coherent without
     * treating an overlong subtitle as permission to freeze playback.
     */
    @Suppress("UNUSED_PARAMETER")
    fun requiresVideoHold(
        estimatedSpeechMs: Long,
        cueDurationMs: Long,
        baseSpeechRate: Float,
        maxSyncRate: Float = DubbingTiming.MAX_SYNC_RATE,
    ): Boolean = false

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

    /** Speech playback rate always follows both synchronization rate and the video's chosen speed. */
    fun playbackRate(syncRate: Float, playerSpeed: Float): Float {
        val safeSyncRate = syncRate.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE) ?: 1f
        val safePlayerSpeed = playerSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        return (safeSyncRate * safePlayerSpeed).coerceIn(0.5f, DubbingTiming.MAX_OUTPUT_RATE)
    }
}
