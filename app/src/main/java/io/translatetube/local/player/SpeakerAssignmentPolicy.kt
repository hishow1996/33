package io.translatetube.local.player

import io.translatetube.local.asr.SpeakerDiarizer

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
        var confidenceWeight: Double = 0.0,
    )

    /** A subtitle cue and the person ID previously persisted for that exact time range. */
    data class CueAnchor(
        val startMs: Long,
        val endMs: Long,
        val previousPersonId: Int?,
    )

    private const val MIN_CUE_OVERLAP_MS = 40L
    private const val MAX_CUE_OVERLAP_MS = 250L
    private const val AMBIGUOUS_OVERLAP_RATIO = 0.85
    private const val MIN_CONFIDENCE_LEAD = 0.18
    private const val MIN_STABLE_ANCHOR_OVERLAP_MS = 450L
    private const val MIN_SINGLE_CUE_STABLE_OVERLAP_MS = 1_200L
    private const val MIN_STABLE_ANCHOR_CUE_COUNT = 2
    private const val MIN_STABLE_ANCHOR_SHARE = 0.72
    private const val MIN_STABLE_ANCHOR_LEAD_RATIO = 1.25
    private const val MIN_STABLE_ANCHOR_OVERLAP_MS = 450L
    private const val MIN_SINGLE_CUE_STABLE_OVERLAP_MS = 1_200L
    private const val MIN_STABLE_ANCHOR_CUE_COUNT = 2
    private const val MIN_STABLE_ANCHOR_SHARE = 0.72
    private const val MIN_STABLE_ANCHOR_LEAD_RATIO = 1.25

    /** Convert native diarizer segments once, retaining confidence for every caller. */
    fun observationsFromSegments(segments: List<SpeakerDiarizer.Segment>): List<SpeakerObservation> =
        segments.map { segment ->
            SpeakerObservation(
                speakerId = segment.speaker,
                startMs = (segment.startSec * 1000f).toLong(),
                endMs = (segment.endSec * 1000f).toLong(),
                confidence = segment.confidence,
            )
        }

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
     * Reuse persisted person IDs only when exact cue-time anchors strongly and consistently
     * connect a new raw diarizer cluster to one previous person. This prevents a changed
     * first-speaker order from silently swapping saved voices while refusing ambiguous merges.
     */
    fun stableSpeakerIds(
        observations: List<SpeakerObservation>,
        cueAnchors: List<CueAnchor>,
    ): Map<Int, Int> {
        val canonical = canonicalSpeakerIds(observations)
        if (canonical.isEmpty() || cueAnchors.isEmpty()) return canonical

        // Scan twice rather than copying all anchors into a filtered list; the Honor 10 has
        // limited memory, and this pass runs immediately after local speaker analysis.
        var maxPreviousPersonId = -1
        for (anchor in cueAnchors) {
            val previousPersonId = anchor.previousPersonId ?: continue
            if (previousPersonId !in 0..999 ||
                anchor.startMs < 0L ||
                anchor.endMs <= anchor.startMs
            ) continue
            maxPreviousPersonId = maxOf(maxPreviousPersonId, previousPersonId)
        }
        if (maxPreviousPersonId < 0) return canonical

        val scoresByRaw = HashMap<Int, HashMap<Int, AnchorScore>>()
        val totalOverlapByRaw = HashMap<Int, Long>()
        for (anchor in cueAnchors) {
            val previousPersonId = anchor.previousPersonId ?: continue
            if (previousPersonId !in 0..999 ||
                anchor.startMs < 0L ||
                anchor.endMs <= anchor.startMs
            ) continue
            val durationMs = anchor.endMs - anchor.startMs
            val rawSpeakerId = bestSpeakerForCue(
                observations = observations,
                cueStartMs = anchor.startMs,
                cueEndMs = anchor.endMs,
                minimumOverlapMs = minimumOverlapMsForCue(durationMs),
            )
            if (rawSpeakerId !in canonical) continue

            val overlapMs = overlapMsForSpeaker(
                observations = observations,
                cueStartMs = anchor.startMs,
                cueEndMs = anchor.endMs,
                speakerId = rawSpeakerId,
            )
            if (overlapMs < minimumOverlapMsForCue(durationMs)) continue

            val score = scoresByRaw
                .getOrPut(rawSpeakerId) { HashMap() }
                .getOrPut(previousPersonId) { AnchorScore() }
            score.overlapMs = saturatingAdd(score.overlapMs, overlapMs)
            score.cueCount++
            totalOverlapByRaw[rawSpeakerId] = saturatingAdd(
                totalOverlapByRaw[rawSpeakerId] ?: 0L,
                overlapMs,
            )
        }
        if (scoresByRaw.isEmpty()) return canonical

        val candidates = mutableListOf<AnchorCandidate>()
        for ((rawSpeakerId, personScores) in scoresByRaw) {
            val ranked = personScores.entries.sortedWith(
                compareByDescending<Map.Entry<Int, AnchorScore>> { it.value.overlapMs }
                    .thenBy { it.key },
            )
            val best = ranked.firstOrNull() ?: continue
            val evidence = best.value
            val totalOverlap = totalOverlapByRaw[rawSpeakerId] ?: 0L
            val share = evidence.overlapMs.toDouble() / totalOverlap.coerceAtLeast(1L).toDouble()
            val runnerUp = ranked.getOrNull(1)?.value
            val enoughEvidence = evidence.overlapMs >= MIN_STABLE_ANCHOR_OVERLAP_MS &&
                (evidence.cueCount >= MIN_STABLE_ANCHOR_CUE_COUNT ||
                    evidence.overlapMs >= MIN_SINGLE_CUE_STABLE_OVERLAP_MS)
            val clearWinner = runnerUp == null ||
                evidence.overlapMs.toDouble() >= runnerUp.overlapMs.toDouble() *
                MIN_STABLE_ANCHOR_LEAD_RATIO
            if (!enoughEvidence || share < MIN_STABLE_ANCHOR_SHARE || !clearWinner) continue

            candidates += AnchorCandidate(
                rawSpeakerId = rawSpeakerId,
                previousPersonId = best.key,
                overlapMs = evidence.overlapMs,
                share = share,
            )
        }
        if (candidates.isEmpty()) return canonical

        // One previous person may be claimed by only one new cluster. If diarization split one
        // person into multiple raw labels, the weaker cluster receives a fresh ID rather than
        // being merged based on timing alone.
        candidates.sortWith(
            compareByDescending<AnchorCandidate> { it.share }
                .thenByDescending { it.overlapMs }
                .thenBy { it.rawSpeakerId }
                .thenBy { it.previousPersonId },
        )
        val stable = linkedMapOf<Int, Int>()
        val usedPersonIds = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.rawSpeakerId !in stable &&
                candidate.previousPersonId !in usedPersonIds
            ) {
                stable[candidate.rawSpeakerId] = candidate.previousPersonId
                usedPersonIds += candidate.previousPersonId
            }
        }
        if (stable.isEmpty()) return canonical

        // Unmatched speakers get IDs above the previous timeline's range, never an ID that
        // could accidentally refer to a different person already saved for this video.
        var nextPersonId = maxPreviousPersonId + 1
        for (rawSpeakerId in canonical.entries.sortedBy { it.value }.map { it.key }) {
            if (rawSpeakerId in stable) continue
            while (nextPersonId <= 999 && nextPersonId in usedPersonIds) nextPersonId++
            if (nextPersonId !in 0..999) continue
            stable[rawSpeakerId] = nextPersonId
            usedPersonIds += nextPersonId
            nextPersonId++
        }
        return stable
    }

    private data class AnchorScore(
        var overlapMs: Long = 0L,
        var cueCount: Int = 0,
    )

    private data class AnchorCandidate(
        val rawSpeakerId: Int,
        val previousPersonId: Int,
        val overlapMs: Long,
        val share: Double,
    )

    private fun overlapMsForSpeaker(
        observations: List<SpeakerObservation>,
        cueStartMs: Long,
        cueEndMs: Long,
        speakerId: Int,
    ): Long {
        val cueDurationMs = cueEndMs - cueStartMs
        if (speakerId < 0 || cueStartMs < 0L || cueDurationMs <= 0L) return 0L
        var totalOverlapMs = 0L
        for (row in observations) {
            if (row.speakerId != speakerId ||
                row.startMs < 0L ||
                row.endMs <= row.startMs
            ) continue
            val overlapStart = maxOf(cueStartMs, row.startMs)
            val overlapEnd = minOf(cueEndMs, row.endMs)
            if (overlapEnd <= overlapStart) continue
            val accepted = minOf(
                overlapEnd - overlapStart,
                (cueDurationMs - totalOverlapMs).coerceAtLeast(0L),
            )
            if (accepted > 0L) totalOverlapMs = saturatingAdd(totalOverlapMs, accepted)
            if (totalOverlapMs >= cueDurationMs) break
        }
        return totalOverlapMs
    }

    /**
     * Returns the raw diarizer speaker label with the strongest time overlap with one cue.
     * Overlap wins first; confidence breaks ties, and lower IDs provide a deterministic final tie.
     */
    /**
     * Require a meaningful overlap even for tiny cues, while keeping the existing 20% rule
     * and 250 ms cap for normal/longer subtitles.
     */
    fun minimumOverlapMsForCue(cueDurationMs: Long): Long {
        if (cueDurationMs <= 0L) return MIN_CUE_OVERLAP_MS
        val proportional = cueDurationMs / 5L + if (cueDurationMs % 5L == 0L) 0L else 1L
        return minOf(MAX_CUE_OVERLAP_MS, maxOf(MIN_CUE_OVERLAP_MS, proportional))
    }

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
            score.confidenceWeight = maxOf(score.confidenceWeight, confidenceWeight)
        }

        // Keep both the strongest and runner-up speaker. A near tie usually means simultaneous
        // speech or uncertain alignment; selecting an arbitrary ID would assign the wrong voice.
        var bestId = -1
        var bestScore: CueScore? = null
        var runnerUpScore: CueScore? = null
        fun outranks(
            candidateId: Int,
            candidate: CueScore,
            currentId: Int,
            current: CueScore?,
        ): Boolean {
            if (current == null) return true
            return candidate.overlapMs > current.overlapMs ||
                (candidate.overlapMs == current.overlapMs &&
                    candidate.confidenceWeight > current.confidenceWeight) ||
                (candidate.overlapMs == current.overlapMs &&
                    candidate.confidenceWeight == current.confidenceWeight &&
                    (currentId < 0 || candidateId < currentId))
        }
        var runnerUpId = -1
        for ((speakerId, score) in scores) {
            if (outranks(speakerId, score, bestId, bestScore)) {
                runnerUpScore = bestScore
                runnerUpId = bestId
                bestScore = score
                bestId = speakerId
            } else if (outranks(speakerId, score, runnerUpId, runnerUpScore)) {
                runnerUpScore = score
                runnerUpId = speakerId
            }
        }

        val requiredOverlap = minimumOverlapMs.coerceAtLeast(0L)
        val winner = bestScore ?: return -1
        if (bestId < 0 || winner.overlapMs < requiredOverlap) return -1

        val runnerUp = runnerUpScore
        if (runnerUp != null && runnerUp.overlapMs >= requiredOverlap) {
            val overlapRatio = runnerUp.overlapMs.toDouble() / winner.overlapMs.coerceAtLeast(1L)
            val confidenceLead = winner.confidenceWeight - runnerUp.confidenceWeight
            if (overlapRatio >= AMBIGUOUS_OVERLAP_RATIO &&
                confidenceLead < MIN_CONFIDENCE_LEAD
            ) {
                return -1
            }
        }
        return bestId
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE
        else (first + second).coerceAtLeast(0L)
}
