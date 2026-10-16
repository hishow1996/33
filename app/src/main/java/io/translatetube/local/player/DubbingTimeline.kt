package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Fast media-timeline lookup for subtitle cues, including overlapping captions.
 *
 * Build an ordered index while retaining each cue's position in the caller's original list. This
 * keeps binary search correct even if a cache/provider gives us rows out of order, without making
 * the index returned by lineAt refer to a different row in DubbingEngine.lines.
 */
internal class DubbingTimeline(lines: List<SubLine>) {
    private data class Cue(val originalIndex: Int, val line: SubLine)

    companion object {
        /** The playback index and synthesis queue must agree on whether a caption can ever play. */
        fun isPlayableCue(line: SubLine): Boolean =
            line.startMs >= 0L && line.endMs > line.startMs && line.text.isNotBlank()
    }

    private val cues = lines.withIndex()
        .asSequence()
        .filter { (_, line) -> isPlayableCue(line) }
        .map { (index, line) -> Cue(index, line) }
        .sortedWith(compareBy<Cue> { it.line.startMs }.thenBy { it.originalIndex })
        .toList()

    /** Prefix maximum end times stop the backwards scan when no earlier cue can still be active. */
    private val prefixMaxEndMs = LongArray(cues.size).also { prefix ->
        for (i in cues.indices) {
            prefix[i] = if (i == 0) cues[i].line.endMs else maxOf(prefix[i - 1], cues[i].line.endMs)
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

        var index = latestStarted
        while (index >= 0) {
            val cue = cues[index]
            // Prefer the latest-started cue, but fall back to an earlier overlapping cue if this
            // newer cue ended before the queried media time.
            if (positionMs < cue.line.endMs) return cue.originalIndex
            if (index == 0 || prefixMaxEndMs[index - 1] <= positionMs) break
            index--
        }
        return -1
    }
}
