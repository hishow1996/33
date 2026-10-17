package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Chooses a startup PCM buffer from actual upcoming speech and measured synthesis speed. */
internal object DubbingStartupBufferPolicy {
    data class Plan(
        val lineIndices: List<Int>,
        val lookAheadMs: Long,
        val minimumCueCount: Int,
        val waitTimeoutMs: Long,
    )

    fun plan(
        lines: List<SubLine>,
        immediateMode: Boolean,
        synthesisRealtimeFactor: Float = Float.NaN,
    ): Plan {
        val spoken = lines.indices.filter { index ->
            val line = lines[index]
            line.startMs >= 0L && line.endMs > line.startMs && !line.zh.isNullOrBlank()
        }
        if (spoken.isEmpty()) return Plan(emptyList(), 0L, 0, 0L)
        if (immediateMode) {
            // Direct TTS has no PCM backlog to fill. The first usable cue is enough to start.
            return Plan(spoken.take(1), 0L, 1, 2_500L)
        }

        // The initial buffer is modest. As measurements arrive, use a deeper buffer for slow
        // voices/devices so that a short startup lead can absorb temporary synthesis stalls.
        val (lookAheadMs, minCues, maxCues) = when {
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor >= 1.6f ->
                Triple(12_000L, 6, 8)
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor >= 1.2f ->
                Triple(10_000L, 5, 7)
            synthesisRealtimeFactor.isFinite() && synthesisRealtimeFactor < 0.75f ->
                Triple(5_000L, 2, 4)
            else -> Triple(8_000L, 3, 6)
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
            waitTimeoutMs = 15_000L,
        )
    }
}
