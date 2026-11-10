package io.translatetube.local.subs

import kotlin.math.abs

/**
 * 原始 YouTube 自动字幕里的一个词。
 * [lineStart] 表示它是字幕里新出现的一行的第一个词：拼句时，英文等有空格的语言要在行与行之间补空格。
 */
internal data class CaptionWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val lineStart: Boolean = false,
)

internal data class CaptionSentenceMergeResult(
    /** null 表示不合句，沿用原来的行。 */
    val lines: List<SubLine>?,
    /** 为什么合了，或者为什么没合（写日志用）。 */
    val reason: String,
    val wordCount: Int,
    val sentenceCount: Int,
)

/**
 * 把 YouTube 自动字幕里被切碎的行，按"词的真实时间 + 句末标点"重新拼成整句。
 *
 * 为什么要做：自动字幕为了显示，会把一句话切成好几行（日语尤其严重，动词在句尾，
 * 前半句和后半句常被分在不同行）。按行翻译时，模型会把后面的行"吞"进当前行，造成译文错位；
 * 配音按行合成，也会出现一行塞太多字、或一句话被切成几段的问题。
 *
 * 做法（只用来"重新分行"，不改文字内容）：
 * 1. 从原始 WebVTT 里读出每个词的起止时间（逐词时间标签 `<00:00:01.200>`）；
 *    没有逐词标签的整行文字，按句末标点切开、按字数分配这一行的时间。
 * 2. 遇到 。！？!? 就断句；太长的句子在 、，, 处再切开（找不到就在中间切）。
 * 3. 句子起止时间 = 第一个词的开始 ~ 最后一个词的结束。
 * 4. 拼出来的文字必须和原来的行基本一致，否则不合句，沿用原来的行。
 *
 * 任何一步不满足条件都返回 lines=null（沿用原来的行），所以不会因为合句让字幕处理失败。
 */
internal object CaptionSentenceMergePolicy {
    /** 一句最多多少"单位"（汉字/假名算 1，拉丁字母和数字算 0.4）；超过就再切。 */
    const val MAX_SENTENCE_UNITS = 70.0
    private const val LATIN_UNIT = 0.4

    /** 平均每个句末标点对应的单位数超过这个值，说明字幕基本没标点（如英文自动字幕），不按句合并。 */
    private const val MAX_UNITS_PER_TERMINAL = 120.0

    /** 至少要有这么多逐词时间标签才算"有逐词时间"。 */
    const val MIN_TIMED_TAGS = 20
    const val MIN_ROWS = 5

    /** 切长句时，两边至少各占总长的这个比例。 */
    private const val MIN_SPLIT_SHARE = 0.15

    /** 句末最后一个标点的时间可能一直拖到下一个字出现，这里最多只保留这么长的停顿。 */
    private const val TRAILING_PAUSE_MS = 1_500L
    private const val MIN_DURATION_MS = 300L

    private const val VERIFY_WINDOW = 8
    private const val VERIFY_MIN_MATCH = 0.9
    private const val VERIFY_MIN_LENGTH_RATIO = 0.9
    private const val VERIFY_MAX_LENGTH_RATIO = 1.6
    private const val VERIFY_MAX_GAP = 1_000

    private const val TERMINAL_CHARS = "。！？!?"
    private const val BREAK_CHARS = "、，,;；"

    private val cueHeaderRegex =
        Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val wordTimeTagRegex = Regex("""<(\d+:\d{2}:\d{2}[.,]\d{1,3})>""")
    private val anyTagRegex = Regex("""<[^>]*>""")
    private val whitespaceRegex = Regex("""\s+""")

    private class CueLine(val raw: String, val plain: String)

