package io.translatetube.local.player

/**
 * Keeps local neural speech inference near the playhead while video playback is active.
 * Deferred cues remain in the normal queue and are promoted by prioritizeAround() after a seek
 * or as the playhead approaches them; no cue is discarded or marked as prepared.
 */
internal object DubbingSynthesisSchedulingPolicy {
    const val DEFER_DELAY_MS = 120L

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
