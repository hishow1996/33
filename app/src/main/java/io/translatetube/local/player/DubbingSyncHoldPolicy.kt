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
        val safeBaseRate = baseSpeechRate.coerceIn(0.5f, 3.0f).toString().toDouble()
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(DubbingTiming.MAX_SYNC_RATE)
            ?.toString()
            ?.toDouble()
            ?: DubbingTiming.MAX_SYNC_RATE.toString().toDouble()
        val estimatedAtSelectedRateMs = estimatedSpeechMs.toDouble() / safeBaseRate
        return estimatedAtSelectedRateMs > cueDurationMs.toDouble() * safeMaxRate
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
     * Count speech-bearing Unicode code points without spaces or punctuation. Calibrating against
     * this count keeps punctuation pauses from being learned as if they were spoken characters.
     */
    fun spokenCodePointCount(text: String): Int {
        var count = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (Character.isLetterOrDigit(codePoint)) count++
            offset += Character.charCount(codePoint)
        }
        return count
    }

    /**
     * Approximate the natural pause contribution of punctuation. Consecutive marks such as "？！"
     * or "……" count as one pause, with the strongest mark in the run winning.
     */
    fun punctuationPauseMs(text: String): Long {
        var totalMs = 0L
        var offset = 0
        var previousCodePoint = -1
        var inPauseRun = false
        var currentRunPauseMs = 0L
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            val nextOffset = offset + Character.charCount(codePoint)
            val nextCodePoint = if (nextOffset < text.length) {
                Character.codePointAt(text, nextOffset)
            } else {
                -1
            }
            val decimalPoint = codePoint == 0x002E &&
                previousCodePoint in 0x0030..0x0039 &&
                nextCodePoint in 0x0030..0x0039
            val asciiEllipsisPart = codePoint == 0x002E &&
                (previousCodePoint == 0x002E || nextCodePoint == 0x002E)
            val midWordPeriod = codePoint == 0x002E &&
                nextCodePoint >= 0 &&
                !Character.isWhitespace(nextCodePoint) &&
                nextCodePoint != 0x002E
            val pauseMs = when {
                decimalPoint -> 0L
                asciiEllipsisPart -> 210L
                midWordPeriod -> 0L
                else -> punctuationPauseForCodePoint(codePoint)
            }
            if (pauseMs > 0L) {
                if (!inPauseRun) {
                    totalMs += pauseMs
                    currentRunPauseMs = pauseMs
                    inPauseRun = true
                } else if (pauseMs > currentRunPauseMs) {
                    totalMs += pauseMs - currentRunPauseMs
                    currentRunPauseMs = pauseMs
                }
            } else {
                inPauseRun = false
                currentRunPauseMs = 0L
            }
            previousCodePoint = codePoint
            offset = nextOffset
        }
        return totalMs.coerceAtMost(2_500L)
    }

    private fun punctuationPauseForCodePoint(codePoint: Int): Long = when (codePoint) {
        0x2026, 0x22EF -> 210L // ellipsis
        0xFF0C, 0x002C, 0x3001 -> 75L // comma / enumeration comma
        0xFF1B, 0x003B, 0xFF1A, 0x003A -> 100L // semicolon / colon
        0x3002, 0x002E, 0x0021, 0xFF01, 0x003F, 0xFF1F -> 165L // sentence end
        0x2014, 0x2013 -> 125L // dash
        else -> 0L
    }

    /**
     * Estimate 1x speech duration from spoken characters plus a bounded punctuation pause budget.
     * Space and punctuation are excluded from the learned character pace so sentence structure is
     * accounted for separately.
     */
    fun estimateSpeechDurationMs(text: String, msPerCodePoint: Double): Long {
        val safeMsPerCodePoint = msPerCodePoint
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(60.0, 900.0)
            ?: 220.0
        val spokenCount = spokenCodePointCount(text)
        val durationMs = spokenCount.toDouble() * safeMsPerCodePoint + punctuationPauseMs(text).toDouble()
        return durationMs.takeIf { it.isFinite() }
            ?.coerceAtMost(Long.MAX_VALUE.toDouble())
            ?.toLong()
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    /**
     * Calibrate direct System TTS duration from a completed utterance. Android OEM voices differ
     * widely in their actual speaking pace, so the character-count estimate is updated from observed
     * playback duration instead of remaining fixed at 220ms per spoken code point for the whole session.
     *
     * [elapsedMs] is normalized back to an approximate 1x duration using the applied rate, after
     * subtracting the separately estimated punctuation pauses. Ignore very short/cancelled utterances
     * and clamp extreme observations so callback jitter cannot destabilize future subtitle scheduling.
     */
    fun updateSpeechDurationEstimate(
        currentMsPerCodePoint: Double,
        elapsedMs: Long,
        codePointCount: Int,
        appliedSpeechRate: Float,
        estimatedPunctuationPauseMs: Long = 0L,
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

        val safeRate = appliedSpeechRate.coerceIn(0.5f, 6.0f).toDouble()
        val pauseAtAppliedRateMs =
            estimatedPunctuationPauseMs.coerceAtLeast(0L).toDouble() / safeRate
        val observedSpeechMs = (elapsedMs.toDouble() - pauseAtAppliedRateMs).coerceAtLeast(1.0)
        val normalizedObservation = observedSpeechMs * safeRate / codePointCount.toDouble()
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
