package io.translatetube.local.subs

/**
 * Pure timing parser for the legacy YouTube timed-text XML format.
 * Keeping it Android-free makes malformed and missing duration attributes testable on the JVM.
 */
internal object TimedTextCueTimingPolicy {
    private const val DEFAULT_DURATION_SECONDS = 3.0
    private const val MAX_SAFE_SECONDS = Long.MAX_VALUE.toDouble() / 1_000.0

    data class Timing(val startMs: Long, val endMs: Long)

    fun parse(startValue: String?, durationValue: String?): Timing? {
        val startSeconds = startValue?.trim()?.toDoubleOrNull() ?: return null
        if (!startSeconds.isFinite() || startSeconds < 0.0 || startSeconds > MAX_SAFE_SECONDS) {
            return null
        }

        val durationSeconds = if (durationValue.isNullOrBlank()) {
            DEFAULT_DURATION_SECONDS
        } else {
            durationValue.trim().toDoubleOrNull() ?: return null
        }
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0 ||
            durationSeconds > MAX_SAFE_SECONDS
        ) return null

        val startMs = (startSeconds * 1_000.0).toLong()
        val durationMs = (durationSeconds * 1_000.0).toLong()
        if (durationMs <= 0L || Long.MAX_VALUE - startMs < durationMs) return null
        return Timing(startMs, startMs + durationMs)
    }
}
