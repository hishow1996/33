package io.translatetube.local.tts

/** Estimates a safe upper wait for long subtitle cues without penalizing normal short captions. */
internal object SystemTtsTimeoutPolicy {
    const val MIN_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 6L * 60L * 1_000L
    private const val ESTIMATED_CODE_POINTS_PER_SECOND = 8.0
    private const val COMPLETION_MARGIN_MS = 15_000L

    fun timeoutMs(text: String, speed: Float): Long {
        val codePoints = text.codePointCount(0, text.length).coerceAtLeast(1)
        val normalizedSpeed = TtsSpeechRatePolicy.normalize(speed).toDouble()
        val spokenMs = (codePoints / (ESTIMATED_CODE_POINTS_PER_SECOND * normalizedSpeed) * 1_000.0)
            .toLong()
            .coerceAtLeast(0L)
        val estimated = if (spokenMs > Long.MAX_VALUE - COMPLETION_MARGIN_MS) {
            Long.MAX_VALUE
        } else {
            spokenMs + COMPLETION_MARGIN_MS
        }
        return estimated.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