    /**
     * @param rows 原来解析出的字幕行（用来核对文字是否一致）
     * @param rawVtt 下载到的原始 WebVTT（解析前，逐词时间标签还在）
     */
    fun merge(
        rows: List<SubLine>,
        rawVtt: String,
        minTimedTags: Int = MIN_TIMED_TAGS,
        minRows: Int = MIN_ROWS,
    ): CaptionSentenceMergeResult {
        if (rows.size < minRows) return keep("字幕行数太少", 0, 0)
        val timedTags = wordTimeTagRegex.findAll(rawVtt).count()
        if (timedTags < minTimedTags) return keep("没有逐词时间标签（标签数 $timedTags）", 0, 0)

        val words = extractWords(rawVtt)
        if (words.isEmpty()) return keep("没有读出任何词", 0, 0)

        var terminalCount = 0
        var totalUnits = 0.0
        for (word in words) {
            if (endsSentence(word.text)) terminalCount++
            totalUnits += unitsOf(word.text)
        }
        if (terminalCount == 0 || totalUnits / terminalCount > MAX_UNITS_PER_TERMINAL) {
            return keep("句末标点太少，不按句合并（标点数 $terminalCount）", words.size, 0)
        }

        val sentences = ArrayList<List<CaptionWord>>()
        for (sentence in buildSentences(words)) capLength(sentence, sentences)
        val merged = toLines(sentences)
        if (merged.isEmpty()) return keep("合句后没有内容", words.size, 0)
        for (i in 1 until merged.size) {
            if (merged[i].startMs < merged[i - 1].startMs) {
                return keep("合句后时间不是递增的", words.size, merged.size)
            }
        }

        val mine = normalizeForCompare(merged.joinToString("") { it.text })
        val original = normalizeForCompare(rows.joinToString("") { it.text })
        if (!textMatches(mine, original)) {
            return keep("合句后的文字和原字幕对不上", words.size, merged.size)
        }
        return CaptionSentenceMergeResult(merged, "已按句合并", words.size, merged.size)
    }

    private fun keep(reason: String, wordCount: Int, sentenceCount: Int) =
        CaptionSentenceMergeResult(null, reason, wordCount, sentenceCount)

    // ---- 第 1 步：读出带时间的词 -----------------------------------------------------------

    /**
     * 从原始 WebVTT 读出按顺序排列的词。YouTube 自动字幕是"滚动"格式：下一个 cue 会把上一行再显示一遍，
     * 所以每个 cue 只取"新出现"的行（和上一个 cue 末尾重复的开头几行是旧行，跳过）。
     */
    fun extractWords(rawVtt: String): List<CaptionWord> {
        val words = ArrayList<CaptionWord>()
        val cueLines = ArrayList<String>()
        var inCue = false
        var cueStart = 0L
        var cueEnd = 0L
        var previous: List<String> = emptyList()

        fun finishCue() {
            if (!inCue) return
            inCue = false
            val current = ArrayList<CueLine>()
            for (raw in cueLines) {
                val plain = SubtitleTextDecoder.decode(anyTagRegex.replace(raw, ""))
                    .replace(whitespaceRegex, " ")
                    .trim()
                if (plain.isNotEmpty()) current += CueLine(raw, plain)
            }
            cueLines.clear()
            val currentPlain = current.map { it.plain }
            val carried = carriedPrefixLength(previous, currentPlain)
            for (index in carried until current.size) {
                val line = current[index]
                if (wordTimeTagRegex.containsMatchIn(line.raw)) {
                    addTimedLine(line.raw, cueStart, cueEnd, words)
                } else {
                    addUntimedLine(line.plain, cueStart, cueEnd, words)
                }
            }
            previous = currentPlain
        }

        for (rawLine in rawVtt.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val header = cueHeaderRegex.find(line)
            if (header != null) {
                finishCue()
                val start = parseTimeMs(header.groupValues[1])
                val end = parseTimeMs(header.groupValues[2])
                if (start != null && end != null && end >= start) {
                    cueStart = start
                    cueEnd = end
                    inCue = true
                }
                continue
            }
            if (!inCue) continue
            // 只有真正的空行才结束 cue。YouTube 常用只含一个空格的行占位，那是正文的一部分。
            if (line.isEmpty()) {
                finishCue()
                continue
            }
            cueLines += line
        }
        finishCue()

        // 纯音效标注（[音楽]、♪）不是说话内容，去掉，免得它的时间把整句往前拉。
        return words.filter { SubtitleParsers.cleanSpokenText(it.text).isNotEmpty() }
    }

    private fun carriedPrefixLength(previous: List<String>, current: List<String>): Int {
        for (k in minOf(previous.size, current.size) downTo 1) {
            if (current.subList(0, k) == previous.subList(previous.size - k, previous.size)) return k
        }
        return 0
    }

