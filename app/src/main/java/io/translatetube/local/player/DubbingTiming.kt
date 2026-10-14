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
        if (sampleCount <= 0 || sampleRate <= 0 || lineEndMs <= lineStartMs) {
            return DubbingPlaybackPlan(0, 0, 1.0f, safePlayerSpeed, false)
        }

        val lineDurationMs = lineEndMs - lineStartMs
        val elapsedMs = (positionMs - lineStartMs).coerceIn(0L, lineDurationMs)
        val remainingMs = lineDurationMs - elapsedMs
        if (remainingMs < MIN_REMAINING_WINDOW_MS) {
            // A tiny late fragment is usually unintelligible; skip it instead of playing a
            // clipped syllable immediately before the next subtitle cue.
            return DubbingPlaybackPlan(sampleCount, 0, 1.0f, safePlayerSpeed, false)
        }

        val audioDurationMs = sampleCount.toDouble() * 1000.0 / sampleRate
        val safeMaxRate = maxSyncRate.takeIf { it.isFinite() && it >= 1.0f } ?: MAX_SYNC_RATE
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
        val elapsedMs = (currentPositionMs - preparedAtMs).coerceAtLeast(0L)
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
        val result = samples.copyOfRange(plan.startSample, end)
        if (plan.truncated && sampleRate > 0) {
            val fadeSamples = min(sampleRate * FADE_OUT_MS / 1000, result.size / 2)
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
