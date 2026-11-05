package io.translatetube.local.util

/**
 * Describes only relationships supported by explicit event/session/operation identifiers.
 * Timestamp ordering is evidence of sequence, not proof that one failure caused another.
 */
internal data class AppLogEventRelation(val role: String, val evidence: String)

internal data class AppLogOperationFailureSummary(
    val sessionId: String,
    val operationId: String,
    val firstAnomalyEventId: String,
    val firstAnomalyTimestampMs: Long,
    val anomalyCount: Int,
    val laterAnomalyCount: Int,
    val recoveryCount: Int,
)

internal object AppLogEventRelationPolicy {
    private val issueLevels = setOf("WARN", "ERROR", "FATAL")

    fun eventKey(record: AppLogRecord): String =
        record.eventId.takeIf { it.isNotBlank() }?.let { "event:$it" }
            ?: "legacy:${record.timestampMs}:${record.sequence}:${record.tag}:${record.message}"

    private fun operationKey(record: AppLogRecord): String? =
        record.sessionId.takeIf { it.isNotBlank() }?.let { session ->
            record.operationId.takeIf { it.isNotBlank() }?.let { operation -> "$session|$operation" }
        }

    private fun isIssue(record: AppLogRecord): Boolean = record.level in issueLevels

    private fun compare(left: AppLogRecord, right: AppLogRecord): Int =
        compareValuesBy(left, right, AppLogRecord::timestampMs, AppLogRecord::sequence)

    fun againstSelected(
        records: List<AppLogRecord>,
        selected: AppLogRecord,
    ): Map<String, AppLogEventRelation> {
        val selectedKey = eventKey(selected)
        val selectedOperationId = selected.operationId
        val selectedTranslationDiagnosticId = TranslationDiagnosticPackPolicy.idFromRecord(selected)
        return records.associate { event ->
            val relation = when {
                eventKey(event) == selectedKey -> AppLogEventRelation(
                    "SELECTED_EVENT",
                    if (selected.recoveredAtMs != null) {
                        "当前选中事件；其故障编号已有明确恢复时间记录"
                    } else {
                        "当前选中事件；异常树和该事件上下文是直接证据"
                    },
                )
                selected.issueKey.isNotBlank() && event.resolvesIssueKey == selected.issueKey ->
                    AppLogEventRelation(
                        "RECOVERY_MATCHED_BY_ISSUE_KEY",
                        "resolvesIssueKey 与选中事件 issueKey 完全匹配；这是按故障签名关联的恢复记录，不保证对应唯一单次实例",
                    )
                selectedTranslationDiagnosticId != null &&
                    TranslationDiagnosticPackPolicy.idFromRecord(event) == selectedTranslationDiagnosticId ->
                    AppLogEventRelation(
                        "SAME_TRANSLATION_DIAGNOSTIC",
                        "翻译诊断 ID 完全匹配，属于同一翻译任务的诊断事件；时间顺序只表示处理先后，不单独证明因果",
                    )
                selected.sessionId.isNotBlank() &&
                    event.sessionId == selected.sessionId &&
                    selectedOperationId.isNotBlank() &&
                    event.operationId == selectedOperationId -> {
                    val order = compare(event, selected)
                    when {
                        isIssue(event) && selected.issueKey.isNotBlank() &&
                            event.issueKey == selected.issueKey -> AppLogEventRelation(
                                "SAME_ISSUE_SIGNATURE_SAME_OPERATION",
                                "同一 sessionId/operationId 且 issueKey 相同；是同任务内相同故障签名的另一条记录，时间顺序不证明因果",
                            )
                        isIssue(event) && order < 0 -> AppLogEventRelation(
                            "EARLIER_ANOMALY_SAME_OPERATION",
                            "sessionId 和 operationId 均一致，且该异常早于选中事件；仅是上游候选，不证明因果关系",
                        )
                        isIssue(event) && order > 0 -> AppLogEventRelation(
                            "LATER_ANOMALY_SAME_OPERATION",
                            "sessionId 和 operationId 均一致，且该异常晚于选中事件；可能是后续连锁，也可能是独立故障",
                        )
                        isIssue(event) -> AppLogEventRelation(
                            "SAME_TIME_ANOMALY_SAME_OPERATION",
                            "sessionId 和 operationId 均一致，时间/序号无法确定先后",
                        )
                        else -> AppLogEventRelation(
                            "SAME_OPERATION_CONTEXT",
                            "sessionId 和 operationId 均一致；属于同一处理任务上下文",
                        )
                    }
                }
                selected.issueKey.isNotBlank() && event.issueKey == selected.issueKey ->
                    AppLogEventRelation(
                        "SAME_ISSUE_SIGNATURE",
                        "issueKey 相同，表示相同故障签名；不单独证明是同一次故障实例",
                    )
                selected.sessionId.isNotBlank() && event.sessionId == selected.sessionId ->
                    AppLogEventRelation(
                        "SAME_SESSION_CONTEXT",
                        "仅进程 sessionId 相同，没有共同 operationId；只能作为附近上下文",
                    )
                else -> AppLogEventRelation(
                    "NO_DIRECT_LINK",
                    "没有匹配的恢复编号、故障编号或同一处理任务标识",
                )
            }
            eventKey(event) to relation
        }
    }

