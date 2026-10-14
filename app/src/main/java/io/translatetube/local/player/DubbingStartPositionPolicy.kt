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
        if (lineIndex < 0 || lineEndMs <= lineStartMs) return null

        // If the ticker has already reached this cue or passed it, trust that newer position.
        // Never revive an old request when the user has moved into a gap or another cue.
        if (latestCursor.positionMs >= lineStartMs) {
            if (latestCursor.positionMs >= lineEndMs || latestCursor.lineIndex != lineIndex) return null
            return latestCursor.positionMs
        }

        val requested = requestedPositionMs ?: lineStartMs
        return requested.takeIf { it >= lineStartMs && it < lineEndMs }
    }
}
