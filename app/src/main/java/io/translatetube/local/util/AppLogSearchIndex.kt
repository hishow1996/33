package io.translatetube.local.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

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
) {
    internal fun matches(normalizedQuery: String): Boolean =
        normalizedFields.any { it.contains(normalizedQuery) } ||
            record.details.contains(normalizedQuery, ignoreCase = true)
}

internal object AppLogSearchIndex {

    /** Build diagnosis and searchable metadata once for each loaded record. */
    suspend fun build(records: List<AppLogRecord>): List<AppLogSearchEntry> =
        withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            val result = ArrayList<AppLogSearchEntry>(records.size)
            records.forEachIndexed { index, record ->
                if (index % 8 == 0) coroutineContext.ensureActive()
                val diagnosis = AppLogDiagnostics.analyze(record)
                val fields = buildList {
                    add(record.tag)
                    add(record.category)
                    add(AppLogTitleResolver.featureLabel(record.tag, record.message))
                    add(AppLogTitleResolver.title(record.tag, record.message))
                    add(record.message)
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
                result += AppLogSearchEntry(record, diagnosis, fields)
            }
            result
        }

    /**
     * Apply level/status filtering and text matching off the UI thread.
     * Cancellation checks let the newest keystroke supersede obsolete searches.
     */
    suspend fun search(
        entries: List<AppLogSearchEntry>,
        filter: String,
        query: String,
    ): List<AppLogSearchEntry> = withContext(Dispatchers.Default) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val coroutineContext = currentCoroutineContext()
        val result = ArrayList<AppLogSearchEntry>()
        entries.forEachIndexed { index, entry ->
            if (index % 32 == 0) coroutineContext.ensureActive()
            val record = entry.record
            val passesFilter = when (filter) {
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
            if (passesFilter && (normalizedQuery.isBlank() || entry.matches(normalizedQuery))) {
                result += entry
            }
        }
        result
    }

    private fun isIssueLevel(level: String): Boolean =
        level == "WARN" || level == "ERROR" || level == "FATAL"
}
