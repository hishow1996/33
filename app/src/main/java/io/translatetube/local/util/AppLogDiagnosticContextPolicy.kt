package io.translatetube.local.util

internal data class AppLogDiagnosticContextBudget(
    val fields: Map<String, String>,
    val totalFieldCount: Int,
    val retainedFieldCount: Int,
    val omittedFieldCount: Int,
    val truncatedFieldCount: Int,
    val omittedFieldKeys: List<String>,
    val truncatedFieldKeys: List<String>,
)

/** Bounds persisted context and reserves six fields to report every omission or truncation. */
internal object AppLogDiagnosticContextPolicy {
    const val MAX_FIELDS = 80
    const val MAX_VALUE_CHARS = 2_000
    private const val METADATA_FIELD_COUNT = 6
    private const val MAX_REPORTED_KEYS = 16

    fun bound(
        input: Map<String, String>,
        priorityKeys: Set<String>,
        maxFields: Int = MAX_FIELDS,
        maxValueChars: Int = MAX_VALUE_CHARS,
        sanitize: (String) -> String = { it },
    ): AppLogDiagnosticContextBudget {
        require(maxFields >= METADATA_FIELD_COUNT)
        require(maxValueChars > 0)
        val ordered = input.entries.sortedBy { if (it.key in priorityKeys) 0 else 1 }
        val retained = ordered.take(maxFields - METADATA_FIELD_COUNT)
        val omitted = ordered.drop(maxFields - METADATA_FIELD_COUNT)
        val safeFields = linkedMapOf<String, String>()
        val truncatedKeys = mutableListOf<String>()

        retained.forEach { entry ->
            val baseKey = sanitize(entry.key).take(100).ifBlank { "未命名字段" }
            var key = baseKey
            var suffix = 2
            while (safeFields.containsKey(key)) {
                val suffixText = "#$suffix"
                key = baseKey.take((100 - suffixText.length).coerceAtLeast(1)) + suffixText
                suffix++
            }
            val value = sanitize(entry.value)
            val boundedValue = if (value.length <= maxValueChars) value else {
                truncatedKeys += baseKey
                val marker = "…[字段截断，脱敏后长度=${value.length}]"
                val ending = if (marker.length <= maxValueChars) marker else "…[截断]".take(maxValueChars)
                value.take((maxValueChars - ending.length).coerceAtLeast(0)) + ending
            }
            safeFields[key] = boundedValue
        }

        val omittedKeys = omitted.take(MAX_REPORTED_KEYS).map { sanitize(it.key).take(100) }
        val truncatedKeyList = truncatedKeys.distinct().take(MAX_REPORTED_KEYS)
        val omittedSummary = buildString {
            append(omittedKeys.joinToString("、").ifBlank { "无" })
            if (omitted.size > omittedKeys.size) append("；另有 ").append(omitted.size - omittedKeys.size).append(" 项")
        }

        safeFields["日志诊断字段总数"] = input.size.toString()
        safeFields["日志诊断字段已保留数"] = retained.size.toString()
        safeFields["日志诊断字段省略数"] = omitted.size.toString()
        safeFields["日志诊断字段省略项（最多16项）"] = omittedSummary
        safeFields["日志诊断字段截断数"] = truncatedKeys.size.toString()
        safeFields["日志诊断字段截断项（最多16项）"] = truncatedKeyList.joinToString("、").ifBlank { "无" }

        return AppLogDiagnosticContextBudget(
            fields = safeFields,
            totalFieldCount = input.size,
            retainedFieldCount = retained.size,
            omittedFieldCount = omitted.size,
            truncatedFieldCount = truncatedKeys.size,
            omittedFieldKeys = omittedKeys,
            truncatedFieldKeys = truncatedKeyList,
        )
    }
}
