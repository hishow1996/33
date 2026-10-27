package io.translatetube.local.asr

import java.util.Locale

/**
 * 清理 Whisper / SenseVoice 在静音、音乐、噪声段上常见的"幻觉"输出，避免它们被翻译和配音读出来：
 *
 * 1. 字幕组/平台署名（"Subtitles by the Amara.org community"、"字幕由 Amara.org 社区提供"……）：
 *    这类整行几乎不可能是真实说话内容，直接丢弃。
 * 2. 解码循环（同一句话重复很多遍直到长度上限）：折叠成一份。只折叠"明显的循环"：
 *    英文等按词计，单词重复 ≥5 次、两词短语 ≥4 次、三词以上短语 ≥3 次；中日文按字计，≥3 字的短语重复 ≥3 次。
 *    正常说话里的口吃、强调（"no no no"）达不到这个门槛，保持原样。
 * 3. 同一语音段内连续多行完全相同（循环被时间戳切成多行）：只保留第一行。
 *
 * 纯逻辑，JVM 可测；不影响时间戳（只删行或缩短文字，行的起止时间保持不变）。
 */
internal object AsrHallucinationPolicy {
    private const val MAX_CREDIT_LINE_CHARS = 90
    private const val MAX_WORD_PERIOD = 16
    private const val MAX_CHAR_PERIOD = 24
    private const val MIN_UNITS_TO_CHECK = 6
    private val whitespaceRegex = Regex("\\s+")

    /** 出现在整行任意位置就算署名。 */
    private val creditMarkers = listOf(
        "amara.org",
        "字幕由",
        "字幕志愿者",
        "明镜与点点栏目",
        "优优独播剧场",
        "yoyo television series exclusive",
        "请不吝点赞",
    )

    /** 英文署名必须出现在行首，避免误伤"…the subtitles by hand…"这类真实说话。 */
    private val creditPrefixes = listOf("subtitles by", "subtitled by")