    fun operationRoles(records: List<AppLogRecord>): Map<String, AppLogEventRelation> {
        val result = linkedMapOf<String, AppLogEventRelation>()
        records.groupBy(::operationKey).forEach { (key, events) ->
            if (key == null) return@forEach
            val ordered = events.sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
            val first = ordered.firstOrNull(::isIssue)
            ordered.forEach { event ->
                val relation = when {
                    first == null -> AppLogEventRelation(
                        "SAME_OPERATION_CONTEXT",
                        "同一 sessionId/operationId 下的处理上下文；当前没有 WARN/ERROR/FATAL 事件",
                    )
                    eventKey(event) == eventKey(first) -> AppLogEventRelation(
                        "FIRST_RECORDED_ANOMALY_CANDIDATE",
                        "同一 sessionId/operationId 下时间最早的异常记录；不等同于已证实根因",
                    )
                    isIssue(event) -> AppLogEventRelation(
                        "LATER_ANOMALY_SAME_OPERATION",
                        "同一 sessionId/operationId 下更晚出现的异常；需验证是连锁错误还是独立问题",
                    )
                    else -> AppLogEventRelation(
                        "SAME_OPERATION_CONTEXT",
                        "同一 sessionId/operationId 下的普通上下文事件",
                    )
                }
                result[eventKey(event)] = relation
            }
        }
        return result
    }

    fun operationFailureSummaries(records: List<AppLogRecord>): List<AppLogOperationFailureSummary> =
        records.groupBy(::operationKey).mapNotNull { (key, events) ->
            if (key == null) return@mapNotNull null
            val ordered = events.sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
            val anomalies = ordered.filter(::isIssue)
            val first = anomalies.firstOrNull() ?: return@mapNotNull null
            val issueKeys = anomalies.map { it.issueKey }.filter { it.isNotBlank() }.toSet()
            val recoveryCount = if (issueKeys.isEmpty()) 0 else records.count {
                it.resolvesIssueKey.isNotBlank() && it.resolvesIssueKey in issueKeys
            }
            AppLogOperationFailureSummary(
                sessionId = first.sessionId,
                operationId = first.operationId,
                firstAnomalyEventId = first.eventId.ifBlank {
                    "legacy:${first.timestampMs}:${first.sequence}"
                },
                firstAnomalyTimestampMs = first.timestampMs,
                anomalyCount = anomalies.size,
                laterAnomalyCount = (anomalies.size - 1).coerceAtLeast(0),
                recoveryCount = recoveryCount,
            )
        }.sortedByDescending { it.firstAnomalyTimestampMs }
}
