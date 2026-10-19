package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Chooses a startup buffer from actual upcoming speech and measured synthesis speed. */
internal object DubbingStartupBufferPolicy {
    data class Plan(
        val lineIndices: List<Int>,
        val lookAheadMs: Long,
        val minimumCueCount: Int,
        val waitTimeoutMs: Long,
        /** Compatibility flag; this policy never waits for the complete video before playback. */
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

    @Suppress("UNUSED_PARAMETER")
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

        // Even when synthesis is severely slower than playback, never require the whole video
        // to finish synthesizing before playback starts. Keep a small first-cue buffer and let
        // not-yet-ready cues fall back to source audio while background synthesis continues.
        // Keep enough lead for upcoming cues, but cap the startup wait. Playback continues with
        // source audio for any cue that is not ready when this bounded wait expires.
        val tier = when {
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.6f ->
                BufferTier(12_000L, 2, 5, 3_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.2f ->
                BufferTier(10_000L, 2, 5, 3_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor < 0.75f ->
                BufferTier(5_000L, 2, 4, 2_500L)
            else -> BufferTier(8_000L, 3, 6, 3_000L)
        }

        val firstStart = lines[spoken.first()].startMs
        val windowEnd = firstStart + tier.lookAheadMs
        val inWindow = ArrayList<Int>(tier.maximumCues)
        // Subtitle rows are already timeline-ordered. Stop as soon as the window is full or passed;
        // avoid allocating/filtering the entire subtitle list on every startup-buffer poll.
        for (index in spoken) {
            if (lines[index].startMs > windowEnd) break
            inWindow.add(index)
            if (inWindow.size >= tier.maximumCues) break
        }
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
