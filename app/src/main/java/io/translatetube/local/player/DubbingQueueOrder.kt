package io.translatetube.local.player

/**
 * Stable queue ordering for real-time dubbing.
 * Prioritize the current subtitle and a small look-ahead window, without dropping other work.
 */
internal object DubbingQueueOrder {
    fun prioritize(
        queued: List<Int>,
        currentIndex: Int,
        totalLines: Int,
        lookAhead: Int = 2,
    ): List<Int> {
        if (queued.isEmpty() || totalLines <= 0 || currentIndex !in 0 until totalLines) {
            return queued
        }
        val remaining = queued.toMutableSet()
        val ordered = ArrayList<Int>(queued.size)
        val ahead = lookAhead.coerceAtLeast(0)
        for (index in currentIndex..minOf(totalLines - 1, currentIndex + ahead)) {
            if (remaining.remove(index)) ordered += index
        }
        queued.forEach { index ->
            if (remaining.remove(index)) ordered += index
        }
        return ordered
    }
}
