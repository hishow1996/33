package io.translatetube.local.asr

/**
 * Keeps speech cues small enough for Whisper without rebuilding a long recording into one huge
 * in-memory FloatArray. The VAD model normally emits 12-second chunks, but consecutive chunks
 * can be joined across natural pauses only while the resulting cue remains within this limit.
 */
internal object VadSegmentMergePolicy {
    const val MIN_SEGMENT_MS = 300L
    const val MERGE_GAP_MS = 400L
    const val MAX_MERGED_SEGMENT_MS = 25_000L

    fun merge(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val boundedSegments = mutableListOf<Pair<Long, Long>>()
        for ((start, end) in segments) {
            if (end - start < MIN_SEGMENT_MS) continue
            var cursor = start
            while (end - cursor > MAX_MERGED_SEGMENT_MS) {
                boundedSegments += cursor to (cursor + MAX_MERGED_SEGMENT_MS)
                cursor += MAX_MERGED_SEGMENT_MS
            }
            if (end - cursor >= MIN_SEGMENT_MS) boundedSegments += cursor to end
        }

        val merged = mutableListOf<Pair<Long, Long>>()
        for ((start, end) in boundedSegments) {
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
