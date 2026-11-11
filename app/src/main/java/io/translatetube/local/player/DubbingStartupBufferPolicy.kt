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
            line.startMs >= 0L && line.endMs > line.startMs &&
                io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(line.zh.orEmpty()) &&
                !io.translatetube.local.translate.SubtitleSoundDescriptionPolicy.isSoundDescription(line.text)
        }

    /**
     * The speech cues playback will actually reach first. Resuming mid-video must not wait for (or
     * promote) the cues at the very start of the video; count from the cue playing at, or next
     * after, [startPositionMs]. Empty when no speech cue remains after that position.
     */
    fun spokenCuesFrom(lines: List<SubLine>, spoken: List<Int>, startPositionMs: Long): List<Int> {
        if (startPositionMs <= 0L) return spoken
        val from = spoken.indexOfFirst { lines[it].endMs > startPositionMs }
        return if (from < 0) emptyList() else spoken.subList(from, spoken.size)
    }

    /**
     * Where playback will start after processing: the saved watch position for a resumed video,
     * otherwise the beginning. Must stay the same rule PlaybackService applies when it starts
     * playback (resume only past 10 s and not within the last 30 s).
     */
    fun playbackStartMs(restartFromBeginning: Boolean, watchedSec: Long, durationSec: Long): Long {
        if (restartFromBeginning) return 0L
        val durationMs = durationSec * 1000L
        val resume = watchedSec > 10L && (durationMs <= 0L || watchedSec * 1000L < durationMs - 30_000L)
        return if (resume) watchedSec * 1000L else 0L
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

        // Start only after seven upcoming cues have been processed; prepare an eighth when it
        // fits in the look-ahead window. The remaining video queue continues in the background.
        // Slower engines get a wider timeline window and a longer bounded startup wait.
        val tier = when {
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.6f ->
                BufferTier(24_000L, 7, 8, 12_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor >= 1.2f ->
                BufferTier(20_000L, 7, 8, 10_000L)
            synthesisTimelineLagFactor.isFinite() && synthesisTimelineLagFactor < 0.75f ->
                BufferTier(14_000L, 7, 8, 8_000L)
            else -> BufferTier(16_000L, 7, 8, 9_000L)
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
