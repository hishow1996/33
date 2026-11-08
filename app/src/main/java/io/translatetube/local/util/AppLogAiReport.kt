package io.translatetube.local.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Creates a machine-readable, app-wide diagnostic report for AI-assisted debugging.
 * The report contains a triage summary and the complete retained event timeline.
 */
internal object AppLogAiReport {
    fun render(
        records: List<AppLogRecord>,
        exportedAt: String,
        appVersion: String,
        device: String,
        androidVersion: String,
        writerHealth: AppLogWriterHealth,
    ): String {
        val ordered = records.sortedWith(
            compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence },
        )
        val fatal = ordered.count { it.level == "FATAL" }
        val errors = ordered.count { it.level == "ERROR" }
        val warnings = ordered.count { it.level == "WARN" }
        val issueRecords = ordered.filter {
            it.issueKey.isNotBlank() && it.level in setOf("WARN", "ERROR", "FATAL")
        }
        val unresolved = issueRecords.filter { it.recoveredAtMs == null }
            .map { it.issueKey }.distinct()
        val recovered = issueRecords.filter { it.recoveredAtMs != null }
            .map { it.issueKey }.distinct()
        val sessions = ordered.map { it.sessionId }.filter { it.isNotBlank() }.distinct()
        val categories = ordered.groupingBy {
            it.category.ifBlank { AppLogTitleResolver.featureLabel(it.tag, it.message) }
        }.eachCount().entries.sortedByDescending { it.value }.take(20)
        val priorityEvents = ordered.filter {
            it.level == "FATAL" || it.level == "ERROR" || it.level == "WARN"
        }.takeLast(80).asReversed()
        val operationRoles = AppLogEventRelationPolicy.operationRoles(ordered)
        val operationSummaries = AppLogEventRelationPolicy.operationFailureSummaries(ordered).take(80)
        val stageSummaries = AppLogStageSummaryPolicy.summarize(ordered).take(120)

