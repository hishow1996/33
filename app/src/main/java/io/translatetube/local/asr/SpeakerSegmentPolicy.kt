package io.translatetube.local.asr

/** Validates timestamps returned by native speaker diarization before they reach subtitle matching. */
internal object SpeakerSegmentPolicy {
    const val UNKNOWN_CONFIDENCE = -2f

    fun normalize(
        startSec: Float,
        endSec: Float,
        speaker: Int,
        confidence: Float,
        durationSec: Float,
    ): SpeakerDiarizer.Segment? {
        if (!durationSec.isFinite() || durationSec <= 0f ||
            !startSec.isFinite() || !endSec.isFinite() ||
            startSec < 0f || endSec <= startSec || speaker < 0 ||
            startSec >= durationSec
        ) return null

        val clippedEnd = minOf(endSec, durationSec)
        if (!clippedEnd.isFinite() || clippedEnd <= startSec) return null
        val normalizedConfidence = when {
            !confidence.isFinite() || confidence < 0f -> UNKNOWN_CONFIDENCE
            else -> confidence.coerceIn(0f, 1f)
        }
        return SpeakerDiarizer.Segment(startSec, clippedEnd, speaker, normalizedConfidence)
    }
}
