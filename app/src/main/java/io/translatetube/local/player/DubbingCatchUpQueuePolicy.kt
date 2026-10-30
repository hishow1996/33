package io.translatetube.local.player

/**
 * Maintains unique catch-up cues while allowing an interrupted active sentence to move
 * ahead of later cues that were captured while it was speaking.
 */
internal object DubbingCatchUpQueuePolicy {
    fun enqueue(
        queue: LinkedHashSet<Int>,
        index: Int,
        prioritizeFirst: Boolean = false,
    ) {
        if (!prioritizeFirst || queue.firstOrNull() == index) {
            queue.add(index)
            return
        }

        val existing = queue.toList()
        queue.clear()
        queue.add(index)
        existing.forEach { queuedIndex ->
            if (queuedIndex != index) queue.add(queuedIndex)
        }
    }

    /** Convert media time into the same lead-adjusted domain used by subtitle lineAt(). */
    fun toTimelinePositionMs(mediaPositionMs: Long, offsetMs: Long): Long {
        if (mediaPositionMs < 0L) return mediaPositionMs
        return if (offsetMs > 0L && mediaPositionMs > Long.MAX_VALUE - offsetMs) {
            Long.MAX_VALUE
        } else {
            mediaPositionMs + offsetMs
        }
    }

    /** Return playable subtitle starts crossed since the previous timeline tick, in time order. */
    fun crossedCueIndexes(
        cueCount: Int,
        previousPositionMs: Long,
        positionMs: Long,
        activeDubbingIndex: Int,
        cueStartMs: (Int) -> Long,
        isPlayableCue: (Int) -> Boolean,
    ): List<Int> {
        if (cueCount <= 0 || positionMs <= previousPositionMs) return emptyList()
        return (0 until cueCount)
            .filter { index ->
                index != activeDubbingIndex &&
                    isPlayableCue(index) &&
                    cueStartMs(index) > previousPositionMs &&
                    cueStartMs(index) <= positionMs
            }
            .sortedWith(compareBy<Int> { cueStartMs(it) }.thenBy { it })
    }
}
