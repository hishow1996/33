package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Fast media-timeline lookup for subtitle cues, including overlapping captions.
 *
 * Cues are indexed by start time while retaining each cue's position in the caller's original
 * list. A max-end segment tree finds the latest-started cue that still covers a playback position
 * in O(log n), even if one long cue overlaps thousands of already-expired short cues.
 */
internal class DubbingTimeline(lines: List<SubLine>) {
    private data class Cue(val originalIndex: Int, val line: SubLine)

    companion object {
        internal const val MAX_INPUT_CUE_COUNT = 500_000
        internal const val MAX_PLAYABLE_CUE_COUNT = 200_000

        /** The playback index and synthesis queue must agree on whether a caption can ever play. */
        fun isPlayableCue(line: SubLine): Boolean =
            line.startMs >= 0L && line.endMs > line.startMs && line.text.isNotBlank()
    }

    private val cues = run {
        require(lines.size <= MAX_INPUT_CUE_COUNT) {
            "字幕行数超过播放时间轴安全上限，已停止建立配音索引"
        }
        // Count before allocating Cue objects and the max-end tree; invalid imported rows should
        // not make a very large tree just to be discarded by the filter.
        val playableCount = lines.count(::isPlayableCue)
        require(playableCount <= MAX_PLAYABLE_CUE_COUNT) {
            "可播放字幕数量超过安全上限，请拆分视频或字幕文件后重试"
        }
        lines.withIndex()
            .asSequence()
            .filter { (_, line) -> isPlayableCue(line) }
            .map { (index, line) -> Cue(index, line) }
            .sortedWith(compareBy<Cue> { it.line.startMs }.thenBy { it.originalIndex })
            .toList()
    }

    private val treeLeafCount: Int = run {
        var size = 1
        while (size < cues.size) {
            require(size <= Int.MAX_VALUE / 2) { "字幕时间轴索引过大，无法安全分配" }
            size *= 2
        }
        size
    }

    /** Each node stores the greatest cue end time in its subtree. */
    private val maxEndTree = LongArray(treeLeafCount * 2) { Long.MIN_VALUE }.also { tree ->
        for (index in cues.indices) {
            tree[treeLeafCount + index] = cues[index].line.endMs
        }
        for (node in treeLeafCount - 1 downTo 1) {
            tree[node] = maxOf(tree[node * 2], tree[node * 2 + 1])
        }
    }

    fun lineAt(positionMs: Long): Int {
        if (positionMs < 0L || cues.isEmpty()) return -1

        var low = 0
        var high = cues.lastIndex
        var latestStarted = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].line.startMs <= positionMs) {
                latestStarted = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (latestStarted < 0) return -1

        val activeCueIndex = findRightmostActive(
            node = 1,
            left = 0,
            right = treeLeafCount - 1,
            latestAllowedIndex = latestStarted,
            positionMs = positionMs,
        )
        return cues.getOrNull(activeCueIndex)?.originalIndex ?: -1
    }

    /**
     * Search right-first so ties/latest starts keep their established priority. Subtrees whose
     * maximum end is at or before the playhead cannot contain an active cue and are skipped.
     */
    private fun findRightmostActive(
        node: Int,
        left: Int,
        right: Int,
        latestAllowedIndex: Int,
        positionMs: Long,
    ): Int {
        if (left > latestAllowedIndex || maxEndTree[node] <= positionMs) return -1
        if (left == right) return left.takeIf { it < cues.size } ?: -1

        val middle = (left + right) ushr 1
        val rightMatch = findRightmostActive(
            node = node * 2 + 1,
            left = middle + 1,
            right = right,
            latestAllowedIndex = latestAllowedIndex,
            positionMs = positionMs,
        )
        if (rightMatch >= 0) return rightMatch

        return findRightmostActive(
            node = node * 2,
            left = left,
            right = middle,
            latestAllowedIndex = latestAllowedIndex,
            positionMs = positionMs,
        )
    }
}
