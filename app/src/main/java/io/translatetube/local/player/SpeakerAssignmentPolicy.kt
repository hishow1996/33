package io.translatetube.local.player

/**
 * Converts temporary diarizer cluster labels to IDs ordered by first appearance and attributes
 * diarized segments to subtitle cues without allocating several intermediate collections per cue.
 */
internal object SpeakerAssignmentPolicy {
    data class SpeakerObservation(
        val speakerId: Int,
        val startMs: Long,
        val endMs: Long,
        /** NaN/negative means the model did not expose confidence for this segment. */
        val confidence: Float = Float.NaN,
    )

    private data class SpeakerProfile(
        val rawId: Int,
        var firstStartMs: Long,
        var firstEndMs: Long,
        var totalDurationMs: Long,
    )

    private data class CueScore(
        var overlapMs: Long = 0L,
        var confidenceOverlap: Double = 0.0,
    )

    fun canonicalSpeakerIds(observations: List<SpeakerObservation>): Map<Int, Int> {
        if (observations.isEmpty()) return emptyMap()

        // Aggregate in one pass instead of filter/groupBy/map chains. Speaker analysis runs on
        // devices such as the Honor 10, where needless temporary lists are worth avoiding.
        val profiles = HashMap<Int, SpeakerProfile>()
        for (row in observations) {
            if (row.speakerId < 0 || row.startMs < 0L || row.endMs <= row.startMs) continue
            val duration = row.endMs - row.startMs
            val current = profiles[row.speakerId]
            if (current == null) {
                profiles[row.speakerId] = SpeakerProfile(
                    rawId = row.speakerId,
                    firstStartMs = row.startMs,
                    firstEndMs = row.endMs,
                    totalDurationMs = duration,
                )
            } else {
                if (row.startMs < current.firstStartMs ||
                    (row.startMs == current.firstStartMs && row.endMs < current.firstEndMs)
                ) {
                    current.firstStartMs = row.startMs
                    current.firstEndMs = row.endMs
                }
                current.totalDurationMs = saturatingAdd(current.totalDurationMs, duration)
            }
        }

        return profiles.values
            .sortedWith(
                compareBy<SpeakerProfile> { it.firstStartMs }
                    .thenBy { it.firstEndMs }
                    .thenByDescending { it.totalDurationMs }
                    // Only used when the observed timing signatures are identical.
                    .thenBy { it.rawId }
            )
            .mapIndexed { canonicalId, profile -> profile.rawId to canonicalId }
            .toMap()
    }

    /**
     * Returns the raw diarizer speaker label with the strongest time overlap with one cue.
     * Overlap wins first; confidence breaks ties, and lower IDs provide a deterministic final tie.
     */
    fun bestSpeakerForCue(
        observations: List<SpeakerObservation>,
        cueStartMs: Long,
        cueEndMs: Long,
        minimumOverlapMs: Long,
    ): Int {
        if (cueStartMs < 0L || cueEndMs <= cueStartMs || observations.isEmpty()) return -1
        val cueDurationMs = cueEndMs - cueStartMs
        val scores = HashMap<Int, CueScore>()

        for (row in observations) {
            if (row.speakerId < 0 || row.startMs < 0L || row.endMs <= row.startMs) continue
            val overlapStart = maxOf(cueStartMs, row.startMs)
            val overlapEnd = minOf(cueEndMs, row.endMs)
            if (overlapEnd <= overlapStart) continue
            val overlap = overlapEnd - overlapStart
            val score = scores.getOrPut(row.speakerId) { CueScore() }
            // Malformed/overlapping rows must not count more than the cue's full duration.
            val acceptedOverlap = minOf(overlap, (cueDurationMs - score.overlapMs).coerceAtLeast(0L))
            if (acceptedOverlap <= 0L) continue
            score.overlapMs = saturatingAdd(score.overlapMs, acceptedOverlap)
            val confidenceWeight = when {
                !row.confidence.isFinite() || row.confidence < 0f -> 0.5
                else -> row.confidence.coerceIn(0f, 1f).toDouble()
            }
            score.confidenceOverlap += acceptedOverlap.toDouble() * confidenceWeight
        }

        var bestId = -1
        var bestOverlap = -1L
        var bestConfidence = -1.0
        for ((speakerId, score) in scores) {
            val wins = score.overlapMs > bestOverlap ||
                (score.overlapMs == bestOverlap && score.confidenceOverlap > bestConfidence) ||
                (score.overlapMs == bestOverlap && score.confidenceOverlap == bestConfidence &&
                    (bestId < 0 || speakerId < bestId))
            if (wins) {
                bestId = speakerId
                bestOverlap = score.overlapMs
                bestConfidence = score.confidenceOverlap
            }
        }
        return if (bestId >= 0 && bestOverlap >= minimumOverlapMs.coerceAtLeast(0L)) bestId else -1
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE
        else (first + second).coerceAtLeast(0L)
}
