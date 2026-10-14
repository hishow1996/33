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

    /** Clips a padded VAD span back to the original decoded sample range. */
    fun clipToSampleBounds(
        startSample: Long,
        endSample: Long,
        sampleCount: Long,
    ): Pair<Long, Long>? {
        if (sampleCount <= 0L || startSample < 0L || endSample <= startSample) return null
        val start = startSample.coerceAtMost(sampleCount)
        val end = endSample.coerceIn(start, sampleCount)
        return if (end > start) start to end else null
    }

    fun merge(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val boundedSegments = mutableListOf<Pair<Long, Long>>()
        // Native VAD normally emits chronological windows, but normalize the contract here too:
        // an out-of-order or malformed provider result must never make Whisper emit subtitles backwards.
        val orderedValidSegments = segments.asSequence()
            .filter { (start, end) -> start >= 0L && end > start }
            .sortedBy { it.first }
        for ((start, end) in orderedValidSegments) {
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
