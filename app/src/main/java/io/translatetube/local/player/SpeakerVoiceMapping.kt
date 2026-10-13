package io.translatetube.local.player

import kotlin.math.max
import kotlin.math.min

/** Pure mapping between diarizer speaker labels and Kokoro voice IDs. */
internal object SpeakerVoiceMapping {
    data class Segment(
        val startSec: Float,
        val endSec: Float,
        val speaker: Int,
        /** 0..1 when supplied by the diarizer; 1 means unknown confidence, not guaranteed certainty. */
        val confidence: Float = 1f,
    )

    private const val MIN_CONFIDENCE = 0.25f

    fun voiceForSpeaker(speaker: Int, preferredDefaultVoice: Int): Int {
        val preferred = preferredDefaultVoice.coerceIn(0, 53)
        return when {
            speaker < 0 -> -1
            speaker == 0 -> -1
            speaker > 53 -> -1
            else -> {
                val candidate = speaker - 1
                if (candidate >= preferred) candidate + 1 else candidate
            }
        }
    }

    fun voiceForCue(
        startMs: Long,
        endMs: Long,
        segments: List<Segment>,
        preferredDefaultVoice: Int,
    ): Int {
        if (startMs < 0L || endMs <= startMs || segments.isEmpty()) return -1
        val start = startMs / 1000f
        val end = endMs / 1000f
        val duration = end - start
        val overlapBySpeaker = mutableMapOf<Int, Float>()
        for (segment in segments) {
            val confidence = segment.confidence
            if (!segment.startSec.isFinite() || !segment.endSec.isFinite() ||
                segment.startSec < 0f || segment.endSec <= segment.startSec || segment.speaker < 0 ||
                (confidence.isFinite() && confidence in 0f..1f && confidence < MIN_CONFIDENCE)
            ) continue
            val overlap = max(0f, min(end, segment.endSec) - max(start, segment.startSec))
            if (overlap > 0f) {
                overlapBySpeaker[segment.speaker] = (overlapBySpeaker[segment.speaker] ?: 0f) + overlap
            }
        }
        val best = overlapBySpeaker.entries.maxWithOrNull(
            compareBy<Map.Entry<Int, Float>> { it.value }.thenBy { -it.key }
        ) ?: return -1
        val minimumOverlap = min(0.25f, max(0.01f, duration * 0.2f))
        if (best.value < minimumOverlap) return -1
        return voiceForSpeaker(best.key, preferredDefaultVoice)
    }

    fun isValidVoiceSelection(voiceId: Int): Boolean = voiceId == -1 || voiceId in 0..53
}
