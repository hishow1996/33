package io.translatetube.local.player

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
)

internal object DubbingTiming {
    const val MAX_SYNC_RATE = 2.0f
    private const val MAX_OUTPUT_RATE = 4.0f
    private const val FADE_IN_MS = 8
    private const val FADE_OUT_MS = 30
    private const val MIN_REMAINING_WINDOW_MS = 80L

    fun normalizePlayerSpeed(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f

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
        // The actual AudioTrack rate is capped at MAX_OUTPUT_RATE and player speed can reach
        // 2x. Keep this synchronization component bounded too, or math would assume faster
        // playback than the audio sink can deliver and subtitle timing would drift.
        val safeMaxRate = maxSyncRate
            .takeIf { it.isFinite() && it >= 1.0f }
            ?.coerceAtMost(MAX_SYNC_RATE)
            ?: MAX_SYNC_RATE
        val syncRate = maxOf(1.0, audioDurationMs / lineDurationMs.toDouble())
            .coerceAtMost(safeMaxRate.toDouble()).toFloat()

        // Skip speech that belongs to elapsed video time (for late TTS or a seek into a cue).
        val offset = floor(elapsedMs.toDouble() * syncRate * sampleRate / 1000.0)
            .toInt().coerceIn(0, sampleCount)
        val budget = ceil(remainingMs.toDouble() * syncRate * sampleRate / 1000.0)
            .toInt().coerceAtLeast(0)
        val count = min(sampleCount - offset, budget).coerceAtLeast(0)
        val truncated = offset + count < sampleCount
        val outputRate = (syncRate * safePlayerSpeed).coerceIn(0.5f, MAX_OUTPUT_RATE)
        return DubbingPlaybackPlan(offset, count, syncRate, outputRate, truncated)
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
    fun slice(samples: FloatArray, plan: DubbingPlaybackPlan, sampleRate: Int): FloatArray {
        if (plan.sampleCount <= 0 || plan.startSample !in 0 until samples.size) return FloatArray(0)
        val end = (plan.startSample.toLong() + plan.sampleCount)
            .coerceAtMost(samples.size.toLong()).toInt()
        if (end <= plan.startSample) return FloatArray(0)
        // The common path plays the entire cached cue unmodified. Reuse its array instead
        // of duplicating up to 32 MB on the heap before AudioTrack copies it to native memory.
        if (plan.startSample == 0 && end == samples.size && !plan.truncated) return samples
        val result = samples.copyOfRange(plan.startSample, end)
        // Starting from a mid-cue sample (late TTS or a seek) can begin at non-zero amplitude.
        // A very short fade-in prevents a click without delaying the cue's timeline start.
        if (plan.startSample > 0 && sampleRate > 0) {
            val fadeSamples = min(sampleRate * FADE_IN_MS / 1_000, result.size / 2)
            if (fadeSamples > 1) {
                for (i in 0 until fadeSamples) {
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
                    val progress = (i + 1).toDouble() / fadeSamples
                    val gain = 0.5 * (1.0 + cos(PI * progress))
                    result[fadeStart + i] = (result[fadeStart + i] * gain).toFloat()
                }
            }
        }
        return result
    }
}
