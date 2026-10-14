package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Resolves the subtitle cue that should be drawn over the video at a media playhead position.
 *
 * Cues are expected in nondecreasing start-time order (SubtitleParsers.parseAuto and ASR output
 * both enforce this). Unlike the subtitle-list selection, this policy does not retain the previous
 * cue during a gap: cue intervals are explicitly [startMs, endMs).
 */
internal object SubtitleTimelinePolicy {
    fun lineAtPosition(lines: List<SubLine>, positionMs: Long): SubLine? {
        val index = lineIndexAtPosition(lines, positionMs)
        return lines.getOrNull(index)
    }

    fun lineIndexAtPosition(lines: List<SubLine>, positionMs: Long): Int {
        if (positionMs < 0L || lines.isEmpty()) return -1

        var low = 0
        var high = lines.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (lines[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return -1

        val line = lines[candidate]
        return if (positionMs >= line.startMs && positionMs < line.endMs) candidate else -1
    }
}
