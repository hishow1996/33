package io.translatetube.local.tts

/** Separate normal synthesis bounds from the larger rate needed by timeline-driven live TTS. */
internal object TtsSpeechRatePolicy {
    const val MAX_SYNTHESIS_RATE = 3.0f
    const val MAX_TIMELINE_RATE = 6.0f

    fun normalize(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, MAX_SYNTHESIS_RATE) ?: 1.0f

    fun normalizeTimeline(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, MAX_TIMELINE_RATE) ?: 1.0f
}
