package io.translatetube.local.player

/** Snapshot of playback intent before replacing a YouTube quality source. */
internal data class PlayerQualityResumeState(
    val positionMs: Long,
    val wasPlaying: Boolean,
)

internal object PlayerQualitySwitchPolicy {
    fun capture(positionMs: Long, playWhenReady: Boolean): PlayerQualityResumeState =
        PlayerQualityResumeState(
            positionMs = positionMs.coerceAtLeast(0L),
            wasPlaying = playWhenReady,
        )
}
