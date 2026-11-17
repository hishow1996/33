package io.translatetube.local.translate

/** 连续的若干字幕行 [start, endExclusive)，会合成一个整句交给逐行翻译引擎。 */
internal data class SentenceGroup(val start: Int, val endExclusive: Int) {
    val size: Int get() = endExclusive - start
}

/**
 * 逐行翻译的引擎（网页、官方接口、OPUS-MT、SMaLL-100、ML Kit）看不到上下文，被切开的半句话会翻得很怪。
 * 这里把"明显没说完"的相邻几行合成一个整句再翻译，译完按原文长度比例拆回各行。
 * 全部是不依赖 Android 的纯逻辑。判断保守：拿不准就不合并，拆不开就退回逐行翻译。
 */
internal object SentenceGroupingPolicy {
    const val MAX_GROUP_LINES = 3
    const val MAX_GROUP_CHARS = 220
    private const val MIN_PIECE_CHARS = 2
    private const val SENTENCE_END = ".!?…。！？‼⁇"
    private const val CONTINUATION_END = ",;:-–—，；："
    private const val CLOSERS = "\"'”’)]}»›」』 \t"
    private const val NO_LINE_START = "，。！？、；：,.!?;:)]}）】」』”’》…%"
    private const val NO_LINE_END = "([{（【「『“‘《"
    private const val PREFERRED_CUT_AFTER = "，。！？、；：,.!?;:…—"
    private const val NON_SPEECH_START = "-–—>[(♪*"
    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

    /** [rowIndices] 与 [lines] 一一对应；只有行号连续的相邻行才可能合并。 */
    fun plan(lines: List<String>, rowIndices: List<Int>): List<SentenceGroup> {
        val groups = ArrayList<SentenceGroup>(lines.size)
        if (rowIndices.size != lines.size) {
            for (index in lines.indices) groups += SentenceGroup(index, index + 1)
            return groups
        }
        var start = 0
        while (start < lines.size) {
            var end = start + 1
            var chars = lines[start].trim().length
            while (end < lines.size && end - start < MAX_GROUP_LINES) {
                if (rowIndices[end] != rowIndices[end - 1] + 1) break
                if (!continues(lines[end - 1], lines[end])) break
                val nextChars = lines[end].trim().length
                if (chars + 1 + nextChars > MAX_GROUP_CHARS) break
                chars += 1 + nextChars
                end++
            }
            groups += SentenceGroup(start, end)
            start = end
        }
        return groups
    }

    fun join(lines: List<String>, group: SentenceGroup): String =
        (group.start until group.endExclusive).joinToString(" ") { lines[it].trim() }

