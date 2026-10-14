package io.translatetube.local.player

/**
 * Converts ephemeral diarizer cluster labels into IDs that follow first speaker appearance.
 *
 * Open-source diarization pipelines such as SoniTranslate normalize speaker labels in encounter
 * order before later translation/TTS stages use them. sherpa-onnx IDs are likewise labels for the
 * current inference only; callers should not persist the raw IDs as long-lived person identities.
 */
internal object SpeakerAssignmentPolicy {
    data class SpeakerObservation(
        val speakerId: Int,
        val startMs: Long,
        val endMs: Long,
    )

    private data class SpeakerProfile(
        val rawId: Int,
        val firstStartMs: Long,
        val firstEndMs: Long,
        val totalDurationMs: Long,
    )

    fun canonicalSpeakerIds(observations: List<SpeakerObservation>): Map<Int, Int> {
        if (observations.isEmpty()) return emptyMap()

        val profiles = observations
            .asSequence()
            .filter { it.speakerId >= 0 && it.startMs >= 0L && it.endMs > it.startMs }
            .groupBy { it.speakerId }
            .map { (speakerId, rows) ->
                val first = rows.minWithOrNull(
                    compareBy<SpeakerObservation> { it.startMs }.thenBy { it.endMs }
                )!!
                val totalDurationMs = rows.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
                SpeakerProfile(
                    rawId = speakerId,
                    firstStartMs = first.startMs,
                    firstEndMs = first.endMs,
                    totalDurationMs = totalDurationMs,
                )
            }
            .sortedWith(
                compareBy<SpeakerProfile> { it.firstStartMs }
                    .thenBy { it.firstEndMs }
                    .thenByDescending { it.totalDurationMs }
                    // Only used when the observed timing signatures are identical.
                    .thenBy { it.rawId }
            )

        return profiles.mapIndexed { canonicalId, profile ->
            profile.rawId to canonicalId
        }.toMap()
    }
}
