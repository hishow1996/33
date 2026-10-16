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
        return cues.asSequence()
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
            .toList()
    }
}
