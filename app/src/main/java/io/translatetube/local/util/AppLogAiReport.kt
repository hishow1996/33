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
            append("- 近期操作轨迹策略：优先按 operationId 隔离；缺失时仅使用同线程最近 2 分钟的事件。\n")
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
                        .append("；故障编号=").append(event.issueKey.ifBlank { "无" })
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
        val issues = ordered.filter { it.level in setOf("FATAL", "ERROR", "WARN") }
        val sessions = ordered.map { it.sessionId }.filter { it.isNotBlank() }.distinct()
        val unresolved = issues.filter { it.issueKey.isNotBlank() && it.recoveredAtMs == null }
            .map { it.issueKey }.distinct().size
        val recovered = issues.filter { it.issueKey.isNotBlank() && it.recoveredAtMs != null }
            .map { it.issueKey }.distinct().size
        val operationSummaries = AppLogEventRelationPolicy.operationFailureSummaries(ordered).take(8)
        val stageSummaries = AppLogStageSummaryPolicy.summarize(ordered).take(8)
        val operationRoles = AppLogEventRelationPolicy.operationRoles(ordered)
        val core = buildString {
            append("# 大象翻译 AI 修复诊断摘要\n")
            append("report_schema: elephant-app-log/chat-v1\n")
            append("导出：").append(exportedAt)
                .append("；版本：").append(clipped(AppLogStore.redactForExport(appVersion), 48))
                .append("；设备：").append(clipped(AppLogStore.redactForExport(device), 64))
                .append("；Android：").append(clipped(AppLogStore.redactForExport(androidVersion), 32)).append('\n')
            append("读取日志：").append(ordered.size)
                .append("；FATAL/ERROR/WARN：").append(fatal).append("/")
                .append(errors).append("/").append(warnings)
                .append("；INFO/其他：").append(ordered.size - fatal - errors - warnings).append('\n')
            append("进程会话：").append(sessions.size)
                .append("；未恢复故障编号：").append(unresolved)
                .append("；已有恢复记录：").append(recovered).append('\n')
            append("时间：")
                .append(ordered.firstOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append(" 至 ")
                .append(ordered.lastOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append('\n')
            append("日志写入：").append(if (writerHealth.isHealthy) "正常" else "异常")
                .append("；累计写入失败：").append(writerHealth.failedWriteCount)
            if (!writerHealth.isHealthy && writerHealth.lastFailureDescription.isNotBlank()) {
                append("；最近失败：").append(clipped(AppLogStore.redactForExport(writerHealth.lastFailureDescription), 140))
            }
            append('\n')

            if (operationSummaries.isNotEmpty()) {
                append("\n## 1. 任务故障摘要\n")
                operationSummaries.forEach { summary ->
                    append("session=").append(clipped(summary.sessionId, 48))
                        .append("；operation=").append(clipped(summary.operationId, 48))
                        .append("；首个异常候选=").append(clipped(summary.firstAnomalyEventId, 48))
                        .append("；异常=").append(summary.anomalyCount)
                        .append("；后续=").append(summary.laterAnomalyCount)
                        .append("；恢复=").append(summary.recoveryCount).append('\n')
                }
                append("首个异常只是当前留存日志中的候选，不等于已证实根因。\n")
            }
            if (stageSummaries.isNotEmpty()) {
                append("\n## 2. 五阶段摘要\n")
                stageSummaries.forEach { stage ->
                    append(stage.stage).append("；事件=").append(stage.eventCount)
                        .append("；WARN/ERROR/FATAL=").append(stage.warningCount).append("/")
                        .append(stage.errorCount).append("/").append(stage.fatalCount)
                    if (stage.latestProgress.isNotBlank()) {
                        append("；最近步骤=").append(clipped(AppLogStore.redactForExport(stage.latestProgress), 100))
                    }
                    append('\n')
                }
            }
            append("\n## 3. AI 分析要求\n")
            append("优先分析异常级事件和最深层 cause；结合 sessionId/operationId、阶段、状态码、根因消息及首个应用栈帧定位真实代码。时间先后不等于因果；区分事实、推测和缺失证据。给出最小修复及 YouTube/网页/本地回归验证。不得吞异常、伪造成功或用原声兜底掩盖失败。\n")
            append("本摘要为复制专用精简版；完整留存事件、详细上下文和异常树请使用“导出 AI 诊断报告”。\n")
        }

        val priority = issues.sortedWith(
            compareBy<AppLogRecord> {
                when (it.level) {
                    "FATAL" -> 0
                    "ERROR" -> 1
                    else -> 2
                }
            }.thenByDescending { it.timestampMs }.thenByDescending { it.sequence },
        )
        val ordinary = ordered.filterNot { it.level in setOf("FATAL", "ERROR", "WARN") }
            .takeLast(12).asReversed()
        val timelineHeader = "\n## 4. 关键事件（按时间升序）\n"
        val timelineBudget = (MAX_CHAT_REPORT_CHARS - core.length - timelineHeader.length - 220)
            .coerceAtLeast(0)
        val selected = mutableListOf<Pair<AppLogRecord, String>>()
        var used = 0
        (priority + ordinary).forEach { event ->
            val role = operationRoles[AppLogEventRelationPolicy.eventKey(event)]?.role ?: "NO_OPERATION_LINK"
            val line = compactGlobalEventJson(event, role).toString()
            val size = line.length + 1
            if (used + size <= timelineBudget) {
                selected += event to line
                used += size
            }
        }
        val selectedKeys = selected.map { AppLogEventRelationPolicy.eventKey(it.first) }.toSet()
        val omitted = ordered.size - selected.size
        val omittedIssues = issues.count { AppLogEventRelationPolicy.eventKey(it) !in selectedKeys }
        val result = buildString {
            append(core)
            append(timelineHeader)
            append("总 ").append(ordered.size).append(" 条；复制保留 ").append(selected.size)
                .append(" 条；省略 ").append(omitted).append(" 条")
            if (omittedIssues > 0) append("（其中异常级事件 ").append(omittedIssues).append(" 条）")
            append('\n')
            selected.sortedWith(
                compareBy<Pair<AppLogRecord, String>> { it.first.timestampMs }.thenBy { it.first.sequence },
            ).forEach { (_, line) -> append(line).append('\n') }
            if (omitted > 0) {
                append("省略说明：按 FATAL、ERROR、WARN 优先，其次保留最近过程事件；没有复制的事件仍保存在本地完整日志中。\n")
            }
        }
        if (result.length <= MAX_CHAT_REPORT_CHARS) return result
        val suffix = "\n[已达到复制长度上限；请使用完整诊断报告文件查看剩余日志]"
        return result.take(MAX_CHAT_REPORT_CHARS - suffix.length) + suffix
    }

    private const val MAX_CHAT_REPORT_CHARS = 12_000

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
        val primary = ordered.firstOrNull {
            AppLogEventRelationPolicy.eventKey(it) == AppLogEventRelationPolicy.eventKey(target)
        } ?: target
        val diagnosis = AppLogDiagnostics.analyze(primary)
        val translationDiagnosticId = TranslationDiagnosticPackPolicy.idFromRecord(primary)
        val translationDiagnosticEventCount = translationDiagnosticId?.let { diagnosticId ->
            ordered.count { TranslationDiagnosticPackPolicy.idFromRecord(it) == diagnosticId }
        } ?: 0
        val relations = AppLogEventRelationPolicy.againstSelected(ordered, primary)
        val primaryJson = incidentPrimaryEventJson(primary, relations[AppLogEventRelationPolicy.eventKey(primary)])
        val core = buildString {
            append("# 大象翻译 AI 单条故障修复包\n")
            append("report_schema: elephant-app-log/incident-v8\n")
            append("导出时间：").append(exportedAt).append('\n')
            append("应用：").append(clipped(AppLogStore.redactForExport(appVersion), 48)).append('\n')
            append("设备：").append(clipped(AppLogStore.redactForExport(device), 64))
                .append("；Android ").append(clipped(AppLogStore.redactForExport(androidVersion), 32)).append('\n')
            append("主故障：").append(primary.level).append(" / ")
                .append(clipped(AppLogStore.redactForExport(primary.tag), 48)).append('\n')
            append("会话：").append(primary.sessionId.ifBlank { "旧版日志/未知" })
            if (primary.operationId.isNotBlank()) {
                append("；操作：").append(clipped(AppLogStore.redactForExport(primary.operationId), 64))
            }
            append('\n')
            append("关联事件：").append(ordered.size)
            if (translationDiagnosticId != null) {
                append("；翻译诊断 ID：").append(translationDiagnosticId)
                append("；同 ID 事件：").append(translationDiagnosticEventCount)
            }
            append('\n')
            append("日志写入：").append(if (writerHealth.isHealthy) "正常" else "异常")
                .append("；累计失败 ").append(writerHealth.failedWriteCount)
            if (!writerHealth.isHealthy && writerHealth.lastFailureDescription.isNotBlank()) {
                append("；最近失败：").append(clipped(AppLogStore.redactForExport(writerHealth.lastFailureDescription), 180))
            }
            append('\n')

            append("\n## 1. 故障判断\n")
            append("分类：").append(clipped(diagnosis.category, 100))
                .append("；置信度：").append(clipped(diagnosis.confidence, 40)).append('\n')
            append("判断：").append(clipped(diagnosis.finding, 420)).append('\n')
            append("证据：").append(clipped(diagnosis.evidence, 420)).append('\n')
            val coverage = AppLogDiagnosticCoveragePolicy.analyze(primary)
            append("证据覆盖：").append(coverage.completenessPercent).append("%（")
                .append(coverage.collectedEvidenceCount).append("/")
                .append(coverage.requiredEvidenceCount).append("）；缺失：")
                .append(coverage.missingEvidence.take(4).joinToString("、").ifBlank { "无" })
            if (coverage.missingEvidence.size > 4) {
                append(" 等 ").append(coverage.missingEvidence.size).append(" 项")
            }
            append('\n')
            diagnosis.nextSteps.take(3).forEachIndexed { index, step ->
                append("排查").append(index + 1).append("：").append(clipped(step, 150)).append('\n')
            }

            append("\n## 2. 主故障原始证据（仅输出一次）\n")
            append(primaryJson.toString()).append('\n')

            append("\n## 3. 关联关系\n")
            append("时间先后不等于因果；按操作 ID/翻译诊断 ID/故障编号确认关联。缺失字段表示未采集，不得猜测。\n")

            append("\n## 4. AI 修复要求\n")
            if (translationDiagnosticId != null) {
                append("翻译故障：按同一诊断 ID 还原请求、批次和字幕行；分别检查网络/HTTP、响应解析、字幕对齐、译文有效性；不要把原文回显、符号、音乐标签、人名或专有名词直接判为同一种错误。\n")
            }
            append("定位真实责任文件/类/方法/行号；先检查最深层 cause 和 suppressed，再核对调用位置、状态码及最后成功步骤。区分已证实事实与推测；给出最小修复和 YouTube/网页/本地回归验证。不得吞异常、伪造成功或用原声兜底掩盖失败。\n")
            append("隐私：已执行常见凭据脱敏；发送前检查字幕片段、文件名和业务内容。\n")
        }

        val primaryKey = AppLogEventRelationPolicy.eventKey(primary)
        val timelineCandidates = ordered.filter {
            AppLogEventRelationPolicy.eventKey(it) != primaryKey
        }
        val priority = timelineCandidates.filter { it.level in setOf("FATAL", "ERROR", "WARN") }
            .sortedWith(
                compareBy<AppLogRecord> {
                    when (it.level) {
                        "FATAL" -> 0
                        "ERROR" -> 1
                        else -> 2
                    }
                }.thenBy { kotlin.math.abs(it.timestampMs - primary.timestampMs) },
            )
        val context = timelineCandidates.filterNot { it.level in setOf("FATAL", "ERROR", "WARN") }
            .sortedBy { kotlin.math.abs(it.timestampMs - primary.timestampMs) }
        val timelineHeader = "\n## 5. 关联事件时间线（主故障不重复）\n"
        val reserveForFooter = 260
        val timelineBudget = (MAX_INCIDENT_REPORT_CHARS - core.length - timelineHeader.length - reserveForFooter)
            .coerceAtLeast(0)
        val included = mutableListOf<Pair<AppLogRecord, String>>()
        var usedChars = 0
        (priority + context).forEach { event ->
            val line = incidentTimelineEventJson(
                event = event,
                primary = primary,
                relation = relations[AppLogEventRelationPolicy.eventKey(event)],
                index = included.size + 1,
            ).toString()
            val lineCost = line.length + 1
            if (usedChars + lineCost <= timelineBudget) {
                included += event to line
                usedChars += lineCost
            }
        }
        val includedKeys = included.map { AppLogEventRelationPolicy.eventKey(it.first) }.toSet()
        val omitted = timelineCandidates.size - included.size
        val omittedPriority = timelineCandidates.count {
            it.level in setOf("FATAL", "ERROR", "WARN") &&
                AppLogEventRelationPolicy.eventKey(it) !in includedKeys
        }
        val result = buildString {
            append(core)
            append(timelineHeader)
            append("时间线：总 ").append(timelineCandidates.size + 1)
                .append(" 条（主故障 1 条已在上方完整列出）；本段保留 ")
                .append(included.size).append(" 条")
            if (omitted > 0) {
                append("；省略 ").append(omitted).append(" 条")
                if (omittedPriority > 0) append("，其中异常级事件 ").append(omittedPriority).append(" 条")
            }
            append('\n')
            included.sortedWith(
                compareBy<Pair<AppLogRecord, String>> { it.first.timestampMs }
                    .thenBy { it.first.sequence },
            ).forEach { (_, line) ->
                append(line).append('\n')
            }
            if (omitted > 0) {
                append("省略范围：受复制长度上限影响，优先保留 FATAL/ERROR/WARN，再保留距主故障较近的过程事件；完整记录仍保存在本地日志及“导出 AI 诊断报告”中。\n")
            }
        }
        if (result.length <= MAX_INCIDENT_REPORT_CHARS) return result
        val suffix = "\n[复制包达到长度上限；剩余内容请从本地完整诊断报告查看]"
        return result.take(MAX_INCIDENT_REPORT_CHARS - suffix.length) + suffix
    }

    private const val MAX_INCIDENT_REPORT_CHARS = 12_000

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
        index: Int,
    ): JSONObject {
        val isIssue = event.level in setOf("FATAL", "ERROR", "WARN")
        val context = incidentContextJson(event.diagnosticContext, maxFields = if (isIssue) 5 else 3, maxValueChars = 100)
        val result = JSONObject()
            .put("index", index)
            .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs + "-" + index })
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