        return buildString {
            append("# 大象翻译 AI 修复诊断报告\n")
            append("report_schema: elephant-app-log/v9\n")
            append("导出时间：").append(exportedAt).append('\n')
            append("应用版本：").append(AppLogStore.redactForExport(appVersion)).append('\n')
            append("设备：").append(AppLogStore.redactForExport(device)).append('\n')
            append("Android：").append(AppLogStore.redactForExport(androidVersion)).append('\n')
            append("日志记录数：").append(ordered.size).append('\n')
            append("导出范围：本报告读取本地保留日志中最近最多 800 条；时间线完整覆盖本次读取的记录。更早记录可能已按保留策略清理或超出读取上限。\n")
            append("时间范围：")
                .append(ordered.firstOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append(" 至 ")
                .append(ordered.lastOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append('\n')
            append("进程会话数：").append(sessions.size).append('\n')
            append("进程会话 ID：").append(sessions.joinToString(", ").ifBlank { "旧版日志未记录" }).append('\n')

            append("\n## 1. 日志概览\n")
            append("- FATAL：").append(fatal).append('\n')
            append("- ERROR：").append(errors).append('\n')
            append("- WARN：").append(warnings).append('\n')
            append("- INFO/其他：").append(ordered.size - fatal - errors - warnings).append('\n')
            val coverageRows = ordered.filter { it.level in setOf("WARN", "ERROR", "FATAL") }
                .map(AppLogDiagnosticCoveragePolicy::analyze)
            val averageCoverage = coverageRows.takeIf { it.isNotEmpty() }
                ?.map { it.completenessPercent }?.average()?.toInt()
            val incompleteCoverage = coverageRows.count { it.missingEvidence.isNotEmpty() }
            append("- 异常日志诊断证据覆盖率：")
                .append(averageCoverage?.let { "$it%" } ?: "无异常日志")
                .append("；需要补充证据的异常数：").append(incompleteCoverage)
                .append("/").append(coverageRows.size).append('\n')
            append("- 尚未确认恢复的故障编号：").append(unresolved.size).append('\n')
            append("- 已有明确恢复记录的故障编号：").append(recovered.size).append('\n')
            append("- 日志写入器当前状态：").append(if (writerHealth.isHealthy) "正常" else "异常").append('\n')
            append("- 日志写入失败累计次数：").append(writerHealth.failedWriteCount).append('\n')
            append("- 近期操作轨迹关联方式：优先按 operationId 隔离；缺失时仅使用同线程最近 2 分钟的事件。\n")
            if (!writerHealth.isHealthy) {
                append("- 最近一次写入失败：")
                    .append(AppLogStore.redactForExport(writerHealth.lastFailureDescription))
                    .append('\n')
            }
            if (categories.isNotEmpty()) {
                append("\n模块/功能分布：\n")
                categories.forEach { entry ->
                    append("- ").append(AppLogStore.redactForExport(entry.key))
                        .append("：").append(entry.value).append('\n')
                }
            }

            append("\n## 2. 任务故障关系候选（仅对具备明确 operationId 的任务分组）\n")
            if (operationSummaries.isEmpty()) {
                append("当前读取范围内没有具备完整 sessionId/operationId 且包含异常事件的任务组；不能据此判断故障是否属于同一轮处理。\n")
            } else {
                operationSummaries.forEachIndexed { index, summary ->
                    append(index + 1).append(". sessionId=").append(summary.sessionId)
                        .append("；operationId=").append(summary.operationId)
                        .append("；最早异常候选事件=").append(summary.firstAnomalyEventId)
                        .append("；异常事件数=").append(summary.anomalyCount)
                        .append("；后续异常数=").append(summary.laterAnomalyCount)
                        .append("；按 issueKey 匹配的恢复记录数=").append(summary.recoveryCount)
                        .append('\n')
                }
                append("注意：最早异常仅代表当前保留日志中时间最早的异常候选，不等于真实根因；后续异常可能是连锁错误，也可能是并行/独立故障。\n")
            }

            append("\n## 3. 五阶段诊断摘要（按处理任务和阶段归组）\n")
            if (stageSummaries.isEmpty()) {
                append("当前报告没有足够的 operationId 和阶段快照来生成五阶段摘要；旧版日志可能没有记录实时阶段字段。\n")
            } else {
                stageSummaries.forEachIndexed { index, stage ->
                    append(index + 1).append(". 阶段=").append(stage.stage)
                        .append("；operationId=").append(stage.operationId)
                        .append("；事件数=").append(stage.eventCount)
                        .append("；WARN/ERROR/FATAL=").append(stage.warningCount).append("/")
                        .append(stage.errorCount).append("/").append(stage.fatalCount)
                        .append("；时间=").append(AppLogStore.formatTime(stage.firstTimestampMs))
                        .append(" 至 ").append(AppLogStore.formatTime(stage.lastTimestampMs))
                        .append('\n')
                    if (stage.latestProgress.isNotBlank()) append("   最近步骤：").append(AppLogStore.redactForExport(stage.latestProgress)).append('\n')
                    append("   引擎：转写=").append(stage.transcriptionEngine.ifBlank { "未记录" })
                        .append("；翻译=").append(stage.translationEngine.ifBlank { "未记录" })
                        .append("；配音=").append(stage.dubbingEngine.ifBlank { "未记录" }).append('\n')
                    append("   数量：字幕=").append(stage.latestSubtitleCount.ifBlank { "未记录" })
                        .append("；有效译文=").append(stage.latestTranslatedCount.ifBlank { "未记录" })
                        .append("；已准备配音=").append(stage.latestPreparedDubbingCount.ifBlank { "未记录" }).append('\n')
                }
            }

            append("\n## 4. 优先检查的故障事件（最多 80 条，按最新优先）\n")
            if (priorityEvents.isEmpty()) {
                append("当前保留范围内没有 WARN/ERROR/FATAL 事件。\n")
            } else {
                priorityEvents.forEachIndexed { index, event ->
                    val diagnosis = AppLogDiagnostics.analyze(event)
                    val coverage = AppLogDiagnosticCoveragePolicy.analyze(event)
                    append(index + 1).append(". ")
                        .append(AppLogStore.formatTime(event.timestampMs))
                        .append(" [").append(event.level).append("] ")
                        .append(AppLogStore.redactForExport(AppLogTitleResolver.title(event.tag, event.message)))
                        .append("；事件 ID=").append(event.eventId.ifBlank { "legacy-" + event.timestampMs })
                        .append("；模块=").append(AppLogStore.redactForExport(event.tag))
                        .append("；异常=").append(event.errorClass.ifBlank { "未记录" })
                        .append("；根因=").append(event.rootCauseMessage.ifBlank { "未提取" })
                        .append("；首个应用栈帧=").append(event.firstAppStackFrame.ifBlank { "未记录" })
                        .append("；日志调用位置=").append(AppLogStore.redactForExport(event.diagnosticContext["日志调用位置"].orEmpty().ifBlank { "未记录/旧版日志" }))
                        .append("；异常对象采集状态=").append(AppLogStore.redactForExport(event.diagnosticContext["异常对象采集状态"].orEmpty().ifBlank { "旧版日志未记录" }))
                        .append("；AI 分类=").append(diagnosis.category)
                        .append("；置信度=").append(diagnosis.confidence)
                        .append("；AI 判断=").append(AppLogStore.redactForExport(diagnosis.finding))
                        .append("；证据=").append(AppLogStore.redactForExport(diagnosis.evidence))
                        .append("；建议=").append(diagnosis.nextSteps.joinToString(" | ") { AppLogStore.redactForExport(it) })
                        .append("；证据覆盖率=").append(coverage.completenessPercent).append('%')
                        .append("；缺失证据=").append(coverage.missingEvidence.joinToString("、").ifBlank { "当前清单未发现缺项" })
                        .append("；补采建议=").append(coverage.collectionAdvice.joinToString(" | ").ifBlank { "结合源码继续验证" })
                        .append("；HTTP=").append(event.httpStatusCode.takeIf { it > 0 } ?: "未知")
                        .append("；故障编号=").append(
                            event.issueKey.ifBlank {
                                // Export-only fallback for older/in-memory records that predate
                                // stable issue keys. The sequence is process-local and is not
                                // written back to persisted records.
                                if (event.sequence > 0L) "failure-${event.sequence}" else "无"
                            },
                        )
                        .append("；状态=")
                        .append(
                            when {
                                event.issueKey.isBlank() -> "未建立故障关联"
                                event.recoveredAtMs != null -> "已恢复"
                                else -> "尚无恢复成功记录"
                            },
                        )
                        .append('\n')
                }
                val totalPriority = fatal + errors + warnings
                if (totalPriority > priorityEvents.size) {
                    append("另有 ").append(totalPriority - priorityEvents.size)
                        .append(" 条警告/错误已包含在完整时间线中。\n")
                }
            }

            append("\n## 5. 完整事件时间线（JSONL，按时间升序）\n")
            append("每行是一个独立事件；details 包含事件上下文、近期操作轨迹、环境快照及异常堆栈。\n")
            ordered.forEachIndexed { index, event ->
                val category = event.category.ifBlank {
                    AppLogTitleResolver.featureLabel(event.tag, event.message)
                }
                val json = JSONObject()
                    .put("schemaVersion", event.schemaVersion)
                    .put("reportSchemaVersion", 9)
                    .put("index", index + 1)
                    .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs + "-" + (index + 1) })
                    .put("sessionId", event.sessionId.ifBlank { "legacy/unknown" })
                    .put("sequence", event.sequence)
                    .put("timestampMs", event.timestampMs)
                    .put("timestampLocal", AppLogStore.formatTime(event.timestampMs))
                    .put("elapsedRealtimeMs", event.elapsedRealtimeMs)
                    .put("level", AppLogStore.redactForExport(event.level))
                    .put("category", AppLogStore.redactForExport(category))
                    .put("module", AppLogStore.redactForExport(event.tag))
                    .put("message", AppLogStore.redactForExport(event.message))
                    .put(
                        "operationId",
                        AppLogStore.redactForExport(
                            event.operationId.ifBlank { AppLogStore.translationDiagnosticId(event).orEmpty() },
                        ),
                    )
                    .put("issueKey", event.issueKey)
                    .put("resolvesIssueKey", event.resolvesIssueKey)
                    .put(
                        "operationFailureRole",
                        operationRoles[AppLogEventRelationPolicy.eventKey(event)]?.role ?: "NO_OPERATION_LINK",
                    )
                    .put(
                        "operationFailureEvidence",
                        AppLogStore.redactForExport(
                            operationRoles[AppLogEventRelationPolicy.eventKey(event)]?.evidence
                                ?: "事件没有可用的共同 sessionId/operationId",
                        ),
                    )
                    .put("recoveredAtMs", event.recoveredAtMs ?: 0L)
                    .put("errorClass", AppLogStore.redactForExport(event.errorClass))
                    .put("rootCauseMessage", AppLogStore.redactForExport(event.rootCauseMessage))
                    .put("firstAppStackFrame", AppLogStore.redactForExport(event.firstAppStackFrame))
                    .put(
                        "logCallSiteStatus",
                        AppLogStore.redactForExport(event.diagnosticContext["日志调用位置采集状态"].orEmpty().ifBlank { "旧版日志未记录" }),
                    )
                    .put(
                        "logCallSite",
                        AppLogStore.redactForExport(event.diagnosticContext["日志调用位置"].orEmpty().ifBlank { "旧版日志未记录" }),
                    )
                    .put(
                        "logCallChain",
                        AppLogStore.redactForExport(event.diagnosticContext["日志调用链（最多8帧）"].orEmpty().ifBlank { "旧版日志未记录" }),
                    )
                    .put(
                        "exceptionCaptureStatus",
                        AppLogStore.redactForExport(event.diagnosticContext["异常对象采集状态"].orEmpty().ifBlank { "旧版日志未记录" }),
                    )
                    .put(
                        "exceptionSnapshot",
                        event.exceptionSnapshot?.let { AppLogStore.exceptionSnapshotForExport(it) } ?: JSONObject.NULL,
                    )
                    .put("httpStatusCode", event.httpStatusCode)
                    .put("recentEventTrailScope", AppLogStore.redactForExport(
                        event.diagnosticContext["近期操作轨迹关联方式"].orEmpty(),
                    ))
                    .put("diagnosticContextBudget", diagnosticContextBudgetJson(event))
                    .put("processingStage", event.diagnosticContext["当前处理阶段"].orEmpty())
                    .put("processingUiPhase", event.diagnosticContext["当前 UI 阶段"].orEmpty())
                    .put("processingProgress", AppLogStore.redactForExport(event.diagnosticContext["当前处理步骤"].orEmpty()))
                    .put("processingFlow", event.diagnosticContext["处理流程"].orEmpty())
                    .put(
                        "diagnosticContext",
                        JSONObject().apply {
                            event.diagnosticContext.toSortedMap().forEach { (key, value) ->
                                put(
                                    AppLogStore.redactForExport(key),
                                    AppLogStore.redactForExport(value),
                                )
                            }
                        },
                    )
                    .put("processId", event.processId)
                    .put("threadId", event.threadId)
                    .put("thread", AppLogStore.redactForExport(event.thread))
                    .put("diagnosticCoverage", diagnosticCoverageJson(event))
                    .put("aiDiagnosis", aiDiagnosisJson(event))
                    .put("details", AppLogStore.redactForExport(event.details))
                append(json.toString()).append('\n')
            }
            if (ordered.isEmpty()) append("（当前没有可导出的日志记录）\n")

            append("\n## 6. AI 修复要求\n")
            append("1. 先按 sessionId + operationId 分组，不要把不同进程会话混为一次任务；再查看五阶段诊断摘要。operationFailureRole=FIRST_RECORDED_ANOMALY_CANDIDATE 仅代表最早已记录异常，不能直接认定为真实根因。\n")
            append("2. 对同一任务后续异常逐条比较异常树、根因消息、首个应用栈帧和阶段字段；明确区分连锁错误、重复记录、并行故障及证据不足。\n")
            append("3. 不得仅凭时间先后断言因果；区分日志已证实的事实、合理假设和缺失证据。\n")
            append("4. 优先读取 processingStage、processingProgress、processingFlow 和 diagnosticContext；核对该异常发生时的阶段、子步骤、引擎及字幕数量。空字段表示当时未采集，不得猜测。\n")
            append("5. 优先读取 rootCauseMessage、firstAppStackFrame、exceptionSnapshot、httpStatusCode 和 operationId 等结构化字段；exceptionSnapshot 保留每层异常自己的堆栈及 Suppressed。若任一节点 truncated=true，必须注明异常树/栈帧已截断。\n")
            append("6. 检查最深层 cause、Suppressed 异常，以及第一个应用自身包名的栈帧；给出文件、类、方法和行号。\n")
            append("7. 网络问题同时分析 HTTP 状态码、服务商错误码、requestId、Content-Type、响应长度、重试和解析器结果；不要把所有失败归为网络问题。\n")
            append("8. 对 YouTube、网页视频和本地视频分别核对同一阶段的实现路径，并指出需要同步修复的入口。\n")
            append("9. 提出最小且可验证的修复，说明文件、方法、风险和回归测试；不得用静默吞错、空结果或原声兜底掩盖失败。\n")
            append("10. 先查看每条异常的 diagnosticCoverage，按模块缺失清单定位真实失败分支补采证据；覆盖率只衡量日志采集情况，不代表根因判断准确率。\n")
            append("11. 缺失字段必须明确标记为未采集，并定位源码补采；不得根据相邻事件或默认值推测实际状态。\n")
            append("12. 注意导出范围与旧版记录字段缺失；空字段表示当时未采集，不得推断为数值零或功能未执行。\n")
            append("13. 检查 recentEventTrailScope 和 diagnosticContextBudget；轨迹范围为同线程时，不能推断它与其他线程/任务相关；字段省略或截断时，先注明证据不完整，再定位补采点。\n")
            append("14. 对没有 Throwable 的失败日志，优先用 logCallSite/logCallChain 定位记录失败状态的源码分支；它只代表日志调用位置，不代表异常真正抛出位置。若 exceptionCaptureStatus 表明未提供 Throwable，明确写出堆栈证据缺口，并检查该分支是否应记录具体异常对象、返回值和失败原因。\n")
            append("15. 对 INFO/WARN/ERROR/FATAL 事件都要检查事件元数据、调用位置和上下文；普通 INFO 是任务过程证据，不能因其级别较低而忽略。")
            append("\n隐私说明：导出前再次执行凭据脱敏；分享前仍应检查字幕片段、文件名和业务内容是否包含个人信息。\n")
        }
    }

    /** Compact all-log summary for clipboard sharing; full file export keeps render(). */
    fun renderCompact(
        records: List<AppLogRecord>, exportedAt: String, appVersion: String,
        device: String, androidVersion: String, writerHealth: AppLogWriterHealth,
    ): String {
        val ordered=records.sortedWith(compareBy<AppLogRecord>{it.timestampMs}.thenBy{it.sequence})
        val issues=ordered.filter{it.level in setOf("FATAL","ERROR","WARN")}
            .sortedWith(compareBy<AppLogRecord>{when(it.level){"FATAL"->0;"ERROR"->1;else->2}}
                .thenByDescending{it.timestampMs}.thenByDescending{it.sequence})
        val ordinary=ordered.filterNot{it.level in setOf("FATAL","ERROR","WARN")}.sortedByDescending{it.timestampMs}
        val selected=(issues+ordinary).distinctBy{AppLogEventRelationPolicy.eventKey(it)}.take(5)
            .sortedWith(compareBy<AppLogRecord>{it.timestampMs}.thenBy{it.sequence})
        val result=buildString{
            append("# 大象翻译 AI 日志摘要\nreport_schema: elephant-app-log/chat-v1\n")
            append("应用 ").append(clipForAi(AppLogStore.redactForExport(appVersion),72))
                .append(" | ").append(clipForAi(AppLogStore.redactForExport(device),64))
                .append(" | Android ").append(clipForAi(androidVersion,32)).append(" | ").append(exportedAt).append('\n')
            append("日志 ").append(ordered.size).append(" 条；FATAL/ERROR/WARN ")
                .append(issues.count{it.level=="FATAL"}).append("/")
                .append(issues.count{it.level=="ERROR"}).append("/")
                .append(issues.count{it.level=="WARN"}).append('\n')
            if(!writerHealth.isHealthy) append("日志写入异常：")
                .append(clipForAi(AppLogStore.redactForExport(writerHealth.lastFailureDescription),120)).append('\n')
            append("关键事件（最多 5 条）：\n")
            if(selected.isEmpty()) append("无日志记录\n")
            selected.forEach{event->
                val issue=event.level in setOf("FATAL","ERROR","WARN")
                append(AppLogStore.formatTime(event.timestampMs)).append(" [").append(event.level).append("] ")
                    .append(clipForAi(AppLogStore.redactForExport(event.tag),36)).append(": ")
                    .append(clipForAi(AppLogStore.redactForExport(event.message),80))
                if(issue&&event.errorClass.isNotBlank()) append(" | ").append(clipForAi(AppLogStore.redactForExport(event.errorClass),96))
                val root=rootCauseForAi(event)
                if(issue&&root.isNotBlank()) append(" | 根因=").append(clipForAi(AppLogStore.redactForExport(root),120))
                if(issue&&event.firstAppStackFrame.isNotBlank()) append(" | 首帧=").append(clipForAi(AppLogStore.redactForExport(event.firstAppStackFrame),96))
                append('\n')
            }
            if(ordered.size>selected.size) append("另有 ").append(ordered.size-selected.size).append(" 条事件未复制，完整日志请导出文件。\n")
            append("要求：对照提交号和源码定位真实原因，给出最小修复；不得吞异常或伪造成功。\n")
        }
        if(result.length<=MAX_CHAT_REPORT_CHARS)return result
        val suffix="\n[已压缩；完整日志请在日志页导出文件]"
        return clipForAi(result,MAX_CHAT_REPORT_CHARS-suffix.length)+suffix
    }
    private const val MAX_CHAT_REPORT_CHARS=6_000

    private fun compactGlobalEventJson(event: AppLogRecord, relation: String): JSONObject {
        val isIssue = event.level in setOf("FATAL", "ERROR", "WARN")
        val result = JSONObject()
            .put("time", AppLogStore.formatTime(event.timestampMs))
            .put("level", event.level)
            .put("module", clipped(AppLogStore.redactForExport(event.tag), 48))
            .put("message", clipped(AppLogStore.redactForExport(event.message), if (isIssue) 180 else 120))
            .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs })
            .put("relation", relation)
        if (event.sessionId.isNotBlank()) result.put("sessionId", clipped(event.sessionId, 48))
        val operationId = event.operationId.ifBlank { AppLogStore.translationDiagnosticId(event).orEmpty() }
        if (operationId.isNotBlank()) result.put("operationId", clipped(AppLogStore.redactForExport(operationId), 64))
        val stage = event.diagnosticContext["当前处理阶段"].orEmpty()
        val progress = event.diagnosticContext["当前处理步骤"].orEmpty()
        if (stage.isNotBlank()) result.put("stage", clipped(stage, 48))
        if (progress.isNotBlank()) result.put("step", clipped(AppLogStore.redactForExport(progress), 96))
        val engines = incidentContextJson(event.diagnosticContext, maxFields = 3, maxValueChars = 72)
        if (engines.length() > 0) result.put("context", engines)
        if (isIssue) {
            if (event.issueKey.isNotBlank()) result.put("issueKey", event.issueKey)
            if (event.resolvesIssueKey.isNotBlank()) result.put("resolvesIssueKey", event.resolvesIssueKey)
            result.put("errorClass", clipped(AppLogStore.redactForExport(event.errorClass), 96))
            result.put("rootCause", clipped(AppLogStore.redactForExport(event.rootCauseMessage), 160))
            result.put("appFrame", clipped(AppLogStore.redactForExport(event.firstAppStackFrame), 160))
            if (event.httpStatusCode > 0) result.put("http", event.httpStatusCode)
            if (event.exceptionSnapshot != null) {
                result.put("exception", compactIncidentExceptionJson(event.exceptionSnapshot))
            }
            val details = AppLogStore.redactForExport(event.details.trimEnd())
            if (details.isNotBlank()) result.put("details", clipped(details, 180))
        }
        return result
    }

    /** A focused, length-bounded package for repairing the selected event. */
    fun renderIncident(
        records:List<AppLogRecord>,target:AppLogRecord,exportedAt:String,
        appVersion:String,device:String,androidVersion:String,writerHealth:AppLogWriterHealth,
    ):String{
        val ordered=(records+target).distinctBy{AppLogEventRelationPolicy.eventKey(it)}
            .sortedWith(compareBy<AppLogRecord>{it.timestampMs}.thenBy{it.sequence})
        val primary=ordered.firstOrNull{AppLogEventRelationPolicy.eventKey(it)==AppLogEventRelationPolicy.eventKey(target)}?:target
        val diagnosis=AppLogDiagnostics.analyze(primary)
        val diagnosticId=TranslationDiagnosticPackPolicy.idFromRecord(primary)
        val diagnosticCount=diagnosticId?.let{id->ordered.count{TranslationDiagnosticPackPolicy.idFromRecord(it)==id}}?:0
        val primaryKey=AppLogEventRelationPolicy.eventKey(primary)
        val timeline=ordered.filter{AppLogEventRelationPolicy.eventKey(it)!=primaryKey}.sortedBy{kotlin.math.abs(it.timestampMs-primary.timestampMs)}
        val root=rootCauseForAi(primary)
        val errorClass=primary.errorClass.ifBlank{deepestCause(primary.exceptionSnapshot)?.type.orEmpty()}
        val appFrame=primary.firstAppStackFrame.takeIf{it.isNotBlank()}
            ?.let{clipForAi(AppLogStore.redactForExport(it),260)}
            ?:exceptionFrames(primary.exceptionSnapshot).firstOrNull{it.className.startsWith("io.translatetube.")}?.let(::formatFrameForAi).orEmpty()
        val context=incidentContextLines(primary)
        val suppressed=suppressedTypes(primary.exceptionSnapshot)
        val chainTypes=listOfNotNull(primary.exceptionSnapshot?.type?.takeIf{it.isNotBlank()},deepestCause(primary.exceptionSnapshot)?.type?.takeIf{it.isNotBlank()})
            .distinct().map(::simpleErrorType)
        val frames=exceptionFrames(primary.exceptionSnapshot).filterNot(::isFrameworkNoiseFrame)
            .sortedByDescending{it.className.startsWith("io.translatetube.")}
            .map(::formatFrameForAi).filter{it.isNotBlank()&&it!=appFrame}.distinct()
        fun build(count:Int,keepContext:Boolean,frameCount:Int)=buildString{
            append("# 大象翻译故障包 v9\nreport_schema: elephant-app-log/incident-v9\n")
            append("应用 ").append(clipForAi(AppLogStore.redactForExport(appVersion),72))
                .append(" | ").append(clipForAi(AppLogStore.redactForExport(device),64))
                .append(" | Android ").append(clipForAi(androidVersion,32)).append(" | ").append(exportedAt).append('\n')
            append("故障：").append(primary.level).append(' ').append(clipForAi(AppLogStore.redactForExport(errorClass).ifBlank{primary.tag},120))
            if(primary.thread.equals("main",true))append("（主线程）")
            append("\n诊断：").append(clipForAi(diagnosis.category,100)).append("；").append(clipForAi(diagnosis.confidence,32)).append('\n')
            if(diagnosis.finding.isNotBlank())append("结论：").append(clipForAi(AppLogStore.redactForExport(diagnosis.finding),360)).append('\n')
            if(diagnosis.evidence.isNotBlank())append("诊断证据：").append(clipForAi(AppLogStore.redactForExport(diagnosis.evidence),280)).append('\n')
            if(root.isNotBlank())append("根因：").append(clipForAi(AppLogStore.redactForExport(root),560)).append('\n')
            else if(primary.message.isNotBlank())append("故障摘要：").append(clipForAi(AppLogStore.redactForExport(primary.message),120)).append('\n')
            if(chainTypes.size>1)append("异常链：").append(chainTypes.joinToString(" -> ")).append('\n')
            if(appFrame.isNotBlank())append("首个应用栈帧：").append(clipForAi(AppLogStore.redactForExport(appFrame),260)).append('\n')
            if(frameCount>0&&frames.isNotEmpty())append("调用栈：").append(frames.take(frameCount).joinToString(" <- "){clipForAi(AppLogStore.redactForExport(it),112)}).append('\n')
            if(suppressed.isNotEmpty())append("suppressed：").append(suppressed.joinToString(", "){simpleErrorType(it)}).append('\n')
            if(keepContext&&context.isNotEmpty())append("上下文：").append(context.joinToString("；")).append('\n')
            append("关联范围：同一进程会话/operationId；时间先后不代表因果。\n")
            if(count>0&&timeline.isNotEmpty()){
                append("关联事件（").append(minOf(count,timeline.size)).append("/").append(timeline.size).append("）：\n")
                timeline.take(count).sortedWith(compareBy<AppLogRecord>{it.timestampMs}.thenBy{it.sequence}).forEach{event->
                    val issue=event.level in setOf("ERROR","FATAL","WARN")
                    append(AppLogStore.formatTime(event.timestampMs)).append(" ").append(event.level).append(" ")
                        .append(clipForAi(AppLogStore.redactForExport(event.tag),32)).append(" ")
                        .append(clipForAi(AppLogStore.redactForExport(event.message),80))
                    if(issue&&event.errorClass.isNotBlank())append(" | ").append(clipForAi(AppLogStore.redactForExport(event.errorClass),96))
                    append('\n')
                }
            }else if(timeline.isNotEmpty())append("关联事件：").append(timeline.size).append(" 条已省略。\n")
            if(diagnosis.nextSteps.isNotEmpty())append("下一步：")
                .append(diagnosis.nextSteps.take(3).mapIndexed{i,step->(i+1).toString()+") "+clipForAi(AppLogStore.redactForExport(step),170)}.joinToString("；")).append('\n')
            if(diagnosticId!=null)append("翻译专项要求：同诊断 ID ").append(diagnosticId).append("（").append(diagnosticCount).append(" 条事件）核对请求、批次和字幕行。\n")
            append("要求：对照该提交的代码定位真实根因，给出最小修复，不吞异常。\n")
        }
        var count=minOf(5,timeline.size);var keepContext=true;var frameCount=minOf(5,frames.size)
        var output=build(count,keepContext,frameCount);var compressed=false
        while(output.length>MAX_INCIDENT_REPORT_CHARS&&count>0){count--;compressed=true;output=build(count,keepContext,frameCount)}
        if(output.length>MAX_INCIDENT_REPORT_CHARS&&keepContext){keepContext=false;compressed=true;output=build(count,keepContext,frameCount)}
        while(output.length>MAX_INCIDENT_REPORT_CHARS&&frameCount>0){frameCount--;compressed=true;output=build(count,keepContext,frameCount)}
        if(output.length>MAX_INCIDENT_REPORT_CHARS){
            output=buildString{
                append("# 大象翻译故障包 v9\nreport_schema: elephant-app-log/incident-v9\n")
                append("应用 ").append(clipForAi(appVersion,64)).append(" | ").append(clipForAi(device,48)).append(" | ").append(clipForAi(androidVersion,24)).append('\n')
                append("故障：").append(primary.level).append(' ').append(clipForAi(AppLogStore.redactForExport(errorClass),120)).append('\n')
                append("诊断：").append(clipForAi(diagnosis.category,96)).append('\n')
                append("结论：").append(clipForAi(diagnosis.finding,300)).append('\n')
                append("根因：").append(clipForAi(root,420)).append('\n')
                if(appFrame.isNotBlank())append("首个应用栈帧：").append(clipForAi(appFrame,220)).append('\n')
                if(diagnosis.evidence.isNotBlank())append("诊断证据：").append(clipForAi(diagnosis.evidence,220)).append('\n')
                append("要求：对照该提交的代码定位真实根因，给出最小修复，不吞异常。\n")
            };compressed=true
        }
        if(!compressed)return output
        val suffix="\n[已压缩；完整日志请在日志页导出文件]"
        return if(output.length+suffix.length<=MAX_INCIDENT_REPORT_CHARS)output+suffix else output.take(MAX_INCIDENT_REPORT_CHARS-suffix.length)+suffix
    }

    /**
     * Full, single-incident export for the file picker. Unlike renderIncident(), this deliberately
     * has no chat-sized character budget: the selected event's complete stored details, full
     * structured exception snapshot and all retained correlated context are preserved.
     */
    fun renderIncidentFull(
        records: List<AppLogRecord>,
        target: AppLogRecord,
        exportedAt: String,
        appVersion: String,
        device: String,
        androidVersion: String,
        writerHealth: AppLogWriterHealth,
    ): String {
        val ordered = (records + target)
            .distinctBy { AppLogEventRelationPolicy.eventKey(it) }
            .sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
        val primaryKey = AppLogEventRelationPolicy.eventKey(target)
        val primary = ordered.firstOrNull {
            AppLogEventRelationPolicy.eventKey(it) == primaryKey
        } ?: target
        val related = ordered.filter {
            AppLogEventRelationPolicy.eventKey(it) != primaryKey
        }
        val diagnosis = AppLogDiagnostics.analyze(primary)
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(primary)
        val diagnosticId = TranslationDiagnosticPackPolicy.idFromRecord(primary)
        val errorClass = primary.errorClass
            .ifBlank { deepestCause(primary.exceptionSnapshot)?.type.orEmpty() }
            .ifBlank { primary.tag }

        fun fullEventJson(event: AppLogRecord, selected: Boolean): JSONObject =
            JSONObject()
                .put("selectedIncident", selected)
                .put("schemaVersion", event.schemaVersion)
                .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs })
                .put("timestampMs", event.timestampMs)
                .put("timestampLocal", AppLogStore.formatTime(event.timestampMs))
                .put("level", AppLogStore.redactForExport(event.level))
                .put("module", AppLogStore.redactForExport(event.tag))
                .put("category", AppLogStore.redactForExport(event.category))
                .put("message", AppLogStore.redactForExport(event.message))
                .put("sessionId", event.sessionId.ifBlank { "legacy/unknown" })
                .put("sequence", event.sequence)
                .put("operationId", AppLogStore.redactForExport(
                    event.operationId.ifBlank { AppLogStore.translationDiagnosticId(event).orEmpty() },
                ))
                .put("issueKey", event.issueKey)
                .put("resolvesIssueKey", event.resolvesIssueKey)
                .put("errorClass", AppLogStore.redactForExport(event.errorClass))
                .put("rootCauseMessage", AppLogStore.redactForExport(event.rootCauseMessage))
                .put("firstAppStackFrame", AppLogStore.redactForExport(event.firstAppStackFrame))
                .put("httpStatusCode", event.httpStatusCode)
                .put("processId", event.processId)
                .put("threadId", event.threadId)
                .put("thread", AppLogStore.redactForExport(event.thread))
                .put("elapsedRealtimeMs", event.elapsedRealtimeMs)
                .put("diagnosticContext", JSONObject().apply {
                    event.diagnosticContext.toSortedMap().forEach { (key, value) ->
                        put(
                            AppLogStore.redactForExport(key),
                            AppLogStore.redactForExport(value),
                        )
                    }
                })
                .put(
                    "exceptionSnapshot",
                    event.exceptionSnapshot?.let { AppLogStore.exceptionSnapshotForExport(it) }
                        ?: JSONObject.NULL,
                )
                .put("details", AppLogStore.redactForExport(event.details.trimEnd()))

        return buildString {
            append("# 大象翻译 AI 单条完整诊断报告\n")
            append("report_schema: elephant-app-log/incident-full-v1\n")
            append("导出时间：").append(exportedAt).append('\n')
            append("应用版本 / Git SHA：").append(AppLogStore.redactForExport(appVersion)).append('\n')
            append("设备：").append(AppLogStore.redactForExport(device)).append('\n')
            append("Android：").append(AppLogStore.redactForExport(androidVersion)).append('\n')
            append("日志写入器：").append(if (writerHealth.isHealthy) "正常" else "异常")
                .append("；累计写入失败 ").append(writerHealth.failedWriteCount).append(" 次\n")
            if (!writerHealth.isHealthy && writerHealth.lastFailureDescription.isNotBlank()) {
                append("最近写入失败：")
                    .append(AppLogStore.redactForExport(writerHealth.lastFailureDescription))
                    .append('\n')
            }

            append("\n## 1. 本次选中的唯一故障\n")
            append("发生时间：").append(AppLogStore.formatTime(primary.timestampMs)).append('\n')
            append("级别：").append(primary.level).append('\n')
            append("模块：").append(AppLogStore.redactForExport(primary.tag)).append('\n')
            append("故障标题：")
                .append(AppLogStore.redactForExport(AppLogTitleResolver.title(primary.tag, primary.message)))
                .append('\n')
            append("异常类型：").append(AppLogStore.redactForExport(errorClass)).append('\n')
            append("事件 ID：").append(AppLogStore.redactForExport(primary.eventId.ifBlank { "旧版日志未记录" })).append('\n')
            append("故障编号：").append(primary.issueKey.ifBlank { "未记录" }).append('\n')
            append("进程会话：").append(AppLogStore.redactForExport(primary.sessionId.ifBlank { "旧版日志未记录" })).append('\n')
            append("操作关联 ID：")
                .append(AppLogStore.redactForExport(primary.operationId.ifBlank { diagnosticId ?: "未记录" }))
                .append('\n')
            append("线程：").append(AppLogStore.redactForExport(primary.thread.ifBlank { "未记录" })).append('\n')
            append("HTTP 状态码：").append(primary.httpStatusCode.takeIf { it > 0 } ?: "未记录").append('\n')
            append("根因消息：")
                .append(AppLogStore.redactForExport(primary.rootCauseMessage.ifBlank { "未单独提取" }))
                .append('\n')
            append("首个应用栈帧：")
                .append(AppLogStore.redactForExport(primary.firstAppStackFrame.ifBlank { "未单独提取" }))
                .append('\n')

            append("\n## 2. AI 诊断结论\n")
            append("诊断类别：").append(diagnosis.category).append('\n')
            append("置信度：").append(diagnosis.confidence).append('\n')
            append("诊断结论：").append(AppLogStore.redactForExport(diagnosis.finding)).append('\n')
            append("诊断证据：").append(AppLogStore.redactForExport(diagnosis.evidence)).append('\n')
            if (diagnosis.nextSteps.isNotEmpty()) {
                append("建议排查：\n")
                diagnosis.nextSteps.forEachIndexed { index, step ->
                    append(index + 1).append(". ")
                        .append(AppLogStore.redactForExport(step)).append('\n')
                }
            }
            append("证据覆盖率：").append(coverage.completenessPercent).append("%（")
                .append(coverage.collectedEvidenceCount).append("/")
                .append(coverage.requiredEvidenceCount).append(" 项）\n")
            if (coverage.missingEvidence.isNotEmpty()) {
                append("缺失证据：\n")
                coverage.missingEvidence.forEach { append("- ").append(it).append('\n') }
            }
            if (coverage.collectionAdvice.isNotEmpty()) {
                append("建议补采：\n")
                coverage.collectionAdvice.forEachIndexed { index, advice ->
                    append(index + 1).append(". ").append(advice).append('\n')
                }
            }

            append("\n## 3. 选中错误的全部结构化诊断字段\n")
            if (primary.diagnosticContext.isEmpty()) {
                append("当时没有保存结构化诊断字段。\n")
            } else {
                primary.diagnosticContext.toSortedMap().forEach { (key, value) ->
                    append("- ").append(AppLogStore.redactForExport(key)).append("：")
                        .append(AppLogStore.redactForExport(value)).append('\n')
                }
            }

            append("\n## 4. 完整结构化异常树\n")
            if (primary.exceptionSnapshot != null) {
                append(AppLogStore.exceptionSnapshotForExport(primary.exceptionSnapshot).toString(2))
                    .append('\n')
            } else {
                append("没有保存结构化 Throwable 快照；请以完整日志详情中的异常链/堆栈为准。\n")
            }

            append("\n## 5. 选中错误的完整原始诊断详情与堆栈\n")
            if (primary.details.isNotBlank()) {
                append(AppLogStore.redactForExport(primary.details.trimEnd())).append('\n')
            } else {
                append("该记录没有保存 details 字段。\n")
            }
            append("\n## 6. 同一任务的关联上下文（不是其他被选中的故障）\n")
            append("主故障只包含上文选中的一条 ERROR/FATAL 记录。以下事件仅在同一 sessionId + operationId，或符合旧版同线程近邻规则时作为上下文保留；不代表它们都是本次导出的主故障。\n")
            append("关联事件数：").append(related.size).append("（最多保留 80 条相关事件）\n")
            if (related.isEmpty()) {
                append("没有找到可确认关联的其他事件。\n")
            } else {
                related.forEachIndexed { index, event ->
                    append("\n### 关联事件 ").append(index + 1).append(" · ")
                        .append(AppLogStore.formatTime(event.timestampMs)).append(" · ")
                        .append(event.level).append(" · ")
                        .append(AppLogStore.redactForExport(event.tag)).append("\n")
                    append(fullEventJson(event, selected = false).toString(2)).append('\n')
                }
            }

            append("\n## 7. 主故障机器可读完整记录（JSON）\n")
            append(fullEventJson(primary, selected = true).toString(2)).append('\n')
            append("\n## 8. AI 分析约束\n")
            append("1. 本报告的唯一主故障是第 1 节选中的事件；关联事件只能用于还原前后过程，不得擅自替换主故障。\n")
            append("2. 完整 details、异常堆栈和结构化异常树均按本地已保存内容导出；若源日志本身标记 truncated 或存在采集上限，必须明确指出，不能声称源数据原本完整。\n")
            append("3. 区分日志证实的事实、合理假设和缺失证据；时间相邻不等于存在因果关系。\n")
            append("4. 结合首个应用栈帧、最深层 cause、suppressed、HTTP 状态和结构化上下文定位实际失败分支。\n")
            append("5. 给出涉及的文件/方法、最小修复、兼容性风险和回归验证点；不得用吞异常、空结果或伪造成功掩盖故障。\n")
            append("\n隐私说明：凭据和敏感认证信息已执行导出脱敏；分享前仍应检查字幕内容、文件路径及业务数据。\n")
        }
    }

    private const val MAX_INCIDENT_REPORT_CHARS=4_000

    private fun incidentPrimaryEventJson(
        event: AppLogRecord,
        relation: AppLogEventRelation?,
    ): JSONObject {
        val details = AppLogStore.redactForExport(event.details.trimEnd())
        val clippedDetails = clipped(details, 900)
        return JSONObject()
            .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs })
            .put("timestamp", AppLogStore.formatTime(event.timestampMs))
            .put("level", AppLogStore.redactForExport(event.level))
            .put("category", AppLogStore.redactForExport(event.category))
            .put("module", AppLogStore.redactForExport(event.tag))
            .put("message", clipped(AppLogStore.redactForExport(event.message), 240))
            .put("sessionId", event.sessionId.ifBlank { "legacy/unknown" })
            .put("sequence", event.sequence)
            .put("operationId", clipped(AppLogStore.redactForExport(event.operationId), 96))
            .put("issueKey", event.issueKey)
            .put("resolvesIssueKey", event.resolvesIssueKey)
            .put("errorClass", clipped(AppLogStore.redactForExport(event.errorClass), 160))
            .put("rootCauseMessage", clipped(AppLogStore.redactForExport(event.rootCauseMessage), 320))
            .put("firstAppStackFrame", clipped(AppLogStore.redactForExport(event.firstAppStackFrame), 320))
            .put("logCallSiteStatus", clipped(AppLogStore.redactForExport(event.diagnosticContext["日志调用位置采集状态"].orEmpty()), 80))
            .put("logCallSite", clipped(AppLogStore.redactForExport(event.diagnosticContext["日志调用位置"].orEmpty()), 220))
            .put("logCallChain", clipped(AppLogStore.redactForExport(event.diagnosticContext["日志调用链（最多8帧）"].orEmpty()), 520))
            .put("exceptionCaptureStatus", clipped(AppLogStore.redactForExport(event.diagnosticContext["异常对象采集状态"].orEmpty()), 100))
            .put("exceptionSnapshot", event.exceptionSnapshot?.let { compactIncidentExceptionJson(it) } ?: JSONObject.NULL)
            .put("httpStatusCode", event.httpStatusCode.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("processingStage", clipped(event.diagnosticContext["当前处理阶段"].orEmpty(), 64))
            .put("processingUiPhase", clipped(event.diagnosticContext["当前 UI 阶段"].orEmpty(), 64))
            .put("processingProgress", clipped(AppLogStore.redactForExport(event.diagnosticContext["当前处理步骤"].orEmpty()), 160))
            .put("processingFlow", clipped(event.diagnosticContext["处理流程"].orEmpty(), 64))
            .put("thread", clipped(AppLogStore.redactForExport(event.thread), 80))
            .put("diagnosticContext", incidentContextJson(event.diagnosticContext, maxFields = 12, maxValueChars = 140))
            .put("diagnosticContextBudget", diagnosticContextBudgetJson(event))
            .put("diagnosticCoverage", compactIncidentCoverageJson(event))
            .put("details", clippedDetails)
            .put("detailsTruncated", details.length > clippedDetails.length)
            .put("relationToSelectedEvent", relation?.role ?: "SELECTED_EVENT")
    }

    private fun incidentTimelineEventJson(
        event: AppLogRecord,
        primary: AppLogRecord,
        relation: AppLogEventRelation?,
    ): JSONObject {
        val isIssue = event.level in setOf("FATAL", "ERROR", "WARN")
        val context = incidentContextJson(event.diagnosticContext, maxFields = if (isIssue) 5 else 3, maxValueChars = 100)
        val result = JSONObject()
            .put("sequence", event.sequence)
            .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs })
            .put("time", AppLogStore.formatTime(event.timestampMs))
            .put("level", event.level)
            .put("module", clipped(AppLogStore.redactForExport(event.tag), 48))
            .put("message", clipped(AppLogStore.redactForExport(event.message), if (isIssue) 180 else 120))
            .put("relationToSelectedEvent", relation?.role ?: "NO_DIRECT_LINK")
        if (relation?.evidence?.isNotBlank() == true) {
            result.put("relationEvidence", clipped(AppLogStore.redactForExport(relation.evidence), 110))
        }
        if (event.sessionId.isNotBlank() && event.sessionId != primary.sessionId) {
            result.put("sessionId", event.sessionId)
        }
        if (event.operationId.isNotBlank() && event.operationId != primary.operationId) {
            result.put("operationId", clipped(AppLogStore.redactForExport(event.operationId), 64))
        }
        val stage = event.diagnosticContext["当前处理阶段"].orEmpty()
        val step = event.diagnosticContext["当前处理步骤"].orEmpty()
        if (stage.isNotBlank()) result.put("stage", clipped(stage, 48))
        if (step.isNotBlank()) result.put("step", clipped(AppLogStore.redactForExport(step), 100))
        if (context.length() > 0) result.put("context", context)
        if (isIssue) {
            result.put("errorClass", clipped(AppLogStore.redactForExport(event.errorClass), 100))
            result.put("rootCauseMessage", clipped(AppLogStore.redactForExport(event.rootCauseMessage), 180))
            result.put("firstAppStackFrame", clipped(AppLogStore.redactForExport(event.firstAppStackFrame), 180))
            if (event.httpStatusCode > 0) result.put("httpStatusCode", event.httpStatusCode)
            val details = AppLogStore.redactForExport(event.details.trimEnd())
            if (details.isNotBlank()) result.put("details", clipped(details, 220))
        }
        return result
    }

    private fun incidentContextJson(
        context: Map<String, String>,
        maxFields: Int,
        maxValueChars: Int,
    ): JSONObject {
        val priorityKeys = listOf(
            "视频来源", "视频类型", "当前处理阶段", "当前 UI 阶段", "当前处理步骤",
            "处理流程", "翻译引擎", "转写引擎", "配音引擎", "请求阶段", "HTTP 状态码",
            "当前批次", "批次索引", "字幕行索引", "字幕总行数", "已获得有效译文行数",
            "日志调用位置采集状态", "日志调用位置", "异常对象采集状态",
            "近期操作轨迹关联方式",
        )
        val keys = (priorityKeys.filter { context.containsKey(it) } +
            context.keys.filterNot { it in priorityKeys }.sorted())
            .distinct()
        return JSONObject().apply {
            keys.asSequence()
                .filter { context[it].orEmpty().isNotBlank() }
                .take(maxFields)
                .forEach { key ->
                    put(
                        clipped(AppLogStore.redactForExport(key), 48),
                        clipped(AppLogStore.redactForExport(context[key].orEmpty()), maxValueChars),
                    )
                }
        }
    }

    private fun compactIncidentCoverageJson(event: AppLogRecord): JSONObject {
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(event)
        return JSONObject()
            .put("percent", coverage.completenessPercent)
            .put("collected", coverage.collectedEvidenceCount)
            .put("required", coverage.requiredEvidenceCount)
            .put("missing", JSONArray().apply {
                coverage.missingEvidence.take(4).forEach { put(clipped(AppLogStore.redactForExport(it), 96)) }
            })
            .put("missingTotal", coverage.missingEvidence.size)
    }

    private fun compactIncidentExceptionJson(
        snapshot: AppLogExceptionSnapshot,
        depth: Int = 0,
        nodeBudget: IntArray = intArrayOf(6),
    ): JSONObject {
        if (depth >= 8 || nodeBudget[0] <= 0) {
            return JSONObject().put("truncated", true)
        }
        nodeBudget[0]--
        val frames = JSONArray()
        snapshot.stackFrames.take(2).forEach { frame ->
            frames.put(
                JSONObject()
                    .put("class", clipped(AppLogStore.redactForExport(frame.className), 100))
                    .put("method", clipped(AppLogStore.redactForExport(frame.methodName), 80))
                    .put("file", clipped(AppLogStore.redactForExport(frame.fileName.orEmpty()), 64))
                    .put("line", frame.lineNumber),
            )
        }
        val result = JSONObject()
            .put("type", clipped(AppLogStore.redactForExport(snapshot.type), 140))
            .put("message", clipped(AppLogStore.redactForExport(snapshot.message), 190))
            .put("stackFrames", frames)
            .put("truncated", snapshot.truncated || snapshot.stackFrames.size > 3 || depth >= 7)
        snapshot.cause?.let {
            result.put("cause", compactIncidentExceptionJson(it, depth + 1, nodeBudget))
        }
        if (snapshot.suppressed.isNotEmpty()) {
            result.put("suppressed", JSONArray().apply {
                snapshot.suppressed.take(2).forEach { put(compactIncidentExceptionJson(it, depth + 1, nodeBudget)) }
            })
            if (snapshot.suppressed.size > 2) result.put("suppressedOmitted", snapshot.suppressed.size - 2)
        }
        return result
    }

    fun clipForAi(value:String,maxChars:Int):String{
        if(maxChars<=0)return ""
        var text=value.trim()
        if(text.isEmpty())return ""
        text=Regex("""\(([^()\r\n]{100,})\)""").replace(text){m->
            val args=m.groupValues[1]
            val d=Regex("""L[^;()]+;""").findAll(args).count()
            val c=if(args.contains(','))args.count{it==','}+1 else 0
            val n=maxOf(d,c)
            if(n>=5)"($n params)" else m.value
        }
        text=Regex("""L(?:[A-Za-z0-9_$]+/)+([A-Za-z0-9_$]+);""").replace(text){it.groupValues[1]}
        text=Regex("""(?<![\w$])(?:[A-Za-z_$][\w$]*\.)+[A-Z_$][\w$]*(?![\w$])""").replace(text){it.value.substringAfterLast('.')}
        if(text.length<=maxChars)return text
        if(maxChars==1)return "…"
        val marker=Regex("""(?i)failed to verify:""").find(text)
        if(marker!=null){
            val important=marker.value+text.substring(marker.range.last+1).take((maxChars-marker.value.length-2).coerceAtLeast(0).coerceAtMost(300))
            val head=(maxChars-important.length-3).coerceAtLeast(0)
            return if(head>0)text.take(head)+"..."+important else "…"+important.take(maxChars-1)
        }
        val head=((maxChars-3)/2).coerceAtLeast(1)
        val tail=(maxChars-head-3).coerceAtLeast(0)
        return text.take(head)+"..."+text.takeLast(tail)
    }
    private fun simpleErrorType(value:String)=value.trim().substringBefore(':').substringAfterLast('.').substringAfterLast('/').removePrefix("L").removeSuffix(";")
    private fun deepestCause(snapshot:AppLogExceptionSnapshot?):AppLogExceptionSnapshot?{
        var node=snapshot?:return null
        while(node.cause!=null)node=node.cause
        return node
    }
    private fun rootCauseForAi(event:AppLogRecord):String{
        deepestCause(event.exceptionSnapshot)?.message?.trim()?.takeIf{it.isNotBlank()}?.let{return it}
        event.rootCauseMessage.trim().takeIf{it.isNotBlank()}?.let{return it}
        val details=event.details.replace("\\n","\n")
        val line=Regex("""(?m)^#\d+\s+(.+)$""").findAll(details).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
        if(!line.isNullOrBlank())return line.substringAfter(": ",line)
        return Regex("""(?i)Verifier rejected.*""").find(details)?.value.orEmpty()
    }
    private fun exceptionFrames(snapshot:AppLogExceptionSnapshot?):List<AppLogStackFrame>{
        val result=mutableListOf<AppLogStackFrame>();var node=snapshot;var depth=0
        while(node!=null&&depth<12){result+=node.stackFrames;node=node.cause;depth++}
        return result
    }
    private fun isFrameworkNoiseFrame(frame:AppLogStackFrame)=
        frame.className.startsWith("androidx.compose.runtime.")||
        frame.className.startsWith("kotlinx.coroutines.internal.")||
        frame.className.startsWith("kotlin.coroutines.jvm.internal.")||
        frame.className.startsWith("androidx.lifecycle.")||
        frame.className.startsWith("java.lang.reflect.")||
        frame.className.startsWith("jdk.internal.reflect.")
    private fun formatFrameForAi(frame:AppLogStackFrame):String{
        val klass=frame.className.substringAfterLast('.').substringAfterLast('/')
        val location=frame.fileName?.takeIf{it.isNotBlank()}?.let{if(frame.lineNumber>0)it+":"+frame.lineNumber else it}.orEmpty()
        return if(location.isBlank())klass+"."+frame.methodName else klass+"."+frame.methodName+"("+location+")"
    }
    private fun suppressedTypes(snapshot:AppLogExceptionSnapshot?):List<String>{
        val found=linkedSetOf<String>()
        fun visit(node:AppLogExceptionSnapshot?,depth:Int){
            if(node==null||depth>=12)return
            node.suppressed.forEach{if(it.type.isNotBlank())found+=it.type;visit(it,depth+1)}
            visit(node.cause,depth+1)
        }
        visit(snapshot,0);return found.toList()
    }
    private fun incidentContextLines(event:AppLogRecord):List<String>{
        val keys=listOf("视频来源","当前处理阶段","当前处理步骤","处理流程","翻译引擎","转写引擎","配音引擎","HTTP 状态码","当前批次","批次索引","字幕行索引")
        val values=keys.mapNotNull{key->event.diagnosticContext[key].orEmpty().trim().takeIf{it.isNotBlank()}?.let{key to it}}.toMutableList()
        if(values.none{it.first=="HTTP 状态码"}&&event.httpStatusCode in 100..599)values.add("HTTP 状态码" to event.httpStatusCode.toString())
        return values.distinctBy{it.first}.take(8).map{(key,value)->key+"="+clipForAi(AppLogStore.redactForExport(value),80)}
    }

    private fun clipped(value: String, maxChars: Int): String {
        val normalized = value.trim()
        if (normalized.length <= maxChars) return normalized
        if (maxChars <= 1) return "…"
        return normalized.take(maxChars - 1) + "…"
    }

    private fun diagnosticContextBudgetJson(record: AppLogRecord): Any {
        val context = record.diagnosticContext
        val total = context["日志诊断字段总数"]?.toIntOrNull() ?: return JSONObject.NULL
        return JSONObject()
            .put("totalFields", total)
            .put("retainedFields", context["日志诊断字段已保留数"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("omittedFields", context["日志诊断字段省略数"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("omittedFieldKeys", AppLogStore.redactForExport(context["日志诊断字段省略项（最多16项）"].orEmpty()))
            .put("truncatedFields", context["日志诊断字段截断数"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("truncatedFieldKeys", AppLogStore.redactForExport(context["日志诊断字段截断项（最多16项）"].orEmpty()))
    }

    private fun diagnosticCoverageJson(record: AppLogRecord): JSONObject {
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(record)
        return JSONObject()
            .put("domain", AppLogStore.redactForExport(coverage.domain))
            .put("collectedEvidenceCount", coverage.collectedEvidenceCount)
            .put("requiredEvidenceCount", coverage.requiredEvidenceCount)
            .put("completenessPercent", coverage.completenessPercent)
            .put("missingEvidence", JSONArray().apply {
                coverage.missingEvidence.forEach { put(AppLogStore.redactForExport(it)) }
            })
            .put("collectionAdvice", JSONArray().apply {
                coverage.collectionAdvice.forEach { put(AppLogStore.redactForExport(it)) }
            })
    }

    private fun aiDiagnosisJson(record: AppLogRecord): Any {
        if (record.level !in setOf("WARN", "ERROR", "FATAL")) return JSONObject.NULL
        val diagnosis = AppLogDiagnostics.analyze(record)
        return JSONObject()
            .put("category", AppLogStore.redactForExport(diagnosis.category))
            .put("confidence", AppLogStore.redactForExport(diagnosis.confidence))
            .put("finding", AppLogStore.redactForExport(diagnosis.finding))
            .put("evidence", AppLogStore.redactForExport(diagnosis.evidence))
            .put("nextSteps", JSONArray().apply {
                diagnosis.nextSteps.forEach { put(AppLogStore.redactForExport(it)) }
            })
    }

}
