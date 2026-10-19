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
        targetRate: Float = 1.0f,
    ): Float {
        if (estimatedSpeechMs <= 0L || cueDurationMs <= 0L) return 1.0f
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(DubbingTiming.MAX_CATCH_UP_SYNC_RATE)
            ?: DubbingTiming.MAX_SYNC_RATE
        val requiredRate = (estimatedSpeechMs.toDouble() / cueDurationMs.toDouble())
            .coerceIn(1.0, safeMaxRate.toDouble())
        val safeTargetRate = targetRate.takeIf { it.isFinite() && it > 0.0f }
            ?.coerceIn(1.0f, safeMaxRate)
            ?.toDouble()
            ?: 1.0
        return maxOf(requiredRate, safeTargetRate).coerceIn(1.0, safeMaxRate.toDouble()).toFloat()
    }

    /**
     * Calibrate direct System TTS duration from a completed utterance. Android OEM voices differ
     * widely in their actual speaking pace, so the character-count estimate is updated from observed
     * playback duration instead of remaining fixed at 220ms per code point for the whole session.
     *
     * [elapsedMs] is normalized back to an approximate 1x duration using the applied rate. Ignore
     * very short/cancelled utterances and clamp extreme observations so callback jitter cannot
     * destabilize future subtitle scheduling.
     */
    fun updateSpeechDurationEstimate(
        currentMsPerCodePoint: Double,
        elapsedMs: Long,
        codePointCount: Int,
        appliedSpeechRate: Float,
    ): Double {
        val current = currentMsPerCodePoint
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(60.0, 900.0)
            ?: 220.0
        if (
            elapsedMs !in 120L..60_000L ||
            codePointCount <= 0 ||
            !appliedSpeechRate.isFinite() ||
            appliedSpeechRate <= 0.0f
        ) return current

        val normalizedObservation = elapsedMs.toDouble() *
            appliedSpeechRate.coerceIn(0.5f, 6.0f).toDouble() /
            codePointCount.toDouble()
        if (!normalizedObservation.isFinite() || normalizedObservation <= 0.0) return current

        val boundedObservation = normalizedObservation.coerceIn(60.0, 900.0)
        // A 30% update reacts over several utterances while smoothing out callback and punctuation variance.
        return (current * 0.70 + boundedObservation * 0.30).coerceIn(60.0, 900.0)
    }

    /** Speech playback rate always follows both synchronization rate and the video's chosen speed. */
    fun playbackRate(syncRate: Float, playerSpeed: Float): Float {
        val safeSyncRate = syncRate.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE) ?: 1f
        val safePlayerSpeed = playerSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        return (safeSyncRate * safePlayerSpeed).coerceIn(0.5f, DubbingTiming.MAX_OUTPUT_RATE)
    }
}