    private fun addTimedLine(line: String, cueStart: Long, cueEnd: Long, out: MutableList<CaptionWord>) {
        val starts = ArrayList<Long>()
        val texts = ArrayList<String>()
        var cursor = 0
        var currentStart = cueStart
        for (match in wordTimeTagRegex.findAll(line)) {
            val text = cleanWordText(line.substring(cursor, match.range.first))
            if (text.isNotBlank()) {
                starts += currentStart
                texts += text
            }
            currentStart = parseTimeMs(match.groupValues[1]) ?: currentStart
            cursor = match.range.last + 1
        }
        val tail = cleanWordText(line.substring(cursor))
        if (tail.isNotBlank()) {
            starts += currentStart
            texts += tail
        }
        for (i in texts.indices) {
            val start = starts[i]
            val end = if (i + 1 < texts.size) starts[i + 1] else cueEnd
            out += CaptionWord(texts[i], start, maxOf(end, start), lineStart = i == 0)
        }
    }

    /** 没有逐词标签的整行文字：按句末标点切开，按字数分配这一行的时间。 */
    private fun addUntimedLine(plain: String, cueStart: Long, cueEnd: Long, out: MutableList<CaptionWord>) {
        val pieces = splitAfterTerminal(plain)
        if (pieces.isEmpty()) return
        var totalWeight = 0L
        for (piece in pieces) totalWeight += maxOf(1, piece.length).toLong()
        var done = 0L
        for ((index, piece) in pieces.withIndex()) {
            val start = cueStart + (cueEnd - cueStart) * done / totalWeight
            done += maxOf(1, piece.length).toLong()
            val end = cueStart + (cueEnd - cueStart) * done / totalWeight
            out += CaptionWord(piece, start, maxOf(end, start), lineStart = index == 0)
        }
    }

    private fun splitAfterTerminal(text: String): List<String> {
        val pieces = ArrayList<String>()
        val builder = StringBuilder()
        for ((index, ch) in text.withIndex()) {
            builder.append(ch)
            val next = text.getOrNull(index + 1)
            val terminal = ch in TERMINAL_CHARS || (ch == '.' && (next == null || next.isWhitespace()))
            val nextIsTerminal = next != null && next in TERMINAL_CHARS
            if (terminal && !nextIsTerminal) {
                pieces += builder.toString()
                builder.setLength(0)
            }
        }
        if (builder.isNotBlank()) pieces += builder.toString()
        return pieces
    }

    private fun cleanWordText(raw: String): String =
        SubtitleTextDecoder.decode(anyTagRegex.replace(raw, ""))

    private fun parseTimeMs(value: String): Long? {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondParts = parts.last().split('.')
        val seconds = secondParts[0].toLongOrNull() ?: return null
        val fraction = secondParts.getOrNull(1)?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + fraction
    }

    // ---- 第 2 步：断句 -------------------------------------------------------------------

    private fun endsSentence(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last in TERMINAL_CHARS || last == '.'
    }

