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
    /** Split a displayed cue when token starts indicate a meaningful pause in speech. */
    private const val MAX_INTER_TOKEN_GAP_MS = 900L
    /** Bound token/timestamp arrays before joining text or allocating timeline stacks. */
    private const val MAX_TOKEN_COUNT = 200_000
    private const val MAX_CJK_CODE_POINTS = 24
    private const val MAX_OTHER_CODE_POINTS = 64
    private const val MIN_LAST_TOKEN_DURATION_MS = 80L
    private val whitespaceRegex = Regex("\\s+")

    private data class TimedToken(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        /** End inferred from the model before capping it at the next token boundary. */
        val inferredEndMs: Long,
        val hasDeclaredDuration: Boolean,
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
        if (tokens.size > MAX_TOKEN_COUNT) return emptyList()
        if (segmentStartMs < 0L || segmentEndMs <= segmentStartMs) return emptyList()
        if (expectedText.isBlank()) return emptyList()

        fun isControlToken(token: String): Boolean =
            token.startsWith("<|") && token.endsWith("|>")

        // Token boundaries may encode repeated whitespace differently from the recognizer's
        // plain-text output. Normalize whitespace only; any actual character mismatch still
        // invalidates token timing so we never attach real words to the wrong interval.
        fun comparableText(value: String): String = value.trim().replace(whitespaceRegex, " ")
        val joinedText = tokens.filterNot(::isControlToken).joinToString("")
        if (comparableText(joinedText) != comparableText(expectedText)) return emptyList()

        // Validate in one pass instead of allocating filtered timestamp/zip intermediates for
        // up to 200,000 tokens on low-memory Android devices.
        var previousStart = 0f
        var minStart = Float.POSITIVE_INFINITY
        var maxStart = Float.NEGATIVE_INFINITY
        var hasDistinctStarts = false
        for (index in timestampsSeconds.indices) {
            val start = timestampsSeconds[index]
            if (!start.isFinite() || start < 0f) return emptyList()
            if (index > 0) {
                if (start + 0.02f < previousStart) return emptyList()
                if (start - previousStart > 0.01f) hasDistinctStarts = true
            }
            previousStart = start
            minStart = minOf(minStart, start)
            maxStart = maxOf(maxStart, start)
        }
        val hasUsefulDurations = durationsSeconds.indices.any { index ->
            durationsSeconds[index].isFinite() && durationsSeconds[index] > 0.01f
        }
        if (!hasUsefulDurations && !hasDistinctStarts) return emptyList()
        if (timestampsSeconds.size > 1 && maxStart == minStart) return emptyList()

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
            // A token's declared duration can drift through a later token or a silence gap.
            // Do not let its visible interval extend beyond the next distinct token start.
            val untilNextTokenMs = nextStartSeconds
                ?.let { ((it - startSeconds) * 1_000.0).roundToLong().coerceAtLeast(1L) }
            val boundedDurationMs = if (untilNextTokenMs == null) {
                durationMs
            } else {
                minOf(durationMs, untilNextTokenMs)
            }

            val startMs = segmentStartMs + startRelativeMs
            // Clamp the duration before addition. Finite-but-hostile model durations can round
            // to Long.MAX_VALUE; adding the segment's non-zero base time first would overflow,
            // wrap negative and silently discard a token that should be clamped to the crop end.
            val maxDurationMs = segmentEndMs - startMs
            val inferredDurationMs = durationMs.coerceAtMost(maxDurationMs)
            val safeDurationMs = boundedDurationMs.coerceAtMost(maxDurationMs)
            val endMs = startMs + safeDurationMs
            val inferredEndMs = startMs + inferredDurationMs
            if (endMs <= startMs) continue
            timedTokens += TimedToken(token, startMs, endMs, inferredEndMs, declaredDuration != null)
        }
        if (timedTokens.isEmpty()) return emptyList()

        val cues = mutableListOf<Triple<Long, Long, String>>()
        val text = StringBuilder()
        var cueStartMs = -1L
        var cueEndMs = -1L
        var cueCodePointCount = 0
        var cueHasCjk = false
        var previousTokenInferredEndMs: Long? = null
        var previousTokenStartMs: Long? = null
        var previousTokenHasDeclaredDuration: Boolean? = null

        fun flushCue() {
            // Token text can carry tokenizer-specific repeated boundary spaces. Keep the
            // actual subtitle display consistent with the whitespace-tolerant alignment check.
            val caption = text.toString().trim().replace(whitespaceRegex, " ")
            if (caption.isNotBlank() && cueStartMs >= 0L && cueEndMs > cueStartMs) {
                cues += Triple(cueStartMs, cueEndMs, caption)
            }
            text.setLength(0)
            cueStartMs = -1L
            cueEndMs = -1L
            cueCodePointCount = 0
            cueHasCjk = false
        }

        fun codePointCount(value: String): Int = value.codePointCount(0, value.length)
        fun isCjk(value: String): Boolean {
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                if (
                    codePoint in 0x2E80..0x9FFF ||
                    codePoint in 0xF900..0xFAFF ||
                    codePoint in 0x3040..0x30FF ||
                    codePoint in 0xAC00..0xD7AF ||
                    codePoint in 0x20000..0x2FA1F ||
                    codePoint in 0x30000..0x323AF
                ) return true
                offset += Character.charCount(codePoint)
            }
            return false
        }
        fun endsSentence(token: String): Boolean {
            val last = token.trimEnd().lastOrNull() ?: return false
            // Avoid allocating a punctuation array for every token in long transcripts.
            return last == '。' || last == '！' || last == '？' ||
                last == '!' || last == '?' || last == '.' || last == '；' || last == ';'
        }

        for (token in timedTokens) {
            // Track code-point count and CJK presence incrementally. Rebuilding the whole current
            // caption for every token made long Whisper segments quadratic in text allocations.
            val incomingText = if (text.isEmpty()) token.text.trimStart() else token.text
            val projectedHasCjk = cueHasCjk || isCjk(incomingText)
            val maxChars = if (projectedHasCjk) MAX_CJK_CODE_POINTS else MAX_OTHER_CODE_POINTS
            val incomingCodePoints = codePointCount(incomingText)
            val tooLong = text.isNotBlank() &&
                cueCodePointCount + incomingCodePoints > maxChars
            val tooLongInTime = text.isNotBlank() &&
                token.startMs - cueStartMs >= MAX_CUE_DURATION_MS
            val longPauseByDeclaredEnd = previousTokenInferredEndMs
                ?.let { token.startMs - it >= MAX_INTER_TOKEN_GAP_MS } == true
            val longPauseWithoutDuration = previousTokenHasDeclaredDuration == false &&
                previousTokenStartMs?.let { token.startMs - it >= MAX_INTER_TOKEN_GAP_MS } == true
            val longPauseBefore = text.isNotBlank() &&
                (longPauseByDeclaredEnd || longPauseWithoutDuration)

            if (tooLong || tooLongInTime || longPauseBefore) flushCue()

            if (text.isEmpty()) {
                val cleanToken = token.text.trimStart()
                if (cleanToken.isNotEmpty()) {
                    cueStartMs = token.startMs
                    cueEndMs = token.endMs
                    text.append(cleanToken)
                    cueCodePointCount = codePointCount(cleanToken)
                    cueHasCjk = isCjk(cleanToken)
                }
            } else {
                text.append(token.text)
                cueCodePointCount += codePointCount(token.text)
                cueHasCjk = cueHasCjk || isCjk(token.text)
                cueEndMs = maxOf(cueEndMs, token.endMs)
            }

            if (text.isNotBlank() && endsSentence(token.text)) flushCue()
            previousTokenInferredEndMs = token.inferredEndMs
            previousTokenStartMs = token.startMs
            previousTokenHasDeclaredDuration = token.hasDeclaredDuration
        }
        flushCue()

        // 段首行锚定 VAD 起点：token 对齐的行起点系统性晚于语音真实起点（VAD 能量检测
        // 先于 Whisper 首词时间戳，普遍偏晚 200-600ms——「视频说完第一句字幕才出现」）。
        // 每段第一行的 start 直接用 VAD 段起点，从源头把字幕时间轴与视频对齐；
        // 原声播放与配音共用这份时间轴，两种模式同步受益。
        if (cues.isNotEmpty() && cues[0].first > segmentStartMs) {
            val first = cues[0]
            cues[0] = Triple(segmentStartMs, first.second, first.third)
        }

        // Correct overlaps and validate bounds in the same pass.
        val adjustedCues = ArrayList<Triple<Long, Long, String>>(cues.size)
        for (index in cues.indices) {
            val cue = cues[index]
            val nextStart = cues.getOrNull(index + 1)?.first
            val end = if (nextStart != null && cue.second > nextStart) nextStart else cue.second
            if (end <= cue.first) return emptyList()
            adjustedCues += Triple(cue.first, end, cue.third)
        }
        return adjustedCues
    }
}
