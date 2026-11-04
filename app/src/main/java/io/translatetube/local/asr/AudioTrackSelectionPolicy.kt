package io.translatetube.local.asr

/**
 * Chooses the most likely main audio track without assuming that the first track is primary.
 *
 * Explicit default-track metadata wins; channel count is only a fallback when the container
 * does not expose a default flag. The original track order is retained as the final tie-breaker.
 */
internal object AudioTrackSelectionPolicy {
    data class Candidate(
        val index: Int,
        val isDefault: Boolean,
        val channelCount: Int,
    )

    fun choose(candidates: List<Candidate>): Int? =
        candidates.asSequence()
            .filter { it.index >= 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.isDefault }
                    .thenByDescending { it.channelCount.coerceIn(1, 8) }
                    .thenBy { it.index }
            )
            .firstOrNull()
            ?.index
}
