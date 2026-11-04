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
    /** The complete audio does not fit the cue window; preserve it and queue crossed cues without pausing video. */
    val overrunsWindow: Boolean = false,
)

internal object DubbingTiming {
    /** Normal playback stays conservative; only queued late-cue recovery may use the higher cap. */
    const val MAX_SYNC_RATE = 1.8f
    const val MAX_CATCH_UP_SYNC_RATE = 2.1f
    const val MAX_OUTPUT_RATE = 3.0f
    private const val FADE_IN_MS = 8
    private const val FADE_OUT_MS = 30
    private const val MIN_REMAINING_WINDOW_MS = 80L
    private const val COPY_CHUNK_SAMPLES = 8_192
    private const val COPY_CHECK_MASK = 0x1fff

    /** 最后一句没有后续字幕可避让时，最多向后扩展的时长（毫秒）。 */
    const val MAX_GAP_BORROW_MS = 3_000L
    /** 借用空隙时给下一条字幕留的缓冲，避免两句配音首尾相贴。 */
    private const val NEXT_CUE_GUARD_MS = 120L

    fun normalizePlayerSpeed(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f

    /**
     * 配音实际可用的结束时间：在不碰到下一条字幕的前提下，借用本句后面的静音空隙。
     * 有后续字幕时使用全部可用间隙，并为下一句保留 [NEXT_CUE_GUARD_MS]；不能因为固定的
     * 3 秒上限而浪费更长的静音段。最后一句没有下一条开始时间，才应用 [MAX_GAP_BORROW_MS]，
     * 避免把字幕活动区间扩展到整段视频剩余时长。
     */
    fun playableEndMs(startMs: Long, endMs: Long, nextCueStartMs: Long): Long {
        if (startMs < 0L || endMs <= startMs || endMs > Long.MAX_VALUE - MAX_GAP_BORROW_MS) return endMs
        if (nextCueStartMs <= endMs + NEXT_CUE_GUARD_MS) return endMs
        val room = nextCueStartMs - endMs - NEXT_CUE_GUARD_MS
        if (room <= 0L) return endMs
        val borrow = if (nextCueStartMs == Long.MAX_VALUE) {
            minOf(room, MAX_GAP_BORROW_MS)
        } else {
            room
        }
        return endMs + borrow
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

    /**
     * Plan a late catch-up cue from its beginning. Unlike normal playback, this never discards
     * a cue merely because its original subtitle window has passed or has only a tiny remainder.
     */
    fun createCompleteCuePlan(
        sampleCount: Int,
        sampleRate: Int,
        cueDurationMs: Long,
        playerSpeed: Float,
        maxSyncRate: Float = MAX_SYNC_RATE,
        targetSyncRate: Float = 1.0f,
    ): DubbingPlaybackPlan {
        val safePlayerSpeed = normalizePlayerSpeed(playerSpeed)
        if (sampleCount <= 0 || sampleRate <= 0 || cueDurationMs <= 0L) {
            return DubbingPlaybackPlan(0, 0, 1.0f, safePlayerSpeed, false)
        }
        // Convert the validated Float through its shortest decimal representation before
        // timeline math; otherwise 2.1f becomes 2.0999999... and creates false overruns.
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(MAX_CATCH_UP_SYNC_RATE)
            ?.toString()
            ?.toDouble()
            ?: MAX_SYNC_RATE.toString().toDouble()
        val audioDurationMs = sampleCount.toDouble() * 1_000.0 / sampleRate.toDouble()
        val cueMs = cueDurationMs.toDouble()
        val safeTargetRate = targetSyncRate.takeIf { it.isFinite() && it > 0.0f }
            ?.toString()
            ?.toDouble()
            ?.coerceIn(1.0, safeMaxRate)
            ?: 1.0
        // A late queued cue needs to recover some timeline debt even when its PCM would fit at 1x.
        // Bound the requested recovery rate and never crop its PCM to force the deadline.
        val syncRateDouble = maxOf(1.0, audioDurationMs / cueMs, safeTargetRate)
            .coerceAtMost(safeMaxRate)
        val syncRate = syncRateDouble.toFloat()
        val overrunsWindow = audioDurationMs > cueMs * safeMaxRate
        val outputRate = (syncRate * safePlayerSpeed).coerceIn(0.5f, MAX_OUTPUT_RATE)
        return DubbingPlaybackPlan(
            startSample = 0,
            sampleCount = sampleCount,
            syncRate = syncRate,
            playbackRate = outputRate,
            truncated = false,
            overrunsWindow = overrunsWindow,
        )
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
        // Keep timeline calculations in Double using the decimal value the caller selected.
        // Reusing 1.8f directly here produces 1.79999995..., so floor() can skip one extra sample.
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(MAX_SYNC_RATE)
            ?.toString()
            ?.toDouble()
            ?: MAX_SYNC_RATE.toString().toDouble()
        val desiredSyncRate = audioDurationMs / lineDurationMs.toDouble()
        val syncRateDouble = maxOf(1.0, desiredSyncRate).coerceAtMost(safeMaxRate)
        val syncRate = syncRateDouble.toFloat()

        // Skip elapsed speech during normal playback. If the tail still does not fit, preserve
        // the full PCM; PlaybackService queues cues crossed before it completes rather than pausing
        // the video or dropping those cues.
        val offset = floor(elapsedMs.toDouble() * syncRateDouble * sampleRate / 1000.0)
            .toInt().coerceIn(0, sampleCount)
        val budget = ceil(remainingMs.toDouble() * syncRateDouble * sampleRate / 1000.0)
            .toInt().coerceAtLeast(0)
        val availableSamples = (sampleCount - offset).coerceAtLeast(0)
        val overrunsWindow = availableSamples > budget
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
