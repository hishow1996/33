package io.translatetube.local.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 日志搜索词。
 *
 * - 空格（含全角空格）分隔的多个词必须同时出现；
 * - `-词` 表示排除（只看标题、模块、摘要、诊断字段，不扫堆栈详情）；
 * - 用引号（"…" 或 “…”）包起来的短语按整串匹配，引号里可以有空格；
 * - 时间不用特殊语法：日志时间 `yyyy-MM-dd HH:mm:ss.SSS` 本身是可搜索字段，
 *   直接输入 `10:58`、`11-15 10:5` 即可。
 */
internal data class AppLogSearchQuery(
    /** 小写后的必须出现的词。 */
    val include: List<String>,
    /** 小写后的必须不出现的词。 */
    val exclude: List<String>,
) {
    val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty()

    /** 在已转小写的字段里匹配：每个必含词至少出现在一个字段里，排除词一个字段里都不能有。 */
    fun matchesFields(lowercaseFields: List<String>): Boolean =
        exclude.none { term -> lowercaseFields.any { it.contains(term) } } &&
            include.all { term -> lowercaseFields.any { it.contains(term) } }

    companion object {
        val EMPTY = AppLogSearchQuery(emptyList(), emptyList())

        fun parse(raw: String): AppLogSearchQuery {
            val text = raw.trim()
            if (text.isEmpty()) return EMPTY
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            var index = 0
            while (index < text.length) {
                if (isSeparator(text[index])) {
                    index++
                    continue
                }
                var negate = false
                if (text[index] == '-' && index + 1 < text.length && !isSeparator(text[index + 1])) {
                    negate = true
                    index++
                }
                val token: String
                if (isOpeningQuote(text[index])) {
                    val closing = findClosingQuote(text, index)
                    val end = if (closing < 0) text.length else closing
                    token = text.substring(index + 1, end)
                    index = if (closing < 0) text.length else closing + 1
                } else {
                    val start = index
                    while (index < text.length && !isSeparator(text[index])) index++
                    token = text.substring(start, index)
                }
                val normalized = token.trim().lowercase(Locale.ROOT)
                if (normalized.isEmpty()) continue
                if (negate) exclude += normalized else include += normalized
            }
            return AppLogSearchQuery(include.distinct(), exclude.distinct())
        }

        private fun isSeparator(c: Char): Boolean = c.isWhitespace() || c == '　'

        private fun isOpeningQuote(c: Char): Boolean = c == '"' || c == '“'

        private fun findClosingQuote(text: String, openIndex: Int): Int {
            val closers = if (text[openIndex] == '“') charArrayOf('”', '"') else charArrayOf('"')
            for (i in openIndex + 1 until text.length) {
                if (text[i] in closers) return i
            }
            return -1
        }
    }
}

/** 日志页的时间范围快捷筛选。 */
internal enum class AppLogTimeRange(val label: String) {
    ALL("全部时间"),
    LAST_10_MIN("最近 10 分钟"),
    LAST_HOUR("最近 1 小时"),
    TODAY("今天");

    /** 该范围的起点（毫秒时间戳）；0 表示不限制。 */
    fun sinceMs(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long = when (this) {
        ALL -> 0L
        LAST_10_MIN -> nowMs - 10L * 60_000L
        LAST_HOUR -> nowMs - 60L * 60_000L
        TODAY -> Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

/** 找出文本里所有搜索词的位置，用于在日志卡片上高亮。 */
internal object AppLogSearchHighlight {
    /** 返回按位置排序且互不重叠的 [start, endExclusive) 区间。 */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (from <= text.length - term.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                found += at until at + term.length
                from = at + term.length
            }
        }
        if (found.isEmpty()) return emptyList()
        found.sortWith(compareBy({ it.first }, { it.last }))
        val merged = ArrayList<IntRange>(found.size)
        var current = found.first()
        for (range in found.drop(1)) {
            if (range.first <= current.last + 1) {
                if (range.last > current.last) current = current.first..range.last
            } else {
                merged += current
                current = range
            }
        }
        merged += current
        return merged
    }
}

/** 最近搜索：去重、最新在前、最多保留若干条。 */
internal object AppLogSearchHistoryPolicy {
    const val MAX_ITEMS = 5

    fun push(history: List<String>, query: String, maxItems: Int = MAX_ITEMS): List<String> {
        val clean = query.trim().replace('\n', ' ')
        if (clean.length < 2) return history
        return (listOf(clean) + history.filterNot { it.equals(clean, ignoreCase = true) })
            .take(maxItems)
    }

    fun encode(history: List<String>): String = history.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
}

/** 日志页的搜索/筛选状态，只在进程内保留：点进某条日志再返回不丢，重启应用后复位。 */
internal object AppLogSearchMemory {
    @Volatile var query: String = ""
    @Volatile var levelFilter: String = "全部"
    @Volatile var module: String = ""
    @Volatile var timeRange: AppLogTimeRange = AppLogTimeRange.ALL
}
