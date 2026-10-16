package io.translatetube.local.asr

import kotlin.math.roundToLong

/**
 * Converts sherpa-onnx Whisper token-level DTW timings into readable subtitle cues.
 *
 * Token timestamps are relative to the cropped VAD audio segment. This policy translates them
 * back to the media timeline, groups short tokens into subtitle-sized phrases, and returns empty
 * when timestamp data cannot be trusted so the caller can use its existing safe fallback.
 */
internal object WhisperSubtitleTimelinePolicy {
    private const val MAX_CUE_DURATION_MS = 3_600L
    private const val MAX_CJK_CODE_POINTS = 24
    private const val MAX_OTHER_CODE_POINTS = 64
    private const val MIN_LAST_TOKEN_DURATION_MS = 80L

    private data class TimedToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    fun fromTokenTimestamps(
        tokens: List<String>,
        timestampsSeconds: List<Float>,
        durationsSeconds: List<Float>,
        segmentStartMs: Long,
        segmentEndMs: Long,
        expectedText: String,
    ): List<Triple<Long, Long, String>> {
        if (tokens.isEmpty() || tokens.size != timestampsSeconds.size) return emptyList()
        if (segmentStartMs < 0L || segmentEndMs <= segmentStartMs) return emptyList()
        if (expectedText.isBlank()) return emptyList()

        fun isControlToken(token: String): Boolean =
            token.startsWith("<|") && token.endsWith("|>")

        val joinedText = tokens.filterNot(::isControlToken).joinToString("").trim()
        if (joinedText != expectedText.trim()) return emptyList()

        val validStarts = timestampsSeconds.filter { it.isFinite() && it >= 0f }
        if (validStarts.size != timestampsSeconds.size) return emptyList()
        if (validStarts.zipWithNext().any { (left, right) -> right + 0.02f < left }) return emptyList()
        val hasUsefulDurations = durationsSeconds.indices.any { index ->
            durationsSeconds[index].isFinite() && durationsSeconds[index] > 0.01f
        }
        val hasDistinctStarts = validStarts.zipWithNext().any { (left, right) -> right - left > 0.01f }
        if (!hasUsefulDurations && !hasDistinctStarts) return emptyList()
        if (validStarts.size > 1 && validStarts.maxOrNull() == validStarts.minOrNull()) return emptyList()

        val segmentDurationMs = segmentEndMs - segmentStartMs
        // Find each token's nearest later timestamp beyond the 5 ms tolerance in linear time.
        // Re-scanning every suffix is quadratic for long recognition segments on older phones.
        val nextTimestampIndex = IntArray(timestampsSeconds.size) { -1 }
        val timestampStack = IntArray(timestampsSeconds.size)
        var stackSize = 0
        for (index in timestampsSeconds.indices.reversed()) {
            val threshold = timestampsSeconds[index].toDouble() + 0.005
            while (
                stackSize > 0 &&
                timestampsSeconds[timestampStack[stackSize - 1]].toDouble() <= threshold
            ) {
                stackSize--
            }
            if (stackSize > 0) nextTimestampIndex[index] = timestampStack[stackSize - 1]
            timestampStack[stackSize++] = index
        }

        val timedTokens = ArrayList<TimedToken>(tokens.size)
        for (index in tokens.indices) {
            val token = tokens[index]
            if (isControlToken(token)) continue

            val startSeconds = timestampsSeconds[index].toDouble()
            val startRelativeMs = (startSeconds * 1_000.0).roundToLong()
            // If visible words fall well outside this VAD crop, do not fabricate a partial alignment.
            if (startRelativeMs < 0L || startRelativeMs >= segmentDurationMs) {
                if (token.isNotBlank()) return emptyList()
                continue
            }

            val declaredDuration = durationsSeconds.getOrNull(index)?.toDouble()
                ?.takeIf { it.isFinite() && it > 0.01 }
            val nextStartSeconds = nextTimestampIndex[index]
                .takeIf { it >= 0 }
                ?.let { timestampsSeconds[it].toDouble() }
            val durationMs = when {
                declaredDuration != null -> (declaredDuration * 1_000.0).roundToLong().coerceAtLeast(1L)
                nextStartSeconds != null ->
                    ((nextStartSeconds - startSeconds) * 1_000.0).roundToLong().coerceAtLeast(1L)
                else -> MIN_LAST_TOKEN_DURATION_MS
            }

            val startMs = segmentStartMs + startRelativeMs
            // Clamp the duration before addition. Finite-but-hostile model durations can round
            // to Long.MAX_VALUE; adding the segment's non-zero base time first would overflow,
            // wrap negative and silently discard a token that should be clamped to the crop end.
            val safeDurationMs = durationMs.coerceAtMost(segmentEndMs - startMs)
            val endMs = startMs + safeDurationMs
            if (endMs <= startMs) continue
            timedTokens += TimedToken(token, startMs, endMs)
        }
        if (timedTokens.isEmpty()) return emptyList()

        val cues = mutableListOf<Triple<Long, Long, String>>()
        val text = StringBuilder()
        var cueStartMs = -1L
        var cueEndMs = -1L

        fun flushCue() {
            val caption = text.toString().trim()
            if (caption.isNotBlank() && cueStartMs >= 0L && cueEndMs > cueStartMs) {
                cues += Triple(cueStartMs, cueEndMs, caption)
            }
            text.setLength(0)
            cueStartMs = -1L
            cueEndMs = -1L
        }

        fun codePointCount(value: String): Int = value.codePointCount(0, value.length)
        fun isCjk(value: String): Boolean = value.any { char ->
            char in '\u2E80'..'\u9FFF' ||
                char in '\uF900'..'\uFAFF' ||
                char in '\u3040'..'\u30FF' ||
                char in '\uAC00'..'\uD7AF'
        }
        fun endsSentence(token: String): Boolean {
            val last = token.trimEnd().lastOrNull() ?: return false
            return last in charArrayOf('。', '！', '？', '!', '?', '.', '；', ';')
        }

        for (token in timedTokens) {
            val existing = text.toString()
            val incomingText = if (existing.isBlank()) token.text.trimStart() else token.text
            val projected = existing + incomingText
            val maxChars = if (isCjk(projected)) MAX_CJK_CODE_POINTS else MAX_OTHER_CODE_POINTS
            val tooLong = existing.isNotBlank() && codePointCount(projected) > maxChars
            val tooLongInTime = existing.isNotBlank() &&
                token.startMs - cueStartMs >= MAX_CUE_DURATION_MS

            if (tooLong || tooLongInTime) flushCue()

            if (text.isEmpty()) {
                val cleanToken = token.text.trimStart()
                if (cleanToken.isNotEmpty()) {
                    cueStartMs = token.startMs
                    cueEndMs = token.endMs
                    text.append(cleanToken)
                }
            } else {
                text.append(token.text)
                cueEndMs = maxOf(cueEndMs, token.endMs)
            }

            if (text.isNotBlank() && endsSentence(token.text)) flushCue()
        }
        flushCue()

        // Token-level boundaries normally meet exactly; guard against small DTW overlap drift.
        val adjustedCues = cues.mapIndexed { index, cue ->
            val nextStart = cues.getOrNull(index + 1)?.first
            val end = if (nextStart != null && cue.second > nextStart) nextStart else cue.second
            Triple(cue.first, end, cue.third)
        }
        // Do not silently lose text if unusual timestamp ties make a cue unrepresentable.
        if (adjustedCues.any { it.second <= it.first }) return emptyList()
        return adjustedCues
    }
}
