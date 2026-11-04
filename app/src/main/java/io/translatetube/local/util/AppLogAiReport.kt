package io.translatetube.local.util

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

        return buildString {
            append("# 大象翻译 AI 修复诊断报告\n")
            append("report_schema: elephant-app-log/v4\n")
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
            append("- 尚未确认恢复的故障编号：").append(unresolved.size).append('\n')
            append("- 已有明确恢复记录的故障编号：").append(recovered.size).append('\n')
            append("- 日志写入器当前状态：").append(if (writerHealth.isHealthy) "正常" else "异常").append('\n')
            append("- 日志写入失败累计次数：").append(writerHealth.failedWriteCount).append('\n')
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

            append("\n## 2. 优先检查的故障事件（最多 80 条，按最新优先）\n")
            if (priorityEvents.isEmpty()) {
                append("当前保留范围内没有 WARN/ERROR/FATAL 事件。\n")
            } else {
                priorityEvents.forEachIndexed { index, event ->
                    val diagnosis = AppLogDiagnostics.analyze(event)
                    append(index + 1).append(". ")
                        .append(AppLogStore.formatTime(event.timestampMs))
                        .append(" [").append(event.level).append("] ")
                        .append(AppLogStore.redactForExport(AppLogTitleResolver.title(event.tag, event.message)))
                        .append("；事件 ID=").append(event.eventId.ifBlank { "legacy-" + event.timestampMs })
                        .append("；模块=").append(AppLogStore.redactForExport(event.tag))
                        .append("；异常=").append(event.errorClass.ifBlank { "未记录" })
                        .append("；根因=").append(event.rootCauseMessage.ifBlank { "未提取" })
                        .append("；首个应用栈帧=").append(event.firstAppStackFrame.ifBlank { "未记录" })
                        .append("；AI 分类=").append(diagnosis.category)
                        .append("；置信度=").append(diagnosis.confidence)
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

            append("\n## 3. 完整事件时间线（JSONL，按时间升序）\n")
            append("每行是一个独立事件；details 包含事件上下文、近期操作轨迹、环境快照及异常堆栈。\n")
            ordered.forEachIndexed { index, event ->
                val category = event.category.ifBlank {
                    AppLogTitleResolver.featureLabel(event.tag, event.message)
                }
                val json = JSONObject()
                    .put("schemaVersion", event.schemaVersion)
                    .put("reportSchemaVersion", 4)
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
                    .put("recoveredAtMs", event.recoveredAtMs ?: 0L)
                    .put("errorClass", AppLogStore.redactForExport(event.errorClass))
                    .put("rootCauseMessage", AppLogStore.redactForExport(event.rootCauseMessage))
                    .put("firstAppStackFrame", AppLogStore.redactForExport(event.firstAppStackFrame))
                    .put(
                        "exceptionSnapshot",
                        event.exceptionSnapshot?.let { AppLogStore.exceptionSnapshotForExport(it) } ?: JSONObject.NULL,
                    )
                    .put("httpStatusCode", event.httpStatusCode)
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
                    .put("details", AppLogStore.redactForExport(event.details))
                append(json.toString()).append('\n')
            }
            if (ordered.isEmpty()) append("（当前没有可导出的日志记录）\n")

            append("\n## 4. AI 修复要求\n")
            append("1. 按 sessionId、sequence、timestampMs 还原触发操作和阶段顺序；不要把不同进程会话混为一次任务。\n")
            append("2. 区分日志已证实的事实、合理假设和缺失证据；不得把推测写成已证实根因。\n")
            append("3. 优先读取 diagnosticContext、rootCauseMessage、firstAppStackFrame、exceptionSnapshot、httpStatusCode 和 operationId 等结构化字段；exceptionSnapshot 保留每层异常自己的堆栈及 Suppressed。若任一节点 truncated=true，必须注明异常树/栈帧已截断。\n")
            append("4. 检查最深层 cause、Suppressed 异常，以及第一个应用自身包名的栈帧；给出文件、类、方法和行号。\n")
            append("5. 网络问题同时分析 HTTP 状态码、服务商错误码、requestId、Content-Type、响应长度、重试和解析器结果；不要把所有失败归为网络问题。\n")
            append("6. 对 YouTube、网页视频和本地视频分别核对同一阶段的实现路径，并指出需要同步修复的入口。\n")
            append("7. 提出最小且可验证的修复，说明文件、方法、风险和回归测试；不得用静默吞错、空结果或原声兜底掩盖失败。\n")
            append("8. 日志不足时先指出应在真实失败分支补采的字段，不得臆造运行状态。\n")
            append("9. 注意导出范围与旧版记录字段缺失；空字段表示当时未采集，不得推断为数值零或功能未执行。\n")
            append("\n隐私说明：导出前再次执行凭据脱敏；分享前仍应检查字幕片段、文件名和业务内容是否包含个人信息。\n")
        }
    }

    /** A focused, copy-ready package for repairing the selected event without exporting all logs. */
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
            .distinctBy { it.eventId.ifBlank { it.timestampMs.toString() + ":" + it.tag + ":" + it.message } }
            .sortedWith(compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence })
        val primary = ordered.firstOrNull {
            if (target.eventId.isNotBlank()) it.eventId == target.eventId
            else it.timestampMs == target.timestampMs && it.tag == target.tag && it.message == target.message
        } ?: target
        val diagnosis = AppLogDiagnostics.analyze(primary)

        fun eventJson(event: AppLogRecord, index: Int): JSONObject = JSONObject()
            .put("index", index)
            .put("schemaVersion", event.schemaVersion)
            .put("eventId", event.eventId.ifBlank { "legacy-" + event.timestampMs + "-" + index })
            .put("sessionId", event.sessionId.ifBlank { "legacy/unknown" })
            .put("sequence", event.sequence)
            .put("timestampMs", event.timestampMs)
            .put("timestampLocal", AppLogStore.formatTime(event.timestampMs))
            .put("level", AppLogStore.redactForExport(event.level))
            .put("category", AppLogStore.redactForExport(event.category))
            .put("module", AppLogStore.redactForExport(event.tag))
            .put("message", AppLogStore.redactForExport(event.message))
            .put("operationId", AppLogStore.redactForExport(event.operationId))
            .put("issueKey", event.issueKey)
            .put("resolvesIssueKey", event.resolvesIssueKey)
            .put("recoveredAtMs", event.recoveredAtMs ?: 0L)
            .put("errorClass", AppLogStore.redactForExport(event.errorClass))
            .put("rootCauseMessage", AppLogStore.redactForExport(event.rootCauseMessage))
            .put("firstAppStackFrame", AppLogStore.redactForExport(event.firstAppStackFrame))
            .put(
                "exceptionSnapshot",
                event.exceptionSnapshot?.let { AppLogStore.exceptionSnapshotForExport(it) } ?: JSONObject.NULL,
            )
            .put("httpStatusCode", event.httpStatusCode)
            .put("processId", event.processId)
            .put("threadId", event.threadId)
            .put("thread", AppLogStore.redactForExport(event.thread))
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
            .put("details", AppLogStore.redactForExport(event.details))

        return buildString {
            append("# 大象翻译 AI 单条故障修复包\n")
            append("report_schema: elephant-app-log/incident-v2\n")
            append("导出时间：").append(exportedAt).append('\n')
            append("应用版本：").append(AppLogStore.redactForExport(appVersion)).append('\n')
            append("设备：").append(AppLogStore.redactForExport(device)).append('\n')
            append("Android：").append(AppLogStore.redactForExport(androidVersion)).append('\n')
            append("关联事件数：").append(ordered.size).append('\n')
            append("日志写入器状态：").append(if (writerHealth.isHealthy) "正常" else "异常")
                .append("；累计写入失败：").append(writerHealth.failedWriteCount).append('\n')
            if (!writerHealth.isHealthy) {
                append("最近一次日志写入失败：")
                    .append(AppLogStore.redactForExport(writerHealth.lastFailureDescription))
                    .append("；时间：")
                    .append(writerHealth.lastFailureTimestampMs.takeIf { it > 0L }?.let {
                        AppLogStore.formatTime(it)
                    } ?: "未知")
                    .append('\n')
            }
            append("主故障事件：").append(primary.eventId.ifBlank { "旧版日志/无事件 ID" }).append('\n')
            append("进程会话：").append(primary.sessionId.ifBlank { "旧版日志/未知" }).append('\n')
            append("操作关联 ID：").append(primary.operationId.ifBlank { "未采集" }).append('\n')
            append("时间范围：")
                .append(ordered.firstOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append(" 至 ")
                .append(ordered.lastOrNull()?.let { AppLogStore.formatTime(it.timestampMs) } ?: "无记录")
                .append('\n')

            append("\n## 1. 主故障判断（基于已记录证据）\n")
            append("- 分类：").append(diagnosis.category).append('\n')
            append("- 置信度：").append(diagnosis.confidence).append('\n')
            append("- 判断：").append(diagnosis.finding).append('\n')
            append("- 证据：").append(diagnosis.evidence).append('\n')
            diagnosis.nextSteps.forEachIndexed { index, step ->
                append("- 排查建议 ").append(index + 1).append("：").append(step).append('\n')
            }

            append("\n## 2. 主故障事件（结构化 JSON）\n")
            append(eventJson(primary, 1).toString()).append('\n')

            append("\n## 3. 关联事件时间线（JSONL，按时间升序）\n")
            append("事件按同一 operationId 优先关联；没有操作 ID 时，仅收录同一 sessionId 的相邻事件。不同进程会话不会混合。\n")
            ordered.forEachIndexed { index, event ->
                append(eventJson(event, index + 1).toString()).append('\n')
            }

            append("\n## 4. AI 修复要求\n")
            append("1. 先还原主故障前后的事件顺序，区分触发操作、失败步骤、重试和恢复结果。\n")
            append("2. 优先查看 exceptionSnapshot.cause 链最深层异常及每层 stackFrames；单独检查 suppressed，勿把清理异常误判为主故障。若 truncated=true，说明当前包只包含上限内证据。\n")
            append("3. 使用 rootCauseMessage 和 firstAppStackFrame 定位真实应用文件、类、方法及行号；若没有应用栈帧，明确说明证据缺口。\n")
            append("4. 根据 diagnosticContext 判断视频来源、处理阶段、引擎、请求状态、批次/句子索引等字段；缺失字段不得猜测。\n")
            append("5. 给出最小修复方案、可能影响的 YouTube/网页/本地入口、回归测试和验证标准。\n")
            append("6. 不得通过吞掉异常、返回空成功、跳过错误或原声兜底来掩盖真实失败。\n")
            append("\n隐私说明：报告对常见密钥、令牌、Cookie 和签名 URL 参数执行脱敏；分享前仍请检查字幕片段、文件名和业务内容。\n")
        }
    }

}
