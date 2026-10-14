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
            val duration = end - start
            if (duration < MIN_SEGMENT_MS) continue

            // Split unexpected long VAD results into balanced windows. Taking a fixed 25-second
            // first window could leave a 100-ms tail which would then be discarded as too short.
            val partCountLong = duration / MAX_SEGMENT_DURATION_MS +
                if (duration % MAX_SEGMENT_DURATION_MS == 0L) 0L else 1L
            if (partCountLong > MAX_SEGMENTS.toLong()) {
                throw IllegalArgumentException("VAD 音频分段数量超过限制")
            }
            val partCount = partCountLong.toInt()
            val baseDuration = duration / partCountLong
            val extraMillis = duration % partCountLong
            var partStart = start
            repeat(partCount) { partIndex ->
                val partDuration = baseDuration + if (partIndex.toLong() < extraMillis) 1L else 0L
                val partEnd = partStart + partDuration
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
    }}
