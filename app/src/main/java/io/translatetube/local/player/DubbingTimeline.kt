package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Fast media-timeline lookup for subtitle cues, including overlapping captions.
 *
 * The input list is not assumed to be sorted: restored captions, hand-edited subtitle files,
 * and third-party transcript providers can supply cues in a different order. The timeline keeps
 * a sorted index into the original list so returned cue IDs still address the caller's rows.
 */
internal class DubbingTimeline(private val lines: List<SubLine>) {
    private val sortedIndices = lines.indices
        .filter { lines[it].endMs > lines[it].startMs }
        .sortedWith(compareBy<Int> { lines[it].startMs }.thenBy { it })

    private val prefixMaxEndMs = LongArray(sortedIndices.size).also { prefix ->
        for (i in sortedIndices.indices) {
            val end = lines[sortedIndices[i]].endMs
            prefix[i] = if (i == 0) end else maxOf(prefix[i - 1], end)
        }
    }

    /**
     * Prefer the latest-started active cue, and fall back to an earlier overlapping cue when
     * a newer, shorter overlay has already ended. Returns the original list index, or -1 in gaps.
     */
    fun lineAt(positionMs: Long): Int {
        var low = 0
        var high = sortedIndices.lastIndex
        var latestStarted = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val originalIndex = sortedIndices[middle]
            if (lines[originalIndex].startMs <= positionMs) {
                latestStarted = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        var index = latestStarted
        while (index >= 0) {
            val originalIndex = sortedIndices[index]
            if (positionMs < lines[originalIndex].endMs) return originalIndex
            if (index == 0 || prefixMaxEndMs[index - 1] <= positionMs) break
            index--
        }
        return -1
    }
}