    internal fun buildSentences(words: List<CaptionWord>): List<List<CaptionWord>> {
        val out = ArrayList<List<CaptionWord>>()
        var current = ArrayList<CaptionWord>()
        for (word in words) {
            current.add(word)
            if (endsSentence(word.text)) {
                out.add(current)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun capLength(sentence: List<CaptionWord>, out: MutableList<List<CaptionWord>>) {
        val total = unitsOfWords(sentence)
        if (total <= MAX_SENTENCE_UNITS || sentence.size < 2) {
            out += sentence
            return
        }
        val cut = findCut(sentence, total)
        if (cut < 1) {
            // 找不到两边都不太短的切点（比如一个很短的词后面紧跟一整块长文字）：宁可留成一句，也不切出碎片。
            out += sentence
            return
        }
        capLength(sentence.subList(0, cut), out)
        capLength(sentence.subList(cut, sentence.size), out)
    }

    /**
     * 优先在 、，, 处切（选最靠近中间的）；没有就在最靠近中间的词边界切。
     * 两边都至少要占总长的 [MIN_SPLIT_SHARE]，否则返回 -1（不切）。返回左半边的词数。
     */
    private fun findCut(sentence: List<CaptionWord>, total: Double): Int {
        val half = total / 2.0
        var accumulated = 0.0
        var bestBreakIndex = -1
        var bestBreakDistance = Double.MAX_VALUE
        var bestAnyIndex = -1
        var bestAnyDistance = Double.MAX_VALUE
        for (i in 0 until sentence.size - 1) {
            accumulated += unitsOf(sentence[i].text)
            if (accumulated < total * MIN_SPLIT_SHARE || total - accumulated < total * MIN_SPLIT_SHARE) continue
            val distance = abs(accumulated - half)
            if (distance < bestAnyDistance) {
                bestAnyDistance = distance
                bestAnyIndex = i + 1
            }
            val last = sentence[i].text.trimEnd().lastOrNull()
            if (last != null && last in BREAK_CHARS && distance < bestBreakDistance) {
                bestBreakDistance = distance
                bestBreakIndex = i + 1
            }
        }
        return if (bestBreakIndex > 0) bestBreakIndex else bestAnyIndex
    }

    // ---- 第 3 步：拼成字幕行、定起止时间 -----------------------------------------------------

    private fun toLines(sentences: List<List<CaptionWord>>): List<SubLine> {
        val drafts = ArrayList<SubLine>(sentences.size)
        for (sentence in sentences) {
            if (sentence.isEmpty()) continue
            val text = SubtitleParsers.cleanSpokenText(joinWords(sentence))
            if (text.none { it.isLetterOrDigit() }) continue
            val start = sentence.first().startMs
            val last = sentence.last()
            var end = last.endMs
            if (sentence.size >= 2 && last.text.none { it.isLetterOrDigit() }) {
                end = minOf(end, last.startMs + TRAILING_PAUSE_MS)
            }
            if (end <= start) end = start + MIN_DURATION_MS
            drafts += SubLine(drafts.size, start, end, text)
        }
        val out = ArrayList<SubLine>(drafts.size)
        for ((index, draft) in drafts.withIndex()) {
            val next = drafts.getOrNull(index + 1)
            var end = draft.endMs
            if (next != null && next.startMs > draft.startMs && next.startMs < end) end = next.startMs
            out += draft.copy(endMs = end)
        }
        return out
    }

    private fun joinWords(words: List<CaptionWord>): String {
        val builder = StringBuilder()
        for (word in words) {
            if (word.lineStart && builder.isNotEmpty() &&
                needsSpaceBetween(builder[builder.length - 1], word.text.firstOrNull())
            ) {
                builder.append(' ')
            }
            builder.append(word.text)
        }
        return builder.toString()
    }

    private fun needsSpaceBetween(left: Char, right: Char?): Boolean {
        if (right == null || left.isWhitespace() || right.isWhitespace()) return false
        return !isNoSpaceScript(left) && !isNoSpaceScript(right)
    }

    /** 中文、日文（汉字、假名、全角符号）词与词之间不加空格。 */
    private fun isNoSpaceScript(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private fun unitsOf(text: String): Double {
        var units = 0.0
        for (ch in text) {
            if (ch.isLetterOrDigit()) units += if (ch.code < 0x250) LATIN_UNIT else 1.0
        }
        return units
    }

    private fun unitsOfWords(words: List<CaptionWord>): Double {
        var units = 0.0
        for (word in words) units += unitsOf(word.text)
        return units
    }

    // ---- 第 4 步：核对文字没有被改动 ---------------------------------------------------------

    private fun normalizeForCompare(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) if (ch.isLetterOrDigit()) builder.append(ch.lowercaseChar())
        return builder.toString()
    }

    /**
     * 原来的行里的文字（只看字母数字）必须几乎都能在合句结果里按顺序找到。
     * 反过来不要求：原来的行解析时可能丢过文字，逐词流里反而更全，多出来的部分是好事。
     * 具体：合句结果的长度是原来的 0.9~1.6 倍；原来的行按 8 个字一段，依次在合句结果里找得到的比例不低于 90%（原来的行自己丢字的地方，会让跨过缺口的那一段对不上，所以留出余量）。
     */
    private fun textMatches(mine: String, original: String): Boolean {
        if (mine.isEmpty() || original.isEmpty()) return false
        val ratio = mine.length.toDouble() / original.length
        if (ratio < VERIFY_MIN_LENGTH_RATIO || ratio > VERIFY_MAX_LENGTH_RATIO) return false
        if (original.length < VERIFY_WINDOW * 2) return mine.contains(original)

        val totalWindows = original.length / VERIFY_WINDOW
        val allowedMisses = (totalWindows * (1.0 - VERIFY_MIN_MATCH)).toInt()
        var misses = 0
        var cursor = 0
        for (window in 0 until totalWindows) {
            val chunk = original.substring(window * VERIFY_WINDOW, (window + 1) * VERIFY_WINDOW)
            val found = mine.indexOf(chunk, maxOf(0, cursor - 64))
            if (found >= 0 && found - cursor <= VERIFY_MAX_GAP) {
                cursor = found + VERIFY_WINDOW
            } else {
                misses++
                if (misses > allowedMisses) return false
            }
        }
        return true
    }
}
