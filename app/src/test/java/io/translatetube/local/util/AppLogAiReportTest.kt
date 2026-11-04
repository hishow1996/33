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

        assertTrue(report.contains("report_schema: elephant-app-log/v2"))
        assertTrue(report.contains("最近最多 800 条"))
        assertTrue(report.contains("优先检查的故障事件"))
        assertTrue(report.contains("完整事件时间线（JSONL"))
        assertTrue(report.contains("\"eventId\":\"event-1\""))
        assertTrue(report.contains("\"eventId\":\"event-2\""))
        assertTrue(report.contains("\"errorClass\":\"java.net.SocketTimeoutException\""))
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
        assertTrue(report.contains("\"reportSchemaVersion\":2"))
        assertFalse(report.contains("undefined"))
    }
}
