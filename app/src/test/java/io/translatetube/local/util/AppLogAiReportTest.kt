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
                sequence = 2L,
                category = "字幕翻译",
                errorClass = "java.net.SocketTimeoutException",
                rootCauseMessage = "connect timed out",
                firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.translate(TranslationEngine.kt:88)",
                diagnosticContext = mapOf("HTTP 状态码" to "429", "请求阶段" to "提交"),
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
                eventId = "event-1",
                sessionId = "session-a",
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

        assertTrue(report.contains("report_schema: elephant-app-log/v4"))
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
        assertTrue(report.contains("\"reportSchemaVersion\":4"))
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
        val report = AppLogAiReport.renderIncident(
            records = listOf(related, target),
            target = target,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 20_000L, true),
        )

        assertTrue(report.contains("report_schema: elephant-app-log/incident-v2"))
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
    }

}
