package io.translatetube.local.player

/**
 * Keeps local neural speech inference near the playhead while video playback is active.
 * Deferred cues remain in the normal queue and are promoted by prioritizeAround() after a seek
 * or as the playhead approaches them; no cue is discarded or marked as prepared.
 */
internal object DubbingSynthesisSchedulingPolicy {
    /** Periodic fallback to notice pause/resume when the playhead index does not change. */
    const val DEFER_RECHECK_DELAY_MS = 1_000L

    /**
     * A full queue reorder is needed only when the playhead leaves the previously promoted
     * window, seeks backward, or asks for a wider look-ahead. Normal forward playback updates
     * the live playhead without repeatedly sorting every pending subtitle.
     */
    fun shouldRefreshPriorityWindow(
        currentIndex: Int,
        queueWindowStart: Int,
        currentLookAhead: Int,
        requestedLookAhead: Int,
    ): Boolean {
        if (currentIndex < 0) return false
        if (queueWindowStart < 0) return true
        val currentAhead = currentLookAhead.coerceAtLeast(0)
        val requestedAhead = requestedLookAhead.coerceAtLeast(0)
        return requestedAhead > currentAhead ||
            currentIndex < queueWindowStart ||
            currentIndex.toLong() > queueWindowStart.toLong() + currentAhead.toLong()
    }

    fun shouldDefer(
        index: Int,
        currentIndex: Int,
        lookAhead: Int,
        playbackActive: Boolean,
    ): Boolean {
        if (!playbackActive || index < 0 || currentIndex < 0) return false
        val firstUrgent = currentIndex.toLong()
        val lastUrgent = firstUrgent + lookAhead.coerceAtLeast(0).toLong()
        val cue = index.toLong()
        return cue < firstUrgent || cue > lastUrgent
    }
}
