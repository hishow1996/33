package io.translatetube.local.tts

/** Shared finite speed bounds for device TTS and the local neural voice engine. */
internal object TtsSpeechRatePolicy {
    fun normalize(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
}
