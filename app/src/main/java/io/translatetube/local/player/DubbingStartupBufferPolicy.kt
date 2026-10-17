package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Chooses a startup PCM buffer from actual upcoming speech and measured synthesis speed. */
internal object DubbingStartupBufferPolicy {
    data class Plan(
        val lineIndices: List<Int>,
        val lookAheadMs: Long,
        val minimumCueCount: Int,
        val waitTimeoutMs: Long,
        /** Switch to full synthesis before playback when repeated measurements show a severe lag. */
        val waitForAllBeforePlayback: Boolean = false,
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
        synthesisRealtimeFactor = synthesisRealtimeFactor,
        synthesisSampleCount = synthesisSampleCount,
    )

    fun plan(
        lines: List<SubLine>,
        spokenCueIndices: List<Int>,
        immediateMode: Boolean,
        synthesisRealtimeFactor: Float = Float.NaN,
        synthesisSampleCount: Int = 0,
    ): Plan {
        val spoken = spokenCueIndices
        if (spoken.isEmpty()) return Plan(emptyList(), 0L, 0, 0L)
        if (immediateMode) {
            // Direct TTS has no PCM backlog to fill. The first usable cue is enough to start.
            return Plan(spoken.take(1), 0L, 1, 2_500L)
        }

        // Two or more measured cues whose synthesis consumes >=2x the interval until the next
        // speech cue indicate a sustained timeline bottleneck. In that case, don't launch video into recurring gaps:
        // finish the generated dub first. The service keeps this decision sticky for the session.
        if (
            synthesisSampleCount >= 2 &&
            synthesisRealtimeFactor.isFinite() &&
            synthesisRealtimeFactor >= 2.0f
        ) {
            return Plan(
                lineIndices = spoken,
                lookAheadMs = 30_000L,
                minimumCueCount = spoken.size,
                waitTimeoutMs = 0L,
                waitForAllBeforePlayback = true,
            )
        }

        // Escalate the lead buffer as measured synthesis falls behind. The moderate/slow tiers wait
        // for more ready cues before starting; only a sustained severe lag escalates to full synthesis.
        val (lookAheadMs, minCues, maxCues, timeoutMs) = when {
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor >= 1.6f ->
                Quadruple(20_000L, 8, 12, 45_000L)
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor >= 1.2f ->
                Quadruple(15_000L, 6, 10, 35_000L)
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor < 0.75f ->
                Quadruple(5_000L, 2, 4, 20_000L)
            else -> Quadruple(8_000L, 3, 6, 25_000L)
        }
        val firstStart = lines[spoken.first()].startMs
        val inWindow = spoken.filter { lines[it].startMs <= firstStart + lookAheadMs }
            .take(maxCues)
        val minimum = minOf(minCues, spoken.size)
        val selected = if (inWindow.size >= minimum) {
            inWindow
        } else {
            spoken.take(minOf(maxOf(minimum, inWindow.size), maxCues))
        }
        return Plan(
            lineIndices = selected,
            lookAheadMs = lookAheadMs,
            minimumCueCount = minimum,
            waitTimeoutMs = timeoutMs,
        )
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
