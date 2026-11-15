package io.translatetube.local.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

/**
 * Reusable per-record search data for the application log screen.
 *
 * Small, frequently queried fields and diagnosis results are normalized once per loaded log
 * snapshot. Large details/stack strings remain referenced from AppLogRecord rather than copied
 * into a second large string, and are only scanned on the background dispatcher.
 */
internal data class AppLogSearchEntry(
    val record: AppLogRecord,
    val diagnosis: AppLogDiagnosis,
    private val normalizedFields: List<String>,
    /** 模块下拉用的中文功能名，例如「字幕翻译」。 */
    val featureLabel: String = "",
) {
    internal enum class Match {
        NONE,

        /** 所有搜索词都在标题/模块/摘要/诊断字段里命中。 */
        FIELDS,

        /** 至少有一个搜索词只在堆栈详情里命中。 */
        DETAILS_ONLY,
    }

    internal fun matches(normalizedQuery: String): Boolean =
        match(AppLogSearchQuery.parse(normalizedQuery)) != Match.NONE

    internal fun match(query: AppLogSearchQuery): Match {
        if (query.isEmpty) return Match.FIELDS
        // 排除词只看字段：扫整段堆栈既慢，也会把"堆栈里碰巧提到该词"的日志误排除。
        for (term in query.exclude) {
            if (fieldsContain(term)) return Match.NONE
        }
        var needsDetails = false
        for (term in query.include) {
            if (fieldsContain(term)) continue
            if (record.details.contains(term, ignoreCase = true)) needsDetails = true else return Match.NONE
        }
        return if (needsDetails) Match.DETAILS_ONLY else Match.FIELDS
    }

    private fun fieldsContain(term: String): Boolean = normalizedFields.any { it.contains(term) }
}

/** 一次搜索的结果：字段命中的在前，仅详情命中的在后。 */
internal class AppLogSearchResult(
    val entries: List<AppLogSearchEntry>,
    private val detailsOnly: Set<AppLogSearchEntry>,
    val query: AppLogSearchQuery,
) {
    fun isDetailsOnly(entry: AppLogSearchEntry): Boolean = entry in detailsOnly

    val detailsOnlyCount: Int get() = detailsOnly.size
}

internal object AppLogSearchIndex {

    /** Build diagnosis and searchable metadata once for each loaded record. */
    suspend fun build(
        records: List<AppLogRecord>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<AppLogSearchEntry> =
        withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { this.timeZone = timeZone }
            val result = ArrayList<AppLogSearchEntry>(records.size)
            records.forEachIndexed { index, record ->
                if (index % 8 == 0) coroutineContext.ensureActive()
                val diagnosis = AppLogDiagnostics.analyze(record)
                val featureLabel = AppLogTitleResolver.featureLabel(record.tag, record.message)
                val fields = buildList {
                    add(record.tag)
                    add(record.category)
                    add(featureLabel)
                    add(AppLogTitleResolver.title(record.tag, record.message))
                    add(record.message)
                    // 日志时间本身可搜索：输入 10:58、11-15 10:5 都能命中。
                    add(timeFormat.format(Date(record.timestampMs)))
                    add(record.level)
                    add(record.thread)
                    add(record.eventId)
                    add(record.sessionId)
                    add(record.operationId)
                    add(record.errorClass)
                    add(record.rootCauseMessage)
                    add(record.firstAppStackFrame)
                    add(record.issueKey)
                    add(record.resolvesIssueKey)
                    add(diagnosis.finding)
                    add(diagnosis.category)
                    record.diagnosticContext.forEach { (key, value) ->
                        add(key)
                        add(value)
                    }
                }.asSequence()
                    .filter { it.isNotBlank() }
                    .map { it.lowercase(Locale.ROOT) }
                    .toList()
                result += AppLogSearchEntry(record, diagnosis, fields, featureLabel)
            }
            result
        }

    /** 日志里出现过的功能模块及条数，条数多的在前，用于模块下拉。 */
    fun moduleCounts(entries: List<AppLogSearchEntry>): List<Pair<String, Int>> =
        entries.asSequence()
            .map { it.featureLabel }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Apply level/status filtering and text matching off the UI thread.
     * Cancellation checks let the newest keystroke supersede obsolete searches.
     */
    suspend fun search(
        entries: List<AppLogSearchEntry>,
        filter: String,
        query: String,
    ): List<AppLogSearchEntry> = searchDetailed(entries, filter, query).entries

    /**
     * 级别筛选 + 模块 + 时间范围 + 多词搜索可以叠加。
     * @param module 功能模块名，空表示不限。
     * @param sinceMs 只保留该时间之后的日志，0 表示不限。
     */
    suspend fun searchDetailed(
        entries: List<AppLogSearchEntry>,
        filter: String,
        query: String,
        module: String = "",
        sinceMs: Long = 0L,
    ): AppLogSearchResult = withContext(Dispatchers.Default) {
        val parsedQuery = AppLogSearchQuery.parse(query)
        val coroutineContext = currentCoroutineContext()
        val fieldMatches = ArrayList<AppLogSearchEntry>()
        val detailsOnlyMatches = ArrayList<AppLogSearchEntry>()
        entries.forEachIndexed { index, entry ->
            if (index % 32 == 0) coroutineContext.ensureActive()
            val record = entry.record
            if (!passesLevelFilter(filter, record)) return@forEachIndexed
            if (module.isNotBlank() && entry.featureLabel != module) return@forEachIndexed
            if (sinceMs > 0L && record.timestampMs < sinceMs) return@forEachIndexed
            when (entry.match(parsedQuery)) {
                AppLogSearchEntry.Match.FIELDS -> fieldMatches += entry
                AppLogSearchEntry.Match.DETAILS_ONLY -> detailsOnlyMatches += entry
                AppLogSearchEntry.Match.NONE -> Unit
            }
        }
        val detailsOnlySet: MutableSet<AppLogSearchEntry> =
            Collections.newSetFromMap(IdentityHashMap())
        detailsOnlySet.addAll(detailsOnlyMatches)
        AppLogSearchResult(fieldMatches + detailsOnlyMatches, detailsOnlySet, parsedQuery)
    }

    private fun passesLevelFilter(filter: String, record: AppLogRecord): Boolean = when (filter) {
        "错误" -> record.level == "ERROR" || record.level == "FATAL"
        "未恢复" -> isIssueLevel(record.level) &&
            record.issueKey.isNotBlank() && record.recoveredAtMs == null
        "已恢复" -> isIssueLevel(record.level) &&
            record.issueKey.isNotBlank() && record.recoveredAtMs != null
        "闪退" -> record.level == "FATAL" ||
            record.tag.contains("Crash", ignoreCase = true) ||
            record.tag == "PreviousCrash" ||
            (record.tag == "ProcessExitInfo" &&
                (record.level == "ERROR" || record.level == "FATAL"))
        "警告" -> record.level == "WARN"
        else -> true
    }

    private fun isIssueLevel(level: String): Boolean =
        level == "WARN" || level == "ERROR" || level == "FATAL"
}
