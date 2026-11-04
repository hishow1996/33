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
     * Android 9/OEM static tracks can stop advancing the playback-head counter just short of
     * the end. Permit a boundary hand-off only when the remaining tail is both <= maxTailMs and
     * <= 10% of this chunk; a genuine mid-buffer stall still fails and is reported.
     */
    fun canAdvanceAfterNearEndStall(
        expectedSampleCount: Long,
        playedSampleCount: Long,
        sampleRate: Int,
        maxTailMs: Long = 100L,
    ): Boolean {
        if (expectedSampleCount <= 0L || playedSampleCount < 0L ||
            sampleRate <= 0 || maxTailMs <= 0L
        ) return false
        // Short chunks are not eligible: for them, the allowed tail would be a large part of
        // the subtitle and could hide a real failure instead of recovering a boundary stall.
        if (expectedSampleCount < sampleRate.toLong() / 4L) return false

        val timeBoundSamples = ceil(sampleRate.toDouble() * maxTailMs.toDouble() / 1_000.0).toLong()
        val ratioBoundSamples = ceil(expectedSampleCount.toDouble() * 0.10).toLong()
        val toleratedTailSamples = minOf(timeBoundSamples, ratioBoundSamples)
        return playedSampleCount >= (expectedSampleCount - toleratedTailSamples).coerceAtLeast(0L)
    }

    /**
     * AudioTrack markers are absolute positions within a static buffer. Place the marker early
     * enough to overlap next-buffer creation, accounting for the current playback rate and any
     * samples already skipped in the buffer after a late video tick.
     */
    /**
     * If an OEM rejects an early marker, retry at the buffer end so long-utterance playback
     * still has a chance to advance. Marker positions must be unique and stay within the buffer.
     */
    fun markerPositionCandidates(
        chunkSampleCount: Int,
        sampleRate: Int,
        playbackRate: Float,
        currentHeadPosition: Int = 0,
        leadMs: Long = PREFETCH_LEAD_MS,
    ): List<Int> {
        if (chunkSampleCount <= 0) return emptyList()
        val early = prefetchMarkerPosition(
            chunkSampleCount = chunkSampleCount,
            sampleRate = sampleRate,
            playbackRate = playbackRate,
            currentHeadPosition = currentHeadPosition,
            leadMs = leadMs,
        )
        return if (early in 1 until chunkSampleCount) listOf(early, chunkSampleCount)
        else listOf(chunkSampleCount)
    }

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