    /** 整行是字幕组/平台署名这类几乎不可能是真实说话的内容。 */
    fun isCreditLine(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty() || normalized.length > MAX_CREDIT_LINE_CHARS) return false
        return creditMarkers.any { normalized.contains(it) } ||
            creditPrefixes.any { normalized.startsWith(it) }
    }

    /** 折叠明显的重复循环；没有循环时原样返回同一个字符串。 */
    fun collapseRepetition(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        val charMode = isCjkDominant(trimmed)
        val units: List<String> = if (charMode) {
            val result = ArrayList<String>(trimmed.length)
            var offset = 0
            while (offset < trimmed.length) {
                val codePoint = trimmed.codePointAt(offset)
                val width = Character.charCount(codePoint)
                if (!Character.isWhitespace(codePoint)) result += trimmed.substring(offset, offset + width)
                offset += width
            }
            result
        } else {
            trimmed.split(whitespaceRegex).filter { it.isNotEmpty() }
        }
        if (units.size < MIN_UNITS_TO_CHECK) return text
        val normalized = units.map { normalizeUnit(it) }
        val collapsed = collapse(units, normalized, wordMode = !charMode) ?: return text
        return if (charMode) collapsed.joinToString("") else collapsed.joinToString(" ")
    }

    /**
     * 清理同一语音段识别出的行：丢署名行、折叠循环、合并连续相同的行。
     * 调用方应按语音段分别调用（不要把不同语音段的行混在一起，真实歌词/复述可能合法地连续重复）。
     */
    fun cleanRows(rows: List<Triple<Long, Long, String>>): List<Triple<Long, Long, String>> {
        if (rows.isEmpty()) return rows
        val cleaned = ArrayList<Triple<Long, Long, String>>(rows.size)
        for ((start, end, text) in rows) {
            if (isCreditLine(text)) continue
            val collapsed = collapseRepetition(text)
            if (collapsed.isBlank()) continue
            cleaned += if (collapsed == text) Triple(start, end, text) else Triple(start, end, collapsed)
        }
        val result = ArrayList<Triple<Long, Long, String>>(cleaned.size)
        var index = 0
        while (index < cleaned.size) {
            val key = normalizeUnit(cleaned[index].third)
            var next = index + 1
            if (key.isNotEmpty()) {
                while (next < cleaned.size && normalizeUnit(cleaned[next].third) == key) next++
            }
            val run = next - index
            val threshold = if (isLongRow(cleaned[index].third)) 3 else 6
            if (run >= threshold) {
                result += cleaned[index]
            } else {
                for (k in index until next) result += cleaned[k]
            }
            index = next
        }
        return result
    }

    private fun collapse(units: List<String>, normalized: List<String>, wordMode: Boolean): List<String>? {
        var changed = false
        val output = ArrayList<String>(units.size)
        val maxPeriod = if (wordMode) MAX_WORD_PERIOD else MAX_CHAR_PERIOD
        var index = 0
        while (index < units.size) {
            var handled = false
            if (normalized[index].isNotEmpty()) {
                val periodLimit = minOf(maxPeriod, (units.size - index) / 2)
                for (period in 1..periodLimit) {
                    val required = minRepeats(period, wordMode)
                    if (required == Int.MAX_VALUE) continue
                    // 周期内全是同一个词/字（"哈哈哈"）属于单字重复，由单词重复规则管，不按短语折叠。
                    if (period > 1 && allSame(normalized, index, period)) continue
                    var repeats = 1
                    while (
                        index + (repeats + 1) * period <= units.size &&
                        blockEquals(normalized, index, index + repeats * period, period)
                    ) {
                        repeats++
                    }
                    if (repeats < required) continue
                    // 单词重复保留 2 份（"no no"），短语/整句循环只留 1 份。
                    val keep = if (wordMode && period == 1) 2 else 1
                    for (copy in 0 until keep) {
                        for (k in 0 until period) output += units[index + k]
                    }
                    var next = index + repeats * period
                    // 循环常在中途被截断，剩下的是周期开头的几个词：只有它一直延伸到结尾时才算循环的尾巴。
                    var partial = 0
                    while (
                        partial < period - 1 &&
                        next + partial < units.size &&
                        normalized[next + partial] == normalized[index + partial]
                    ) {
                        partial++
                    }
                    if (next + partial == units.size) next += partial
                    index = next
                    changed = true
                    handled = true
                    break
                }
            }
            if (!handled) {
                output += units[index]
                index++
            }
        }
        return if (changed) output else null
    }

    private fun minRepeats(period: Int, wordMode: Boolean): Int = if (wordMode) {
        when {
            period == 1 -> 5
            period == 2 -> 4
            else -> 3
        }
    } else {
        if (period < 3) Int.MAX_VALUE else 3
    }

    private fun allSame(values: List<String>, first: Int, length: Int): Boolean {
        for (k in 1 until length) {
            if (values[first + k] != values[first]) return false
        }
        return true
    }

    private fun blockEquals(values: List<String>, first: Int, second: Int, length: Int): Boolean {
        for (k in 0 until length) {
            if (values[first + k] != values[second + k]) return false
        }
        return true
    }

    /** 只保留字母数字并转小写，用来比较"是不是同一句话"（忽略标点和大小写）。 */
    internal fun normalizeUnit(value: String): String {
        val builder = StringBuilder(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toLowerCase(codePoint))
            }
            offset += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private fun isLongRow(text: String): Boolean {
        val trimmed = text.trim()
        if (isCjkDominant(trimmed)) return trimmed.codePointCount(0, trimmed.length) >= 8
        return trimmed.split(whitespaceRegex).size >= 4
    }

    internal fun isCjkDominant(text: String): Boolean {
        var cjk = 0
        var visible = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) continue
            visible++
            if (isCjkCodePoint(codePoint)) cjk++
        }
        return visible > 0 && cjk * 2 >= visible
    }

    internal fun isCjkCodePoint(codePoint: Int): Boolean =
        codePoint in 0x2E80..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x3040..0x30FF ||
            codePoint in 0xAC00..0xD7AF ||
            codePoint in 0x20000..0x2FA1F ||
            codePoint in 0x30000..0x323AF
}
