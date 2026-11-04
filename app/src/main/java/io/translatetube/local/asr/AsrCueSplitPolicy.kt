package io.translatetube.local.asr

import kotlin.math.roundToLong

/**
 * 把过长的识别行拆成适合显示和配音的短句行。
 *
 * SenseVoice 没有词级时间戳，一个语音段（最长 25 秒）只会得到一行字幕；Whisper 在词级时间戳
 * 不可用时也会退回"整段一行"。一行几十秒的字幕既没法读，配音也无法与画面同步。
 * 这里按标点把文字拆成句子、再打包成不超过 [MAX_CJK_CHARS]/[MAX_OTHER_CHARS] 个字符的行，
 * 时间按字数比例分配在原来的起止时间之内（各行首尾相接，整体起止时间不变）。
 *
 * 没有超长的行原样返回；拆不稳妥（时间太短等）也原样返回。纯逻辑，JVM 可测。
 */
internal object AsrCueSplitPolicy {
    const val MAX_CUE_MS = 6_000L
    const val MAX_CJK_CHARS = 28
    const val MAX_OTHER_CHARS = 84
    /** 每一行至少分到这么长的时间，否则不拆。 */
    private const val MIN_PIECE_MS = 200L
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs")

    private data class Slice(val from: Int, val to: Int)

    fun split(startMs: Long, endMs: Long, text: String): List<Triple<Long, Long, String>> {
        val original = listOf(Triple(startMs, endMs, text))
        val trimmed = text.trim()
        if (trimmed.isEmpty() || startMs < 0L || endMs <= startMs) return original

        val cjk = AsrHallucinationPolicy.isCjkDominant(trimmed)
        val maxChars = if (cjk) MAX_CJK_CHARS else MAX_OTHER_CHARS
        val durationMs = endMs - startMs
        if (durationMs <= MAX_CUE_MS && trimmed.length <= maxChars) return original

        val sentences = ArrayList<Slice>()
        for (sentence in sentenceSlices(trimmed)) {
            if (sentence.to - sentence.from > maxChars) {
                sentences += breakLongSentence(trimmed, sentence, maxChars, cjk)
            } else {
                sentences += sentence
            }
        }
        if (sentences.isEmpty()) return original

        // 把相邻的句子打包到不超过上限的一行里；行内保留原文的空白。
        val pieces = ArrayList<Slice>()
        var current: Slice? = null
        for (sentence in sentences) {
            val previous = current
            if (previous == null) {
                current = sentence
            } else if (sentence.to - previous.from <= maxChars) {
                current = Slice(previous.from, sentence.to)
            } else {
                pieces += previous
                current = sentence
            }
        }
        current?.let { pieces += it }
        if (pieces.size < 2) return original
        if (durationMs < pieces.size * MIN_PIECE_MS) return original

        val weights = pieces.map { piece -> visibleCount(trimmed, piece).coerceAtLeast(1) }
        val totalWeight = weights.sum().toDouble()
        val result = ArrayList<Triple<Long, Long, String>>(pieces.size)
        var cumulative = 0
        var previousEnd = startMs
        for ((index, piece) in pieces.withIndex()) {
            cumulative += weights[index]
            val isLast = index == pieces.lastIndex
            var boundary = if (isLast) {
                endMs
            } else {
                startMs + (durationMs * (cumulative / totalWeight)).roundToLong()
            }
            if (!isLast && boundary <= previousEnd) boundary = previousEnd + 1L
            if (boundary > endMs || boundary <= previousEnd) return original
            val pieceText = trimmed.substring(piece.from, piece.to).trim()
            if (pieceText.isEmpty()) return original
            result += Triple(previousEnd, boundary, pieceText)
            previousEnd = boundary
        }
        return result
    }

    /** 先清理（署名行、循环重复），再把过长的行拆开。一个语音段的行一次传入。 */
    fun finishSegmentRows(
        rows: List<Triple<Long, Long, String>>,
    ): List<Triple<Long, Long, String>> {
        val cleaned = AsrHallucinationPolicy.cleanRows(rows)
        val result = ArrayList<Triple<Long, Long, String>>(cleaned.size)
        for ((start, end, text) in cleaned) result += split(start, end, text)
        return result
    }

    private fun sentenceSlices(text: String): List<Slice> {
        val slices = ArrayList<Slice>()
        var sentenceStart = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (!isTerminator(c)) {
                index++
                continue
            }
            var end = index + 1
            while (end < text.length && isTerminator(text[end])) end++
            while (end < text.length && isClosing(text[end])) end++
            val atEnd = end >= text.length
            val boundary = when {
                atEnd -> true
                isCjkTerminator(c) -> true
                text[end].isWhitespace() -> !(c == '.' && endsWithAbbreviation(text, index))
                else -> false
            }
            if (!boundary) {
                index = end
                continue
            }
            slices += Slice(sentenceStart, end)
            var next = end
            while (next < text.length && text[next].isWhitespace()) next++
            sentenceStart = next
            index = next
        }
        if (sentenceStart < text.length) slices += Slice(sentenceStart, text.length)
        return slices
    }

    private fun breakLongSentence(text: String, sentence: Slice, maxChars: Int, cjk: Boolean): List<Slice> {
        val parts = ArrayList<Slice>()
        var position = sentence.from
        while (sentence.to - position > maxChars) {
            val limit = position + maxChars
            val lowest = position + maxChars / 2
            var cut = -1
            var k = limit - 1
            while (k >= lowest) {
                if (isSoftBreak(text[k])) {
                    cut = k + 1
                    break
                }
                k--
            }
            if (cut < 0 && !cjk) {
                k = limit
                while (k >= lowest) {
                    if (text[k].isWhitespace()) {
                        cut = k
                        break
                    }
                    k--
                }
            }
            if (cut < 0) cut = limit
            if (cut < text.length && Character.isLowSurrogate(text[cut])) cut--
            if (cut <= position) cut = limit
            parts += Slice(position, cut)
            position = cut
            while (position < sentence.to && text[position].isWhitespace()) position++
        }
        if (position < sentence.to) parts += Slice(position, sentence.to)
        return parts
    }

    private fun visibleCount(text: String, slice: Slice): Int {
        var count = 0
        var offset = slice.from
        while (offset < slice.to) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (!Character.isWhitespace(codePoint)) count++
        }
        return count
    }

    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == ';' || c == '。' || c == '！' || c == '？' || c == '；' || c == '…'

    private fun isCjkTerminator(c: Char): Boolean =
        c == '。' || c == '！' || c == '？' || c == '；' || c == '…'

    private fun isClosing(c: Char): Boolean =
        c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’' || c == '」' || c == '』' || c == '）'

    private fun isSoftBreak(c: Char): Boolean =
        c == ',' || c == '，' || c == '、' || c == ';' || c == '；' || c == ':' || c == '：'

    /** 句点前面是 "Mr"、"Dr"、"U.S" 这类缩写时不算句子结束。 */
    private fun endsWithAbbreviation(text: String, dotIndex: Int): Boolean {
        var start = dotIndex
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        val word = text.substring(start, dotIndex).lowercase(java.util.Locale.ROOT)
        if (word.isEmpty()) return false
        if (word in abbreviations) return true
        return word.contains('.')
    }
}
