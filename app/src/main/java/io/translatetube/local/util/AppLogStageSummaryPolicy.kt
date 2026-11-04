package io.translatetube.local.util

internal data class AppLogStageSummary(
    val sessionId: String,
    val operationId: String,
    val stage: String,
    val eventCount: Int,
    val warningCount: Int,
    val errorCount: Int,
    val fatalCount: Int,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    val latestProgress: String,
    val transcriptionEngine: String,
    val translationEngine: String,
    val dubbingEngine: String,
    val latestSubtitleCount: String,
    val latestTranslatedCount: String,
    val latestPreparedDubbingCount: String,
)

internal object AppLogStageSummaryPolicy {
    fun summarize(records: List<AppLogRecord>): List<AppLogStageSummary> =
        records.mapNotNull { record ->
            val stage = record.diagnosticContext["当前处理阶段"]
                ?.takeIf { it.isNotBlank() }
                ?: record.diagnosticContext["失败阶段"]?.takeIf { it.isNotBlank() }
                ?: record.diagnosticContext["故障阶段"]?.takeIf { it.isNotBlank() }
            val operationId = record.operationId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sessionId = record.sessionId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (stage == null) return@mapNotNull null
            Triple(record, "$sessionId|$operationId", stage)
        }.groupBy { it.second + "|" + it.third }
            .map { (_, entries) ->
                val events = entries.map { it.first }
                    .sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
                val latest = events.last()
                AppLogStageSummary(
                    sessionId = latest.sessionId,
                    operationId = latest.operationId,
                    stage = latest.diagnosticContext["当前处理阶段"]
                        ?.takeIf { it.isNotBlank() }
                        ?: latest.diagnosticContext["失败阶段"]
                        ?: latest.diagnosticContext["故障阶段"].orEmpty(),
                    eventCount = events.size,
                    warningCount = events.count { it.level == "WARN" },
                    errorCount = events.count { it.level == "ERROR" },
                    fatalCount = events.count { it.level == "FATAL" },
                    firstTimestampMs = events.first().timestampMs,
                    lastTimestampMs = latest.timestampMs,
                    latestProgress = latest.diagnosticContext["当前处理步骤"].orEmpty(),
                    transcriptionEngine = latest.diagnosticContext["转写引擎"].orEmpty(),
                    translationEngine = latest.diagnosticContext["翻译引擎"].orEmpty(),
                    dubbingEngine = latest.diagnosticContext["配音引擎"].orEmpty(),
                    latestSubtitleCount = latest.diagnosticContext["当前字幕行数"]
                        ?: latest.diagnosticContext["字幕总行数"].orEmpty(),
                    latestTranslatedCount = latest.diagnosticContext["已获得有效译文行数"].orEmpty(),
                    latestPreparedDubbingCount = latest.diagnosticContext["已准备配音行数"].orEmpty(),
                )
            }
            .sortedWith(compareBy<AppLogStageSummary> { it.sessionId }
                .thenBy { it.operationId }
                .thenBy { it.firstTimestampMs })
}
