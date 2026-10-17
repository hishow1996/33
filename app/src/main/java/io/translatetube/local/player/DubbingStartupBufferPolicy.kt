package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Chooses a startup buffer from actual upcoming speech and measured synthesis speed. */
internal object DubbingStartupBufferPolicy {
    data class Plan(
        val lineIndices: List<Int>,
        val lookAheadMs: Long,
        val minimumCueCount: Int,
        val waitTimeoutMs: Long,
        /** If true, the service waits for all speech rows to become terminally ready before playback. */
        val waitForAllBeforePlayback: Boolean = false,
    )

    private data class BufferTier(
        val lookAheadMs: Long,
        val minimumCues: Int,
        val maximumCues: Int,
        val waitTimeoutMs: Long,
    )

    fun spokenCueIndices(lines: List<SubLine>): List<Int> =
        lines.indices.filter { index ->
            val line = lines[index]
            line.startMs >= 0L && line.endMs > line.startMs && !line.zh.isNullOrBlank()
        }

    fun plan(
        lines: List<SubLine>,
        immediateMode: Boolean,
        synthesisTimelineLagFactor: Float = Float.NaN,
        synthesisSampleCount: Int = 0,
    ): Plan = plan(
        lines = lines,
        spokenCueIndices = spokenCueIndices(lines),
        immediateMode = immediateMode,
        synthesisTimelineLagFactor = synthesisTimelineLagFactor,
        synthesisSampleCount = synthesisSampleCount,
    )

    fun plan(
        lines: List<SubLine>,
        spokenCueIndices: List<Int>,
        immediateMode: Boolean,
        synthesisTimelineLagFactor: Float = Float.NaN,
        synthesisSampleCount: Int = 0,
    ): Plan {
        val spoken = spokenCueIndices
        if (spoken.isEmpty()) return Plan(emptyList(), 0L, 0, 0L)

        if (immediateMode) {
            // Direct TTS has no PCM backlog to fill. The first usable cue is enough to start.
            return Plan(spoken.take(1), 0L, 1, 2_500L)
        }

        // The lag factor compares synthesis time to the timeline interval before the next spoken cue.
        // After at least two measurements, a sustained factor >=2 means the queue cannot catch up
        // during normal playback. In that case, finish preparing all speech cues before video starts.
        if (
            synthesisSampleCount >= 2 &&
            synthesisTimelineLagFactor.isFinite() &&
            synthesisTimelineLagFactor >= 2.0f
        ) {
            return Plan(
                lineIndices = spoken,
                lookAheadMs = 30_000L,
                minimumCueCount = spoken.size,
                waitTimeoutMs = 0L,
                waitForAllBeforePlayback = true,
            )
        }

        // For moderate/slow synthesis, accumulate a larger lead before streaming; fast synthesis
        // can start with a shorter lead. Full-synthesis mode is reserved for repeated severe lag.
        val tier = when {
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.6f ->
                BufferTier(20_000L, 8, 12, 45_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.2f ->
                BufferTier(15_000L, 6, 10, 35_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor < 0.75f ->
                BufferTier(5_000L, 2, 4, 20_000L)
            else -> BufferTier(8_000L, 3, 6, 25_000L)
        }

        val firstStart = lines[spoken.first()].startMs
        val inWindow = spoken
            .filter { lines[it].startMs <= firstStart + tier.lookAheadMs }
            .take(tier.maximumCues)
        val minimum = minOf(tier.minimumCues, spoken.size)
        val selected = if (inWindow.size >= minimum) {
            inWindow
        } else {
            spoken.take(minOf(maxOf(minimum, inWindow.size), tier.maximumCues))
        }

        return Plan(
            lineIndices = selected,
            lookAheadMs = tier.lookAheadMs,
            minimumCueCount = minimum,
            waitTimeoutMs = tier.waitTimeoutMs,
        )
    }
}
