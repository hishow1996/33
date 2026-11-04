package io.translatetube.local.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogAiReportTest {
    @Test
    fun reportContainsSummaryAndCompleteChronologicalJsonlTimeline() {
        val records = listOf(
            AppLogRecord(
                timestampMs = 2_000L,
                level = "ERROR",
                tag = "Translation",
                message = "字幕翻译失败",
                details = "异常根因链：\\n#0 java.net.SocketTimeoutException: timeout",
                thread = "DefaultDispatcher-worker-2",
                eventId = "event-2",
                sessionId = "session-a",
                operationId = "pipeline-test-1",
                sequence = 2L,
                category = "字幕翻译",
                errorClass = "java.net.SocketTimeoutException",
                rootCauseMessage = "connect timed out",
                firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.translate(TranslationEngine.kt:88)",
                diagnosticContext = mapOf(
                    "HTTP 状态码" to "429",
                    "请求阶段" to "提交",
                    "当前处理阶段" to "TRANSLATING",
                    "当前 UI 阶段" to "TRANSLATING",
                    "当前处理步骤" to "正在处理第 1/3 批",
                    "处理流程" to "AUTO",
                    "翻译引擎" to "测试翻译引擎",
                    "字幕总行数" to "50",
                    "已获得有效译文行数" to "16",
                ),
                httpStatusCode = 429,
                exceptionSnapshot = AppLogExceptionSnapshot(
                    type = "java.lang.IllegalStateException",
                    message = "translation request failed",
                    stackFrames = listOf(
                        AppLogStackFrame(
                            "io.translatetube.local.translate.TranslationEngine",
                            "translate",
                            "TranslationEngine.kt",
                            88,
                        ),
                    ),
                    cause = AppLogExceptionSnapshot(
                        type = "java.net.SocketTimeoutException",
                        message = "read timed out",
                        stackFrames = listOf(
                            AppLogStackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 0),
                        ),
                    ),
                    suppressed = listOf(
                        AppLogExceptionSnapshot(
                            type = "java.io.IOException",
                            message = "response close failed",
                            stackFrames = emptyList(),
                            truncated = true,
                        ),
                    ),
                ),
            ),
            AppLogRecord(
                timestampMs = 1_000L,
                level = "INFO",
                tag = "PlaybackPipeline",
                message = "开始处理视频",
                details = "阶段：语音转写",
                thread = "main",
                diagnosticContext = mapOf(
                    "当前处理阶段" to "TRANSCRIBING",
                    "当前 UI 阶段" to "TRANSCRIBING",
                    "当前处理步骤" to "正在整理语音时间轴",
                    "处理流程" to "AUTO",
                    "转写引擎" to "Whisper",
                ),
                eventId = "event-1",
                sessionId = "session-a",
                operationId = "pipeline-test-1",
                sequence = 1L,
                category = "视频处理流程",
            ),
        )
        val report = AppLogAiReport.render(
            records = records,
            exportedAt = "2026-11-04 20:00:00 +0800",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 2_000L, true),
        )

        assertTrue(report.contains("report_schema: elephant-app-log/v8"))
        assertTrue(report.contains("最近最多 800 条"))
        assertTrue(report.contains("优先检查的故障事件"))
        assertTrue(report.contains("完整事件时间线（JSONL"))
        assertTrue(report.contains("\"eventId\":\"event-1\""))
        assertTrue(report.contains("\"eventId\":\"event-2\""))
        assertTrue(report.contains("\"errorClass\":\"java.net.SocketTimeoutException\""))
        assertTrue(report.contains("\"rootCauseMessage\":\"connect timed out\""))
        assertTrue(report.contains("\"firstAppStackFrame\":\"io.translatetube.local.translate.TranslationEngine.translate(TranslationEngine.kt:88)\""))
        assertTrue(report.contains("\"HTTP 状态码\":\"429\""))
        assertTrue(report.contains("\"请求阶段\":\"提交\""))
        assertTrue(report.contains("AI 分类=HTTP 429：请求频率受限"))
        assertTrue(report.contains("\"exceptionSnapshot\""))
        assertTrue(report.contains("\"type\":\"java.net.SocketTimeoutException\""))
        assertTrue(report.contains("\"message\":\"response close failed\""))
        assertTrue(report.contains("\"truncated\":true"))
        assertTrue(report.indexOf("\"eventId\":\"event-1\"") < report.indexOf("\"eventId\":\"event-2\""))
        assertTrue(report.contains("不要把不同进程会话混为一次任务"))
        assertTrue(report.contains("任务故障关系候选"))
        assertTrue(report.contains("五阶段诊断摘要"))
        assertTrue(report.contains("阶段=TRANSLATING"))
        assertTrue(report.contains("阶段=TRANSCRIBING"))
        assertTrue(report.contains("正在处理第 1/3 批"))
        assertTrue(report.contains("\"processingStage\":\"TRANSLATING\""))
        assertTrue(report.contains("最早异常候选事件=event-2"))
        assertTrue(report.contains("FIRST_RECORDED_ANOMALY_CANDIDATE"))
        assertTrue(report.contains("不能直接认定为真实根因"))
        assertTrue(report.contains("operationFailureRole"))
        assertTrue(report.contains("诊断证据覆盖率"))
        assertTrue(report.contains("缺失证据="))
        assertTrue(report.contains("diagnosticCoverage"))
        assertTrue(report.contains("aiDiagnosis"))
        assertTrue(report.contains("recentEventTrailScope"))
        assertTrue(report.contains("diagnosticContextBudget"))
    }

    @Test
    fun legacyRecordsAreExplicitlyMarked() {
        val report = AppLogAiReport.render(
            records = listOf(AppLogRecord(1_000L, "WARN", "Legacy", "旧版警告", "", "main")),
            exportedAt = "now",
            appVersion = "unknown",
            device = "unknown",
            androidVersion = "unknown",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 0L, true),
        )
        assertTrue(report.contains("legacy/unknown"))
        assertTrue(report.contains("legacy-1000-1"))
        assertTrue(report.contains("\"schemaVersion\":1"))
        assertTrue(report.contains("\"reportSchemaVersion\":7"))
        assertFalse(report.contains("undefined"))
    }
    @Test
    fun incidentReportIncludesPrimaryFailureAndOnlyItsCorrelatedEvents() {
        val target = AppLogRecord(
            timestampMs = 20_000L,
            level = "ERROR",
            tag = "Translation",
            message = "字幕翻译失败",
            details = "异常根因链：\\n#0 java.net.SocketTimeoutException: read timed out",
            thread = "DefaultDispatcher-worker-2",
            eventId = "failure-2",
            sessionId = "session-a",
            sequence = 2L,
            category = "字幕翻译",
            operationId = "op-77",
            errorClass = "java.net.SocketTimeoutException",
            rootCauseMessage = "read timed out",
            firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
            diagnosticContext = mapOf("HTTP 状态码" to "504", "请求阶段" to "读取响应"),
            httpStatusCode = 504,
            exceptionSnapshot = AppLogExceptionSnapshot(
                type = "java.io.IOException",
                message = "translation request failed",
                stackFrames = emptyList(),
                cause = AppLogExceptionSnapshot(
                    type = "java.net.SocketTimeoutException",
                    message = "read timed out",
                    stackFrames = emptyList(),
                ),
            ),
        )
        val related = target.copy(
            timestampMs = 19_000L,
            level = "INFO",
            tag = "Translation",
            message = "开始请求翻译服务",
            details = "请求阶段：提交",
            eventId = "start-1",
            sequence = 1L,
        )
        val earlierAnomaly = target.copy(
            timestampMs = 18_500L,
            level = "WARN",
            message = "翻译前置检查出现警告",
            eventId = "earlier-anomaly",
            sequence = 0L,
            issueKey = "earlier-issue",
        )
        val report = AppLogAiReport.renderIncident(
            records = listOf(earlierAnomaly, related, target),
            target = target,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 20_000L, true),
        )

        assertTrue(report.contains("report_schema: elephant-app-log/incident-v6"))
        assertTrue(report.contains("failure-2"))
        assertTrue(report.contains("start-1"))
        assertTrue(report.contains("rootCauseMessage"))
        assertTrue(report.contains("firstAppStackFrame"))
        assertTrue(report.contains("exceptionSnapshot"))
        assertTrue(report.contains("\"type\":\"java.net.SocketTimeoutException\""))
        assertTrue(report.contains("diagnosticContext"))
        assertTrue(report.contains("HTTP 504"))
        assertTrue(report.contains("关联事件时间线"))
        assertTrue(report.contains("不同进程会话不会混合"))
        assertTrue(report.contains("故障关系分析"))
        assertTrue(report.contains("relationToSelectedEvent"))
        assertTrue(report.contains("processingStage"))
        assertTrue(report.contains("SAME_OPERATION_CONTEXT"))
        assertTrue(report.contains("EARLIER_ANOMALY_SAME_OPERATION"))
        assertTrue(report.contains("仅是上游候选"))
        assertTrue(report.contains("诊断证据覆盖率"))
        assertTrue(report.contains("diagnosticCoverage"))
        assertTrue(report.contains("aiDiagnosis"))
        assertTrue(report.contains("recentEventTrailScope"))
        assertTrue(report.contains("diagnosticContextBudget"))
    }

}
