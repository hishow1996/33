package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/**
 * Splits the compact, inline player caption into readable two-line fragments without dropping
 * characters. Fragment timing follows the subtitle cue window and remains frozen while paused.
 */
internal object SubtitlePreviewSegmentPolicy {
    private const val DEFAULT_MAX_VISIBLE_UNITS = 38f

    internal data class Timing(
        val cueKeyMs: Long,
        val durationMs: Long,
    )

    fun split(
        text: String,
        maxVisibleUnits: Float = DEFAULT_MAX_VISIBLE_UNITS,
    ): List<String> {
        val source = text.trim()
        if (source.isEmpty()) return emptyList()

        val limit = maxVisibleUnits.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_MAX_VISIBLE_UNITS
        val result = ArrayList<String>()
        var start = 0

        while (start < source.length) {
            var cursor = start
            var visibleUnits = 0f
            var sentenceBreak = -1
            var punctuationBreak = -1
            var whitespaceBreak = -1

            while (cursor < source.length) {
                val codePoint = source.codePointAt(cursor)
                val width = widthUnits(codePoint)
                if (cursor > start && visibleUnits + width > limit) break

                visibleUnits += width
                cursor += Character.charCount(codePoint)

                when {
                    isSentencePunctuation(codePoint) -> sentenceBreak = cursor
                    isSoftPunctuation(codePoint) -> punctuationBreak = cursor
                }
                if (Character.isWhitespace(codePoint)) whitespaceBreak = cursor
            }

            if (cursor >= source.length) {
                result += source.substring(start)
                break
            }

            val preferredBoundaryFloor = start + ((cursor - start) * 0.52f).toInt()
            val cut = when {
                sentenceBreak >= preferredBoundaryFloor -> sentenceBreak
                punctuationBreak >= preferredBoundaryFloor -> punctuationBreak
                whitespaceBreak >= preferredBoundaryFloor -> whitespaceBreak
                else -> cursor
            }.coerceIn(
                start + Character.charCount(source.codePointAt(start)),
                source.length,
            )

            result += source.substring(start, cut)
            start = cut
        }

        return result
    }

    fun resolveTiming(
        lines: List<SubLine>,
        currentLineIndex: Int,
        captionOverride: String?,
    ): Timing {
        val current = lines.getOrNull(currentLineIndex)
            ?: return Timing(cueKeyMs = 0L, durationMs = 2_400L)

        val mergedCaption = captionOverride?.trim().orEmpty()
        if (mergedCaption.isNotEmpty()) {
            val candidateStarts = (currentLineIndex - 1).coerceAtLeast(0)..currentLineIndex
            for (firstIndex in candidateStarts) {
                val first = lines.getOrNull(firstIndex) ?: continue
                val second = lines.getOrNull(firstIndex + 1) ?: continue
                if (currentLineIndex !in firstIndex..(firstIndex + 1)) continue

                val firstText = first.zh?.trim().orEmpty()
                val secondText = second.zh?.trim().orEmpty()
                if (firstText.isBlank() || secondText.isBlank()) continue

                val expected = joinMergedLines(firstText, secondText)
                if (expected == mergedCaption) {
                    return Timing(
                        cueKeyMs = first.startMs,
                        durationMs = (second.endMs - first.startMs).coerceAtLeast(1L),
                    )
                }
            }
        }

        return Timing(
            cueKeyMs = current.startMs,
            durationMs = (current.endMs - current.startMs).coerceAtLeast(1L),
        )
    }

    private fun widthUnits(codePoint: Int): Float = when {
        Character.isWhitespace(codePoint) -> 0.33f
        codePoint <= 0x7F && Character.isLetterOrDigit(codePoint) -> 0.55f
        codePoint <= 0x7F -> 0.5f
        else -> 1f
    }

    private fun isSentencePunctuation(codePoint: Int): Boolean = when (codePoint) {
        '.'.code, '!'.code, '?'.code, ';'.code,
        '。'.code, '！'.code, '？'.code, '；'.code -> true
        else -> false
    }

    private fun isSoftPunctuation(codePoint: Int): Boolean = when (codePoint) {
        ','.code, ':'.code, '，'.code, '、'.code, '：'.code -> true
        else -> false
    }

    private fun joinMergedLines(first: String, second: String): String {
        val tail = first.lastOrNull()
        return if (
            tail == '。' || tail == '？' || tail == '！' ||
            tail == '；' || tail == '，' || tail == '、'
        ) {
            first + second
        } else {
            "$first，$second"
        }
    }
}
