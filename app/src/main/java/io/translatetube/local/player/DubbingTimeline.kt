package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/**
 * Fast media-timeline lookup for subtitle cues, including overlapping captions.
 *
 * The index stores only primitive arrays (start times, original row indices and a max-end
 * segment tree). This avoids allocating a Cue wrapper object and an ArrayList entry per
 * subtitle, which matters for long videos and large imported subtitle files.
 *
 * A max-end segment tree finds the latest-started cue that still covers a playback position
 * in O(log n), even if one long cue overlaps thousands of already-expired short cues.
 */
internal class DubbingTimeline(lines: List<SubLine>) {
    companion object {
        internal const val MAX_INPUT_CUE_COUNT = 500_000
        internal const val MAX_PLAYABLE_CUE_COUNT = 200_000

        /** The playback index and synthesis queue must agree on whether a caption can ever play. */
        fun isPlayableCue(line: SubLine): Boolean =
            line.startMs >= 0L &&
                line.endMs > line.startMs &&
                line.text.isNotBlank() &&
                !SubtitleTranslationQualityPolicy.hasNothingToSpeak(line.text)
    }

    private val cueStartTimes: LongArray
    private val cueOriginalIndices: IntArray
    private val treeLeafCount: Int
    private val maxEndTree: LongArray

    init {
        require(lines.size <= MAX_INPUT_CUE_COUNT) {
            "字幕行数超过播放时间轴安全上限，已停止建立配音索引"
        }

        // Count first so we can allocate exact-sized arrays without a temporary object list.
        var playableCount = 0
        for (line in lines) {
            if (isPlayableCue(line)) playableCount++
        }
        require(playableCount <= MAX_PLAYABLE_CUE_COUNT) {
            "可播放字幕数量超过安全上限，请拆分视频或字幕文件后重试"
        }

        val starts = LongArray(playableCount)
        val originalIndices = IntArray(playableCount)
        var cursor = 0
        for (index in lines.indices) {
            val line = lines[index]
            if (!isPlayableCue(line)) continue
            starts[cursor] = line.startMs
            originalIndices[cursor] = index
            cursor++
        }

        // Keep ties ordered by original list index, matching the previous stable sort.
        heapSortCuePairs(starts, originalIndices)
        cueStartTimes = starts
        cueOriginalIndices = originalIndices

        var leafCount = 1
        while (leafCount < cueStartTimes.size) {
            require(leafCount <= Int.MAX_VALUE / 2) {
                "字幕时间轴索引过大，无法安全分配"
            }
            leafCount *= 2
        }
        treeLeafCount = leafCount

        // Each node stores the greatest cue end time in its subtree.
        val tree = LongArray(treeLeafCount * 2) { Long.MIN_VALUE }
        for (index in cueOriginalIndices.indices) {
            tree[treeLeafCount + index] = lines[cueOriginalIndices[index]].endMs
        }
        for (node in treeLeafCount - 1 downTo 1) {
            tree[node] = maxOf(tree[node * 2], tree[node * 2 + 1])
        }
        maxEndTree = tree
    }

    /**
     * In-place heap sort of paired primitive arrays. Ordering by original index on equal start
     * times preserves the previous latest-started / last-row-wins tie behavior.
     */
    private fun heapSortCuePairs(starts: LongArray, originalIndices: IntArray) {
        if (starts.size < 2) return

        for (root in (starts.size / 2 - 1) downTo 0) {
            siftDownCuePair(root, starts.lastIndex, starts, originalIndices)
        }
        for (end in starts.lastIndex downTo 1) {
            swapCuePairs(0, end, starts, originalIndices)
            siftDownCuePair(0, end - 1, starts, originalIndices)
        }
    }

    private fun siftDownCuePair(
        startRoot: Int,
        endIndex: Int,
        starts: LongArray,
        originalIndices: IntArray,
    ) {
        var root = startRoot
        while (true) {
            val leftChild = root * 2 + 1
            if (leftChild > endIndex) return

            var largerChild = leftChild
            val rightChild = leftChild + 1
            if (
                rightChild <= endIndex &&
                compareCuePairs(leftChild, rightChild, starts, originalIndices) < 0
            ) {
                largerChild = rightChild
            }

            if (compareCuePairs(root, largerChild, starts, originalIndices) >= 0) return
            swapCuePairs(root, largerChild, starts, originalIndices)
            root = largerChild
        }
    }

    private fun compareCuePairs(
        left: Int,
        right: Int,
        starts: LongArray,
        originalIndices: IntArray,
    ): Int {
        val startComparison = starts[left].compareTo(starts[right])
        return if (startComparison != 0) {
            startComparison
        } else {
            originalIndices[left].compareTo(originalIndices[right])
        }
    }

    private fun swapCuePairs(
        left: Int,
        right: Int,
        starts: LongArray,
        originalIndices: IntArray,
    ) {
        val start = starts[left]
        starts[left] = starts[right]
        starts[right] = start

        val originalIndex = originalIndices[left]
        originalIndices[left] = originalIndices[right]
        originalIndices[right] = originalIndex
    }

    fun lineAt(positionMs: Long): Int {
        if (positionMs < 0L || cueStartTimes.isEmpty()) return -1
        // Intro/outro positions are common and should not walk the interval tree.
        if (positionMs < cueStartTimes[0] || positionMs >= maxEndTree[1]) return -1
        if (cueStartTimes.size == 1) return cueOriginalIndices[0]

        var low = 0
        var high = cueStartTimes.lastIndex
        var latestStarted = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cueStartTimes[middle] <= positionMs) {
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
        return cueOriginalIndices.getOrNull(activeCueIndex) ?: -1
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
        if (left == right) return left.takeIf { it < cueStartTimes.size } ?: -1

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