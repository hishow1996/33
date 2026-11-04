package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStageSummaryPolicyTest {
    @Test
    fun groupsStageEventsBySessionAndOperationAndCountsFailures() {
        fun record(
            time: Long,
            eventId: String,
            level: String,
            stage: String,
            progress: String,
            operationId: String = "op-1",
            sessionId: String = "session-1",
        ) = AppLogRecord(
            timestampMs = time,
            level = level,
            tag = stage,
            message = eventId,
            details = "",
            thread = "worker",
            eventId = eventId,
            sessionId = sessionId,
            sequence = time,
            operationId = operationId,
            diagnosticContext = mapOf(
                "当前处理阶段" to stage,
                "当前处理步骤" to progress,
                "转写引擎" to "Whisper",
                "翻译引擎" to "Google",
                "配音引擎" to "Edge TTS",
                "当前字幕行数" to "24",
                "已获得有效译文行数" to "16",
                "已准备配音行数" to "8",
            ),
        )

        val summaries = AppLogStageSummaryPolicy.summarize(
            listOf(
                record(1_000L, "asr-start", "INFO", "TRANSCRIBING", "读取音轨"),
                record(2_000L, "asr-warning", "WARN", "TRANSCRIBING", "时间轴校验"),
                record(3_000L, "translate-failure", "ERROR", "TRANSLATING", "第 2/3 批", operationId = "op-2"),
                record(4_000L, "other-task", "ERROR", "TRANSLATING", "另一个任务", sessionId = "session-2"),
            ),
        )

        assertEquals(3, summaries.size)
        val transcription = summaries.first { it.operationId == "op-1" && it.stage == "TRANSCRIBING" }
        assertEquals(2, transcription.eventCount)
        assertEquals(1, transcription.warningCount)
        assertEquals(0, transcription.errorCount)
        assertEquals("时间轴校验", transcription.latestProgress)
        assertEquals("Whisper", transcription.transcriptionEngine)
        assertEquals("24", transcription.latestSubtitleCount)

        val translation = summaries.first { it.operationId == "op-2" }
        assertEquals(1, translation.errorCount)
        assertTrue(translation.latestProgress.contains("第 2/3 批"))
    }

    @Test
    fun doesNotGroupOldRecordsWithoutOperationOrStageEvidence() {
        val legacy = AppLogRecord(
            timestampMs = 1_000L,
            level = "ERROR",
            tag = "Legacy",
            message = "old error",
            details = "",
            thread = "main",
        )
        assertTrue(AppLogStageSummaryPolicy.summarize(listOf(legacy)).isEmpty())
    }
}
