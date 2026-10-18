package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/**
 * Pure timeline math shared by real-time dubbing and JVM unit tests.
 * Subtitle times and positionMs are media-timeline milliseconds.
 */
internal data class DubbingPlaybackPlan(
    val startSample: Int,
    val sampleCount: Int,
    val syncRate: Float,
    val playbackRate: Float,
    val truncated: Boolean,
    /** Audio is longer than the safe timeline rate; preserve its tail and defer the next cue. */
    val overrunsWindow: Boolean = false,
)

internal object DubbingTiming {
    const val MAX_SYNC_RATE = 1.35f
    const val MAX_OUTPUT_RATE = 3.0f
    private const val FADE_IN_MS = 8
    private const val FADE_OUT_MS = 30
    private const val MIN_REMAINING_WINDOW_MS = 80L
    private const val COPY_CHUNK_SAMPLES = 8_192
    private const val COPY_CHECK_MASK = 0x1fff

    /** 一句配音最多向后借用的字幕空隙（毫秒）。 */
    const val MAX_GAP_BORROW_MS = 3_000L
    /** 借用空隙时给下一条字幕留的缓冲，避免两句配音首尾相贴。 */
    private const val NEXT_CUE_GUARD_MS = 120L

    fun normalizePlayerSpeed(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f

    /**
     * 配音实际可用的结束时间：在不碰到下一条字幕的前提下，借用本句字幕后面的静音空隙。
     * 译文常比原文长，只按字幕自己的时间窗播放就得加速甚至截断；有空隙时多给一点时间，
     * 语速更自然。空隙不够（或后一条字幕与本条重叠）时原样返回，借用量最多 [MAX_GAP_BORROW_MS]。
     */
    fun playableEndMs(startMs: Long, endMs: Long, nextCueStartMs: Long): Long {
        if (startMs < 0L || endMs <= startMs || endMs > Long.MAX_VALUE - MAX_GAP_BORROW_MS) return endMs
        val room = nextCueStartMs - NEXT_CUE_GUARD_MS - endMs
        if (room <= 0L) return endMs
        return endMs + minOf(room, MAX_GAP_BORROW_MS)
    }

    /**
     * 给每条有译文的字幕套上 [playableEndMs] 的时间窗；其余字幕、顺序、下标都不变。
     * "下一条字幕"取所有可播放字幕里严格晚于本条开始的最早开始时间（含没有译文的），
     * 保证借用的空隙里不会有任何别的字幕。
     */
    fun extendCueWindows(lines: List<SubLine>): List<SubLine> {
        val starts = LongArray(lines.size)
        var count = 0
        for (line in lines) {
            if (DubbingTimeline.isPlayableCue(line)) starts[count++] = line.startMs
        }
        java.util.Arrays.sort(starts, 0, count)
        return lines.map { line ->
            if (!DubbingTimeline.isPlayableCue(line) || line.zh.isNullOrBlank()) return@map line
            var low = 0
            var high = count
            while (low < high) {
                val middle = (low + high) ushr 1
                if (starts[middle] <= line.startMs) low = middle + 1 else high = middle
            }
            val nextStart = if (low < count) starts[low] else Long.MAX_VALUE
            val end = playableEndMs(line.startMs, line.endMs, nextStart)
            if (end == line.endMs) line else line.copy(endMs = end)
        }
    }

    fun createPlan(
        sampleCount: Int,
        sampleRate: Int,
        lineStartMs: Long,
        lineEndMs: Long,
        positionMs: Long,
        playerSpeed: Float,
        maxSyncRate: Float = MAX_SYNC_RATE,
    ): DubbingPlaybackPlan {
        val safePlayerSpeed = normalizePlayerSpeed(playerSpeed)
        if (sampleCount <= 0 || sampleRate <= 0 || lineStartMs < 0L || lineEndMs <= lineStartMs) {
            return DubbingPlaybackPlan(0, 0, 1.0f, safePlayerSpeed, false)
        }

        // A stale caller must not start this cue early when a seek lands before its start.
        // A playhead at/after the end is terminal too; preserve the end offset for diagnostics.
        if (positionMs < lineStartMs) {
            return DubbingPlaybackPlan(0, 0, 1.0f, safePlayerSpeed, false)
        }
        if (positionMs >= lineEndMs) {
            return DubbingPlaybackPlan(sampleCount, 0, 1.0f, safePlayerSpeed, false)
        }

        // Media timestamps are non-negative and position is now inside the cue, so both
        // subtractions are non-negative and cannot overflow Long.
        val lineDurationMs = lineEndMs - lineStartMs
        val elapsedMs = positionMs - lineStartMs
        val remainingMs = lineDurationMs - elapsedMs
        if (remainingMs < MIN_REMAINING_WINDOW_MS) {
            // A tiny late fragment is usually unintelligible; skip it instead of playing a
            // clipped syllable immediately before the next subtitle cue.
            return DubbingPlaybackPlan(sampleCount, 0, 1.0f, safePlayerSpeed, false)
        }

        val audioDurationMs = sampleCount.toDouble() * 1000.0 / sampleRate
        // The output rate includes both subtitle-window synchronization and the user's video speed.
        // Cap the components together so a fast video does not silently clip a long utterance.
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(MAX_SYNC_RATE)
            ?: MAX_SYNC_RATE
        val desiredSyncRate = audioDurationMs / lineDurationMs.toDouble()
        val syncRate = maxOf(1.0, desiredSyncRate)
            .coerceAtMost(safeMaxRate.toDouble()).toFloat()

        // Skip elapsed speech for late starts, then determine whether the remaining tail can fit
        // in the remaining media window at the safe rate. If it cannot, preserve that tail and
        // let the playback scheduler finish this sentence before advancing to another cue.
        val offset = floor(elapsedMs.toDouble() * syncRate * sampleRate / 1000.0)
            .toInt().coerceIn(0, sampleCount)
        val budget = ceil(remainingMs.toDouble() * syncRate * sampleRate / 1000.0)
            .toInt().coerceAtLeast(0)
        val availableSamples = (sampleCount - offset).coerceAtLeast(0)
        val overrunsWindow = desiredSyncRate > safeMaxRate.toDouble() && availableSamples > budget
        val count = if (overrunsWindow) availableSamples else min(availableSamples, budget).coerceAtLeast(0)
        val truncated = offset + count < sampleCount
        val outputRate = (syncRate * safePlayerSpeed).coerceIn(0.5f, MAX_OUTPUT_RATE)
        return DubbingPlaybackPlan(offset, count, syncRate, outputRate, truncated, overrunsWindow)
    }

    /**
     * Samples to skip when static PCM has been prepared at [preparedAtMs] but the video
     * playhead advances before AudioTrack starts. This preserves subtitle-time alignment
     * without rebuilding the already-filled static track.
     */
    fun lateStartSkipSamples(
        preparedAtMs: Long,
        currentPositionMs: Long,
        syncRate: Float,
        sampleRate: Int,
        availableSampleCount: Int,
    ): Int {
        if (availableSampleCount <= 0 || sampleRate <= 0) return 0
        val safeSyncRate = syncRate.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val prepared = preparedAtMs.coerceAtLeast(0L)
        val current = currentPositionMs.coerceAtLeast(0L)
        val elapsedMs = (current - prepared).coerceAtLeast(0L)
        val samplesToSkip = floor(
            elapsedMs.toDouble() * safeSyncRate.toDouble() * sampleRate.toDouble() / 1000.0
        ).toInt()
        return samplesToSkip.coerceIn(0, availableSampleCount)
    }

    /** Copy the exact playable window; fade an overlong tail to avoid a hard cut. */
    fun slice(
        samples: FloatArray,
        plan: DubbingPlaybackPlan,
        sampleRate: Int,
        checkActive: () -> Unit = {},
    ): FloatArray {
        checkActive()
        if (plan.sampleCount <= 0 || plan.startSample !in 0 until samples.size) return FloatArray(0)
        val end = (plan.startSample.toLong() + plan.sampleCount)
            .coerceAtMost(samples.size.toLong()).toInt()
        if (end <= plan.startSample) return FloatArray(0)
        // The common path plays the entire cached cue unmodified. Reuse its array instead
        // of duplicating up to 32 MB on the heap before AudioTrack copies it to native memory.
        if (plan.startSample == 0 && end == samples.size && !plan.truncated) return samples
        // Copy in bounded blocks so a seek or engine replacement does not wait for a monolithic copy.
        val result = FloatArray(end - plan.startSample)
        var copied = 0
        while (copied < result.size) {
            checkActive()
            val count = min(COPY_CHUNK_SAMPLES, result.size - copied)
            samples.copyInto(result, copied, plan.startSample + copied, plan.startSample + copied + count)
            copied += count
        }
        if (plan.startSample > 0 && sampleRate > 0) {
            val fadeSamples = min(sampleRate * FADE_IN_MS / 1_000, result.size / 2)
            if (fadeSamples > 1) {
                for (i in 0 until fadeSamples) {
                    if ((i and COPY_CHECK_MASK) == 0) checkActive()
                    val gain = i.toDouble() / (fadeSamples - 1).toDouble()
                    result[i] = (result[i] * gain).toFloat()
                }
            }
        }
        if (plan.truncated && sampleRate > 0) {
            val fadeSamples = min(sampleRate * FADE_OUT_MS / 1_000, result.size / 2)
            if (fadeSamples > 1) {
                val fadeStart = result.size - fadeSamples
                for (i in 0 until fadeSamples) {
                    if ((i and COPY_CHECK_MASK) == 0) checkActive()
                    val progress = (i + 1).toDouble() / fadeSamples
                    val gain = 0.5 * (1.0 + cos(PI * progress))
                    result[fadeStart + i] = (result[fadeStart + i] * gain).toFloat()
                }
            }
        }
        checkActive()
        return result
    }
}
