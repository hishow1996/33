package io.translatetube.local.player

/** Coherent snapshot of the latest ExoPlayer time and the subtitle cue active at that time. */
internal data class DubbingTimelineCursor(
    val lineIndex: Int = -1,
    val positionMs: Long = -1L,
)

/** Chooses a non-stale start timestamp after asynchronous PCM cache loading. */
internal object DubbingStartPositionPolicy {
    fun choose(
        lineIndex: Int,
        lineStartMs: Long,
        lineEndMs: Long,
        requestedPositionMs: Long?,
        latestCursor: DubbingTimelineCursor,
    ): Long? {
        // Subtitle time is media-relative and must not begin before zero. Reject malformed
        // intervals here as well as in the parser so every caller gets the same invariant.
        if (lineIndex < 0 || lineStartMs < 0L || lineEndMs <= lineStartMs) return null

        // Any non-negative cursor is a real player snapshot, including position 0.
        // Once a snapshot exists it is authoritative: after a seek, never fall back to an
        // earlier requested timestamp when the new playhead is before this cue or in another cue.
        if (latestCursor.positionMs >= 0L) {
            if (
                latestCursor.positionMs < lineStartMs ||
                latestCursor.positionMs >= lineEndMs ||
                latestCursor.lineIndex != lineIndex
            ) return null
            return latestCursor.positionMs
        }

        // Only use the requested position before the ticker has published its first snapshot.
        val requested = requestedPositionMs ?: lineStartMs
        return requested.takeIf { it >= lineStartMs && it < lineEndMs }
    }
}
