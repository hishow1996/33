package io.translatetube.local.asr

/**
 * Normalizes the timestamped transcript against the exact PCM audio sent to ASR.
 *
 * All timestamps are milliseconds on one media-relative clock. The resulting list is chronological
 * and is shared unchanged by translation, subtitle highlighting, cache persistence, and dubbing.
 */
internal object TranscriptTimelinePolicy {
    fun normalize(
        cues: List<Triple<Long, Long, String>>,
        audioDurationMs: Long,
    ): List<Triple<Long, Long, String>> {
        if (cues.isEmpty()) return emptyList()
        val duration = audioDurationMs.takeIf { it > 0L }
        val chronological = cues.asSequence()
            .mapNotNull { (rawStart, rawEnd, rawText) ->
                val text = rawText.trim()
                if (text.isBlank() || rawStart < 0L || rawEnd <= rawStart) {
                    return@mapNotNull null
                }
                if (duration == null) {
                    Triple(rawStart, rawEnd, text)
                } else {
                    // A cue starting beyond the submitted media cannot be synchronized to playback.
                    if (rawStart >= duration) return@mapNotNull null
                    val end = rawEnd.coerceAtMost(duration)
                    if (end <= rawStart) null else Triple(rawStart, end, text)
                }
            }
            .sortedWith(compareBy<Triple<Long, Long, String>> { it.first }.thenBy { it.second })
            .distinct()
            .toList()

        if (chronological.size < 2) return chronological

        // This transcript drives a single dubbing track. Provider word timings may cross at
        // phrase boundaries, so cap an earlier cue at the next strictly later cue's start.
        // Process equal-start cues as a group, allowing every row in that group to use the same
        // next-distinct timestamp without changing the chronological sort order or dropping recognized text.
        val normalized = arrayOfNulls<Triple<Long, Long, String>>(chronological.size)
        var groupEnd = chronological.lastIndex
        var nextLaterStart: Long? = null
        while (groupEnd >= 0) {
            val groupStart = chronological[groupEnd].first
            var groupFirst = groupEnd
            while (groupFirst > 0 && chronological[groupFirst - 1].first == groupStart) {
                groupFirst--
            }
            for (index in groupFirst..groupEnd) {
                val cue = chronological[index]
                val cap = nextLaterStart
                val end = if (cap != null && cue.second > cap) minOf(cue.second, cap) else cue.second
                if (end > cue.first) normalized[index] = Triple(cue.first, end, cue.third)
            }
            nextLaterStart = groupStart
            groupEnd = groupFirst - 1
        }
        return normalized.filterNotNull()
    }
}
