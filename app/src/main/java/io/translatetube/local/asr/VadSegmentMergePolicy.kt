package io.translatetube.local.asr

/**
 * Keeps adjacent speech cues readable by Whisper without rebuilding a long recording into one
 * huge in-memory FloatArray. The VAD model may emit repeated 12-second chunks for continuous
 * speech; merging is useful for natural pauses, but must never undo the model's short-window cap.
 */
internal object VadSegmentMergePolicy {
    const val MIN_SEGMENT_MS = 300L
    const val MERGE_GAP_MS = 400L
    const val MAX_MERGED_SEGMENT_MS = 25_000L

    fun merge(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val merged = mutableListOf<Pair<Long, Long>>()
        for ((start, end) in segments) {
            if (end - start < MIN_SEGMENT_MS) continue

            val last = merged.lastOrNull()
            val unionEnd = if (last == null) end else maxOf(last.second, end)
            val shouldMerge = last != null &&
                start - last.second < MERGE_GAP_MS &&
                unionEnd - last.first <= MAX_MERGED_SEGMENT_MS

            if (shouldMerge && last != null) {
                merged[merged.lastIndex] = last.first to unionEnd
            } else {
                merged += start to end
            }
        }
        return merged
    }
}
