package io.translatetube.local.asr

/**
 * Bounds each Whisper input window after VAD has emitted its short speech chunks.
 *
 * Silero VAD may emit chunks of about 12 seconds, but blindly merging every chunk separated by a
 * short pause can turn continuous narration into a multi-minute segment. Offline Whisper is built
 * around short windows; keeping each merged cue <= 25 seconds also keeps the temporary FloatArray
 * sent to native inference small on devices such as the Honor 10.
 */
internal object VadSegmentPolicy {
    const val MIN_SEGMENT_MS = 300L
    const val MERGE_GAP_MS = 400L
    const val MAX_SEGMENT_DURATION_MS = 25_000L

    fun merge(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (segments.isEmpty()) return emptyList()
        val ordered = segments
            .filter { (start, end) -> start >= 0L && end > start }
            .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        if (ordered.isEmpty()) return emptyList()

        val bounded = ArrayList<Pair<Long, Long>>(ordered.size)
        for ((start, end) in ordered) {
            if (end - start < MIN_SEGMENT_MS) continue
            var partStart = start
            while (partStart < end) {
                val partEnd = minOf(end, partStart + MAX_SEGMENT_DURATION_MS)
                if (partEnd - partStart >= MIN_SEGMENT_MS) {
                    val last = bounded.lastOrNull()
                    val sameWindow = last != null &&
                        partStart - last.second < MERGE_GAP_MS &&
                        partEnd - last.first <= MAX_SEGMENT_DURATION_MS
                    if (sameWindow) {
                        bounded[bounded.lastIndex] = last!!.first to maxOf(last.second, partEnd)
                    } else {
                        bounded += partStart to partEnd
                    }
                }
                partStart = partEnd
            }
        }
        return bounded
    }
}
