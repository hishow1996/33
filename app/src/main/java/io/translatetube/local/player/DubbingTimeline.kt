package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Fast media-timeline lookup for sorted subtitle cues, including overlapping captions.
 * Prefix maximum end-times let us stop scanning once no earlier cue can still be active.
 */
internal class DubbingTimeline(private val lines: List<SubLine>) {
    private val prefixMaxEndMs = LongArray(lines.size).also { prefix ->
        for (i in lines.indices) {
            prefix[i] = if (i == 0) lines[i].endMs else maxOf(prefix[i - 1], lines[i].endMs)
        }
    }

    fun lineAt(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var latestStarted = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= positionMs) {
                latestStarted = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        var index = latestStarted
        while (index >= 0) {
            // Prefer the latest-started cue, but fall back to an earlier overlapping cue
            // if this newer cue ended before the queried media time.
            if (positionMs < lines[index].endMs) return index
            if (index == 0 || prefixMaxEndMs[index - 1] <= positionMs) break
            index--
        }
        return -1
    }
}
