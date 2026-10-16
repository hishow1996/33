package io.translatetube.local.asr

/**
 * Normalizes the timestamped transcript against the exact PCM audio sent to ASR.
 *
 * All timestamps are milliseconds on one media-relative clock. The resulting list is chronological
 * and is shared unchanged by translation, subtitle highlighting, cache persistence, and dubbing.
 */
internal object TranscriptTimelinePolicy {
    internal const val MAX_CUE_COUNT = 500_000

    fun normalize(
        cues: List<Triple<Long, Long, String>>,
        audioDurationMs: Long,
        checkActive: () -> Unit = {},
    ): List<Triple<Long, Long, String>> {
        checkActive()
        if (cues.isEmpty()) return emptyList()
        require(cues.size <= MAX_CUE_COUNT) {
            "语音识别返回的字幕条数超过安全上限，已停止处理以保护设备内存"
        }
        val duration = audioDurationMs.takeIf { it > 0L }
        val chronological = ArrayList<Triple<Long, Long, String>>(cues.size)
        for (index in cues.indices) {
            if ((index and 0xff) == 0) checkActive()
            val (rawStart, rawEnd, rawText) = cues[index]
            val text = rawText.trim()
            if (text.isBlank() || rawStart < 0L || rawEnd <= rawStart) continue
            if (duration == null) {
                chronological += Triple(rawStart, rawEnd, text)
            } else {
                if (rawStart >= duration) continue
                val end = rawEnd.coerceAtMost(duration)
                if (end > rawStart) chronological += Triple(rawStart, end, text)
            }
        }
        chronological.sortWith(
            compareBy<Triple<Long, Long, String>> { it.first }
                .thenBy { it.second }
                .thenBy { it.third },
        )
        checkActive()

        // Identical rows become adjacent after sorting; discard them without an extra HashSet.
        val deduplicated = ArrayList<Triple<Long, Long, String>>(chronological.size)
        for (index in chronological.indices) {
            if ((index and 0xff) == 0) checkActive()
            val cue = chronological[index]
            if (deduplicated.lastOrNull() != cue) deduplicated += cue
        }
        if (deduplicated.size < 2) return deduplicated

        // Provider word timings may cross at phrase boundaries, so cap an earlier cue at the next
        // strictly later cue's start. Equal-start groups share the same next-later timestamp.
        val normalized = arrayOfNulls<Triple<Long, Long, String>>(deduplicated.size)
        var groupEnd = deduplicated.lastIndex
        var nextLaterStart: Long? = null
        var scanned = 0
        while (groupEnd >= 0) {
            if ((scanned++ and 0xff) == 0) checkActive()
            val groupStart = deduplicated[groupEnd].first
            var groupFirst = groupEnd
            while (groupFirst > 0 && deduplicated[groupFirst - 1].first == groupStart) {
                groupFirst--
                if ((scanned++ and 0xff) == 0) checkActive()
            }
            for (index in groupFirst..groupEnd) {
                if ((scanned++ and 0xff) == 0) checkActive()
                val cue = deduplicated[index]
                val cap = nextLaterStart
                val end = if (cap != null && cue.second > cap) minOf(cue.second, cap) else cue.second
                if (end > cue.first) normalized[index] = Triple(cue.first, end, cue.third)
            }
            nextLaterStart = groupStart
            groupEnd = groupFirst - 1
        }
        val result = ArrayList<Triple<Long, Long, String>>(normalized.size)
        for (index in normalized.indices) {
            if ((index and 0xff) == 0) checkActive()
            normalized[index]?.let(result::add)
        }
        checkActive()
        return result
    }
}