    /** [next] 是不是 [previous] 这句话的延续。 */
    fun continues(previous: String, next: String): Boolean {
        val a = previous.trim()
        val b = next.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a[0] in NON_SPEECH_START || b[0] in NON_SPEECH_START) return false
        if (!isSpaceDelimited(a) || !isSpaceDelimited(b)) return false
        val last = lastSignificantChar(a) ?: return false
        if (last in SENTENCE_END) return false
        if (last in CONTINUATION_END) return true
        val first = b.firstOrNull { it.isLetter() } ?: return false
        return first.isLowerCase()
    }

    /**
     * 按各行原文长度的比例，把整句译文拆回 [sourceLines].size 段。优先在标点后切，不会切断英文单词，
     * 每段至少 [MIN_PIECE_CHARS] 个字。拆不出来就返回 null，由调用方退回逐行翻译。
     */
    fun split(translation: String, sourceLines: List<String>): List<String>? {
        val count = sourceLines.size
        val text = translation.lineSequence().joinToString(" ") { it.trim() }.trim()
        if (count <= 0 || text.isEmpty()) return null
        if (count == 1) return listOf(text)
        val length = text.length
        if (length < count * MIN_PIECE_CHARS) return null

        val weights = sourceLines.map { line -> line.count { !it.isWhitespace() }.coerceAtLeast(1) }
        val totalWeight = weights.sum().toDouble()
        val penalty = maxOf(2.0, length * 0.2)
        val cuts = IntArray(count - 1)
        var previousCut = 0
        var cumulative = 0
        for (index in 0 until count - 1) {
            cumulative += weights[index]
            val ideal = length * cumulative / totalWeight
            val low = previousCut + MIN_PIECE_CHARS
            val high = length - MIN_PIECE_CHARS * (count - 1 - index)
            if (low > high) return null
            var best = -1
            var bestCost = Double.MAX_VALUE
            for (position in low..high) {
                if (!allowedCut(text, position)) continue
                val preferred = text[position - 1] in PREFERRED_CUT_AFTER ||
                    (text[position - 1] == ' ' && position >= 2 && text[position - 2] in PREFERRED_CUT_AFTER)
                val cost = Math.abs(position - ideal) + if (preferred) 0.0 else penalty
                if (cost < bestCost) {
                    bestCost = cost
                    best = position
                }
            }
            if (best < 0) return null
            cuts[index] = best
            previousCut = best
        }

        val pieces = ArrayList<String>(count)
        var from = 0
        for (index in 0 until count) {
            val to = if (index < count - 1) cuts[index] else length
            var piece = text.substring(from, to).trim()
            // 非最后一段结尾的逗号顿号在单独一行字幕里很别扭，去掉。
            if (index < count - 1) piece = piece.trimEnd('，', '、', ',', '；', ';', ' ')
            if (piece.isEmpty()) return null
            pieces += piece
            from = to
        }
        return pieces
    }

    private fun allowedCut(text: String, position: Int): Boolean {
        val left = text[position - 1]
        val right = text[position]
        if (isAsciiWordChar(left) && isAsciiWordChar(right)) return false
        if (right in NO_LINE_START) return false
        if (left in NO_LINE_END) return false
        if (Character.isHighSurrogate(left)) return false
        return true
    }

    private fun isAsciiWordChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

    private fun lastSignificantChar(text: String): Char? {
        var index = text.length - 1
        while (index >= 0 && text[index] in CLOSERS) index--
        return if (index >= 0) text[index] else null
    }

    private fun isSpaceDelimited(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.UnicodeScript.of(codePoint) in UNSPACED_SCRIPTS) return false
            index += Character.charCount(codePoint)
        }
        return true
    }
}

/** 把合句、翻译、拆回、失败退回逐行串起来；真正的翻译请求由 [translateBatch] 提供。 */
internal object SentenceGroupedTranslation {
    class Stats(val mergedGroups: Int, val mergedLines: Int, val fallbackLines: Int)

    suspend fun translate(
        lines: List<String>,
        rowIndices: List<Int>,
        report: (Stats) -> Unit = {},
        translateBatch: suspend (List<String>) -> List<String>,
    ): List<String> {
        val groups = SentenceGroupingPolicy.plan(lines, rowIndices)
        if (groups.all { it.size == 1 }) return translateBatch(lines)

        val units = groups.map { group ->
            if (group.size == 1) lines[group.start] else SentenceGroupingPolicy.join(lines, group)
        }
        val unitResults = translateBatch(units)
        // 引擎返回条数不对：按老办法逐行重来，交给上层原有的校验处理。
        if (unitResults.size != units.size) return translateBatch(lines)

        val out = arrayOfNulls<String>(lines.size)
        val failedRows = ArrayList<Int>()
        groups.forEachIndexed { groupIndex, group ->
            if (group.size == 1) {
                out[group.start] = unitResults[groupIndex]
                return@forEachIndexed
            }
            val pieces = unitResults[groupIndex].takeIf { it.isNotBlank() }?.let {
                SentenceGroupingPolicy.split(it, lines.subList(group.start, group.endExclusive))
            }
            if (pieces == null) {
                for (row in group.start until group.endExclusive) failedRows += row
            } else {
                pieces.forEachIndexed { offset, piece -> out[group.start + offset] = piece }
            }
        }
        if (failedRows.isNotEmpty()) {
            val retried = translateBatch(failedRows.map { lines[it] })
            if (retried.size != failedRows.size) return translateBatch(lines)
            failedRows.forEachIndexed { position, row -> out[row] = retried[position] }
        }
        val merged = groups.filter { it.size > 1 }
        report(Stats(merged.size, merged.sumOf { it.size }, failedRows.size))
        return out.map { it ?: "" }
    }
}
