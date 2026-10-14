package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Resolves the subtitle cue that should be drawn over the video at a media playhead position.
 *
 * Cues are expected in nondecreasing start-time order (SubtitleParsers.parseAuto and ASR output
 * both enforce this). Unlike the subtitle-list selection, this policy does not retain the previous
 * cue during a gap: cue intervals are explicitly [startMs, endMs).
 *
 * A max-end segment tree finds the rightmost active interval in O(log n), so the UI does not scan
 * the entire subtitle history during every small gap on a long video.
 */
internal class SubtitleTimelinePolicy(lines: List<SubLine>) {
    private val cues = lines.toList()
    private val tree = LongArray(if (cues.isEmpty()) 0 else cues.size * 4) { Long.MIN_VALUE }

    init {
        if (cues.isNotEmpty()) build(node = 1, left = 0, right = cues.lastIndex)
    }

    fun lineAtPosition(positionMs: Long): SubLine? {
        if (positionMs < 0L || cues.isEmpty()) return null

        // Find the last cue whose start time is <= the playhead.
        var low = 0
        var high = cues.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (cues[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null

        val activeIndex = findRightmostActive(
            node = 1,
            left = 0,
            right = cues.lastIndex,
            maxIndex = candidate,
            positionMs = positionMs,
        )
        return cues.getOrNull(activeIndex)
    }

    private fun build(node: Int, left: Int, right: Int): Long {
        if (left == right) {
            return cues[left].endMs.also { tree[node] = it }
        }
        val mid = left + (right - left) / 2
        val maxEnd = maxOf(
            build(node * 2, left, mid),
            build(node * 2 + 1, mid + 1, right),
        )
        tree[node] = maxEnd
        return maxEnd
    }

    private fun findRightmostActive(
        node: Int,
        left: Int,
        right: Int,
        maxIndex: Int,
        positionMs: Long,
    ): Int {
        if (left > maxIndex || tree[node] <= positionMs) return -1
        if (left == right) {
            val line = cues[left]
            return if (line.startMs <= positionMs && positionMs < line.endMs) left else -1
        }

        val mid = left + (right - left) / 2
        val rightIndex = findRightmostActive(
            node * 2 + 1, mid + 1, right, maxIndex, positionMs
        )
        if (rightIndex >= 0) return rightIndex
        return findRightmostActive(node * 2, left, mid, maxIndex, positionMs)
    }
}
