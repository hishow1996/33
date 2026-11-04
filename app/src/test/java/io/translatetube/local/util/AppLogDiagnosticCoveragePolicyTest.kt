package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDiagnosticCoveragePolicyTest {
    @Test
    fun translationFailureWithStructuredEvidenceIsMarkedComplete() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            category = "字幕翻译",
            message = "字幕翻译请求失败",
            details = "",
            thread = "DefaultDispatcher-worker-2",
            eventId = "event-translation",
            sessionId = "session-a",
            operationId = "pipeline-a",
            errorClass = "java.net.SocketTimeoutException",
            rootCauseMessage = "read timed out",
            firstAppStackFrame = "io.translatetube.local.translate.Engine.translate(Engine.kt:120)",
            httpStatusCode = 429,
            diagnosticContext = mapOf(
                "当前处理阶段" to "TRANSLATING",
                "翻译引擎名称" to "测试引擎",
                "当前批次" to "2/5",
                "输入字幕行数" to "20",
                "已获得有效译文行数" to "18",
                "HTTP 状态码" to "429",
                "传输/响应/网络重试轨迹" to "提交请求 -> HTTP 429 -> 等待重试",
            ),
        )
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(record)
        assertEquals("字幕翻译", coverage.domain)
        assertEquals(100, coverage.completenessPercent)
        assertTrue(coverage.missingEvidence.isEmpty())
        assertEquals(10, coverage.requiredEvidenceCount)
    }

    @Test
    fun sparseTtsFailureListsConcreteMissingEvidence() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "DubbingEngine",
            category = "配音合成",
            message = "第 4 句配音合成失败",
            details = "异常根因链：\n#0 java.io.IOException: audio response empty",
            thread = "DefaultDispatcher-worker-1",
            eventId = "event-tts",
            sessionId = "session-b",
            errorClass = "java.io.IOException",
            rootCauseMessage = "audio response empty",
        )
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(record)
        assertEquals("语音合成", coverage.domain)
        assertTrue(coverage.missingEvidence.contains("配音引擎、音色或服务商"))
        assertTrue(coverage.missingEvidence.contains("字幕行号/句子索引"))
        assertTrue(coverage.missingEvidence.contains("输出音频字节数、时长或 PCM 样本数"))
        assertTrue(coverage.collectionAdvice.isNotEmpty())
    }

    @Test
    fun explicitlyUnknownValuesDoNotCountAsCollectedEvidence() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "WARN",
            tag = "SpeechRecognition",
            message = "转写任务未完成",
            details = "",
            thread = "worker",
            diagnosticContext = mapOf(
                "转写引擎" to "未确定",
                "音频时长" to "未知",
                "采样率" to "未记录",
                "当前处理阶段" to "TRANSCRIBING",
            ),
        )
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(record)
        assertEquals("语音转写", coverage.domain)
        assertTrue(coverage.missingEvidence.contains("转写引擎及模型名称/版本"))
        assertTrue(coverage.missingEvidence.contains("音频时长或音频文件大小"))
        assertTrue(coverage.missingEvidence.contains("采样率、声道及音频格式"))
    }

    @Test
    fun infoEventsAreNotScoredAsFailures() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "INFO",
            tag = "PlaybackFlow",
            message = "处理开始",
            details = "",
            thread = "main",
        )
        val coverage = AppLogDiagnosticCoveragePolicy.analyze(record)
        assertEquals("不适用", coverage.domain)
        assertEquals(100, coverage.completenessPercent)
        assertTrue(coverage.missingEvidence.isEmpty())
    }
}
