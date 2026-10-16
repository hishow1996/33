package io.translatetube.local.asr

/**
 * Converts a media timestamp into a truthful, monotonic progress percentage.
 *
 * MediaCodec/MediaExtractor may emit repeated or slightly out-of-order timestamps, and some
 * containers do not expose duration metadata at all. Unknown durations intentionally yield no
 * percentage; callers should keep their indeterminate/detail state rather than inventing progress.
 */
internal object MediaTimelineProgressPolicy {
    /**
     * Returns a new percentage only when measured media time advances the display.
     * Values are capped at 99%; the caller emits 100% only after output is finalized successfully.
     */
    /** Progress for a long sample-scanning pass; completion belongs to the caller after finalize. */
    fun nextSamplePercent(
        processedSamples: Long,
        totalSamples: Long,
        previousPercent: Int = -1,
    ): Int? {
        if (processedSamples < 0L || totalSamples <= 0L || previousPercent >= 100) return null
        val ratio = processedSamples.toDouble() / totalSamples.toDouble()
        if (!ratio.isFinite() || ratio < 0.0) return null
        val candidate = (ratio.coerceAtMost(1.0) * 100.0).toInt().coerceIn(0, 99)
        return candidate.takeIf { it > previousPercent.coerceIn(-1, 100) }
    }

    fun nextPercent(
        positionUs: Long,
        durationUs: Long,
        previousPercent: Int = -1,
    ): Int? {
        if (positionUs < 0L || durationUs <= 0L) return null
        if (previousPercent >= 100) return null

        val ratio = positionUs.toDouble() / durationUs.toDouble()
        if (!ratio.isFinite() || ratio < 0.0) return null
        val candidate = (ratio.coerceAtMost(1.0) * 100.0).toInt().coerceIn(0, 99)
        return candidate.takeIf { it > previousPercent.coerceIn(-1, 100) }
    }
}
