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
        val validQueued = queued.filter { it in 0 until totalLines }
        if (validQueued.isEmpty()) return emptyList()
        val remaining = validQueued.toMutableSet()
        val ordered = ArrayList<Int>(validQueued.size)
        val ahead = lookAhead.coerceAtLeast(0)
        val lastPromoted = minOf(totalLines.toLong() - 1L, currentIndex.toLong() + ahead.toLong()).toInt()
        for (index in currentIndex..lastPromoted) {
            if (remaining.remove(index)) ordered += index
        }
        validQueued.forEach { index ->
            if (remaining.remove(index)) ordered += index
        }
        return ordered
    }

    /**
     * 整张队列要重排时（改语速等）：播放位置及之后的句子先合成，已经播过的排到最后补齐。
     * 播放位置未知（不在字幕范围内）时保持原顺序。
     */
    fun rebuildFromPlayhead(
        queued: List<Int>,
        playheadIndex: Int,
        totalLines: Int,
        lookAhead: Int = 2,
    ): List<Int> {
        if (playheadIndex !in 0 until totalLines) return queued
        val fromPlayhead = queued.filter { it >= playheadIndex } + queued.filter { it < playheadIndex }
        return prioritize(fromPlayhead, playheadIndex, totalLines, lookAhead)
    }
}
