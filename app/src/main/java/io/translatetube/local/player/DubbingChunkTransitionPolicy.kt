package io.translatetube.local.player

import kotlin.math.ceil

/** Pure guards and timing rules for committing the next static audio chunk. */
internal object DubbingChunkTransitionPolicy {
    /** Start preparing the next static buffer before the current one reaches its end. */
    const val PREFETCH_LEAD_MS = 400L

    fun canCommit(
        released: Boolean,
        currentGeneration: Int,
        expectedGeneration: Int,
        currentTrackIsSource: Boolean,
    ): Boolean =
        !released && currentGeneration == expectedGeneration && currentTrackIsSource

    /**
     * AudioTrack markers are absolute positions within a static buffer. Place the marker early
     * enough to overlap next-buffer creation, accounting for the current playback rate and any
     * samples already skipped in the buffer after a late video tick.
     */
    fun prefetchMarkerPosition(
        chunkSampleCount: Int,
        sampleRate: Int,
        playbackRate: Float,
        currentHeadPosition: Int = 0,
        leadMs: Long = PREFETCH_LEAD_MS,
    ): Int {
        if (chunkSampleCount <= 0) return 0
        if (chunkSampleCount == 1) return 1
        val head = currentHeadPosition.coerceIn(0, chunkSampleCount - 1)
        val safeRate = playbackRate.takeIf { it.isFinite() && it > 0f } ?: 1f
        val leadSamples = if (sampleRate <= 0 || leadMs <= 0L) {
            1L
        } else {
            ceil(sampleRate.toDouble() * leadMs.toDouble() * safeRate.toDouble() / 1_000.0)
                .toLong()
                .coerceAtLeast(1L)
        }
        val desiredMarker = (chunkSampleCount.toLong() - leadSamples)
            .coerceAtLeast((head + 1).toLong())
            .coerceAtMost(chunkSampleCount.toLong())
        return desiredMarker.toInt()
    }
}
