package io.translatetube.local.player

internal object DubbingSyncHoldPolicy {
    /**
     * Detect whether speech is predicted to exceed the cue window after applying the selected
     * rate. This is an overrun marker only; video playback is never paused for dubbing.
     */
    fun wouldOverrunCueWindow(
        estimatedSpeechMs: Long,
        cueDurationMs: Long,
        baseSpeechRate: Float,
        maxSyncRate: Float = DubbingTiming.MAX_SYNC_RATE,
    ): Boolean {
        if (estimatedSpeechMs <= 0L || cueDurationMs <= 0L ||
            !baseSpeechRate.isFinite() || baseSpeechRate <= 0f
        ) return false
        val safeBaseRate = baseSpeechRate.coerceIn(0.5f, 3.0f)
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE)
            ?: DubbingTiming.MAX_SYNC_RATE
        val estimatedAtSelectedRateMs = estimatedSpeechMs.toDouble() / safeBaseRate.toDouble()
        return estimatedAtSelectedRateMs > cueDurationMs.toDouble() * safeMaxRate.toDouble()
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
            ?.coerceAtMost(DubbingTiming.MAX_CATCH_UP_SYNC_RATE)
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
