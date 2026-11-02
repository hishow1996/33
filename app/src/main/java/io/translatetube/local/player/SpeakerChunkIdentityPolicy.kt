package io.translatetube.local.player

/**
 * Reconciles the local speaker labels produced by adjacent diarization windows.
 *
 * IDs emitted by a diarizer are local to one invocation. Only a strong, one-to-one match in the
 * audio overlap is allowed to carry an earlier person ID into the next window. Ambiguous or
 * unsupported matches receive a new ID rather than silently merging two different people.
 */
internal object SpeakerChunkIdentityPolicy {
    data class Segment(
        val speakerId: Int,
        val startMs: Long,
        val endMs: Long,
        val confidence: Float = Float.NaN,
    )

    data class MergeResult(
        val segments: List<Segment>,
        val nextSpeakerId: Int,
        val linkedSpeakerCount: Int,
    )

    private data class SpeakerWindowStats(
        var durationMs: Long = 0L,
    )

    private data class Candidate(
        val currentId: Int,
        val previousId: Int,
        val overlapMs: Long,
        val score: Double,
    )

    private const val MIN_MATCH_OVERLAP_MS = 1_000L
    private const val MIN_MATCH_SHARE = 0.58
    private const val MIN_MATCH_LEAD = 1.2

    fun normalizeFirstWindow(current: List<Segment>): MergeResult {
        val firstBySpeaker = linkedMapOf<Int, Long>()
        current.forEach { segment ->
            if (segment.speakerId >= 0 && segment.endMs > segment.startMs) {
                val old = firstBySpeaker[segment.speakerId]
                if (old == null || segment.startMs < old) firstBySpeaker[segment.speakerId] = segment.startMs
            }
        }
        require(firstBySpeaker.size <= 1_000) { "检测到的人物数量超过安全上限" }
        val mapping = firstBySpeaker.entries
            .sortedWith(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .mapIndexed { index, entry -> entry.key to index }
            .toMap()
        val normalized = current.mapNotNull { segment ->
            val id = mapping[segment.speakerId] ?: return@mapNotNull null
            if (segment.endMs <= segment.startMs) return@mapNotNull null
            segment.copy(speakerId = id)
        }
        return MergeResult(normalized, mapping.size, 0)
    }

    fun mergeWindow(
        previous: List<Segment>,
        current: List<Segment>,
        overlapStartMs: Long,
        overlapEndMs: Long,
        nextSpeakerId: Int,
    ): MergeResult {
        require(nextSpeakerId in 0..1_000) { "说话人编号超出安全范围" }
        if (current.isEmpty()) return MergeResult(emptyList(), nextSpeakerId, 0)

        val previousStats = collectStats(previous, overlapStartMs, overlapEndMs)
        val currentStats = collectStats(current, overlapStartMs, overlapEndMs)
        val overlapByPair = HashMap<Pair<Int, Int>, Long>()

        if (overlapEndMs > overlapStartMs && previousStats.isNotEmpty() && currentStats.isNotEmpty()) {
            for (old in previous) {
                if (old.speakerId !in previousStats) continue
                for (fresh in current) {
                    if (fresh.speakerId !in currentStats) continue
                    val overlap = intersectionMs(old, fresh, overlapStartMs, overlapEndMs)
                    if (overlap <= 0L) continue
                    val key = fresh.speakerId to old.speakerId
                    overlapByPair[key] = saturatingAdd(overlapByPair[key] ?: 0L, overlap)
                }
            }
        }

        val candidates = ArrayList<Candidate>()
        overlapByPair.forEach { (pair, overlap) ->
            val currentDuration = currentStats[pair.first]?.durationMs ?: 0L
            val previousDuration = previousStats[pair.second]?.durationMs ?: 0L
            val smallerDuration = minOf(currentDuration, previousDuration)
            if (overlap < MIN_MATCH_OVERLAP_MS || smallerDuration <= 0L) return@forEach
            val currentShare = overlap.toDouble() / currentDuration.toDouble()
            val previousShare = overlap.toDouble() / previousDuration.toDouble()
            val score = minOf(currentShare, previousShare)
            if (score < MIN_MATCH_SHARE) return@forEach
            candidates += Candidate(pair.first, pair.second, overlap, score)
        }

        // A pair must be the clear best match from both directions. Otherwise preserve
        // separate IDs instead of merging ambiguous or overlapping voices.
        val accepted = candidates.filter { candidate ->
            val currentAlternative = candidates
                .filter { it.currentId == candidate.currentId && it.previousId != candidate.previousId }
                .maxOfOrNull { it.score } ?: 0.0
            val previousAlternative = candidates
                .filter { it.previousId == candidate.previousId && it.currentId != candidate.currentId }
                .maxOfOrNull { it.score } ?: 0.0
            candidate.score >= currentAlternative * MIN_MATCH_LEAD &&
                candidate.score >= previousAlternative * MIN_MATCH_LEAD
        }.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.overlapMs }
                .thenBy { it.currentId },
        )

        val mapping = HashMap<Int, Int>()
        val usedPrevious = HashSet<Int>()
        accepted.forEach { candidate ->
            if (candidate.currentId !in mapping && usedPrevious.add(candidate.previousId)) {
                mapping[candidate.currentId] = candidate.previousId
            }
        }

        val newSpeakers = current.asSequence()
            .filter { it.speakerId >= 0 && it.endMs > it.startMs && it.speakerId !in mapping }
            .groupBy { it.speakerId }
            .mapValues { (_, segments) -> segments.minOf { it.startMs } }
            .entries
            .sortedWith(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
        var nextId = nextSpeakerId
        newSpeakers.forEach { (rawId, _) ->
            require(nextId in 0..999) { "检测到的人物数量超过安全上限" }
            mapping[rawId] = nextId++
        }

        val merged = ArrayList<Segment>(current.size)
        current.forEach { segment ->
            val globalId = mapping[segment.speakerId] ?: return@forEach
            if (segment.endMs <= overlapEndMs) return@forEach
            val clippedStart = maxOf(segment.startMs, overlapEndMs)
            if (segment.endMs > clippedStart) {
                merged += segment.copy(
                    speakerId = globalId,
                    startMs = clippedStart,
                )
            }
        }
        return MergeResult(
            segments = merged,
            nextSpeakerId = nextId,
            linkedSpeakerCount = accepted.count { mapping[it.currentId] == it.previousId },
        )
    }

    private fun collectStats(
        segments: List<Segment>,
        overlapStartMs: Long,
        overlapEndMs: Long,
    ): Map<Int, SpeakerWindowStats> {
        val result = HashMap<Int, SpeakerWindowStats>()
        if (overlapEndMs <= overlapStartMs) return result
        segments.forEach { segment ->
            if (segment.speakerId < 0 || segment.endMs <= segment.startMs) return@forEach
            val start = maxOf(segment.startMs, overlapStartMs)
            val end = minOf(segment.endMs, overlapEndMs)
            if (end <= start) return@forEach
            val stats = result.getOrPut(segment.speakerId) { SpeakerWindowStats() }
            stats.durationMs = saturatingAdd(stats.durationMs, end - start)
        }
        return result
    }

    private fun intersectionMs(
        first: Segment,
        second: Segment,
        clipStartMs: Long,
        clipEndMs: Long,
    ): Long {
        val start = maxOf(first.startMs, second.startMs, clipStartMs)
        val end = minOf(first.endMs, second.endMs, clipEndMs)
        return (end - start).coerceAtLeast(0L)
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}
