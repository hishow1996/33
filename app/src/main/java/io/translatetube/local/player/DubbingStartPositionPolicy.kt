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

        // Once any live ticker snapshot exists, it is authoritative—even after a backward seek.
        // Checking only position >= lineStartMs lets a stale future cue restart after the user seeks
        // earlier than that cue while its PCM is still loading in the background.
        if (latestCursor.positionMs >= 0L) {
            if (latestCursor.positionMs < lineStartMs ||
                latestCursor.positionMs >= lineEndMs ||
                latestCursor.lineIndex != lineIndex
            ) return null
            return latestCursor.positionMs
        }

        // No live snapshot has been published yet; only then may the original request be used.
        val requested = requestedPositionMs ?: lineStartMs
        return requested.takeIf { it >= lineStartMs && it < lineEndMs }
    }
}
