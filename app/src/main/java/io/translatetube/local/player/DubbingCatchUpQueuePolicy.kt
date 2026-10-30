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
}
