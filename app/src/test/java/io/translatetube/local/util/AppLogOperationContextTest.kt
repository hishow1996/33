package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogOperationContextTest {
    @Test
    fun operationContextIsAddedWithoutDroppingTranslationDiagnostics() {
        val operation = AppLogOperationContext(
            operationId = "pipeline-run-123",
            mediaSource = "YouTube",
            videoSessionFingerprint = "video-fp-456",
            pipelineGeneration = 17L,
        )

        val enriched = AppLogStore.applyOperationContext(
            diagnosticContext = mapOf(
                "翻译诊断 ID" to "translation-run-789",
                "当前批次" to "2/5",
            ),
            operation = operation,
        )

        assertEquals("pipeline-run-123", enriched["操作关联 ID"])
        assertEquals("YouTube", enriched["视频来源"])
        assertEquals("video-fp-456", enriched["视频会话指纹"])
        assertEquals("17", enriched["处理代数"])
        assertEquals("translation-run-789", enriched["翻译诊断 ID"])
        assertEquals("2/5", enriched["当前批次"])
    }

    @Test
    fun pipelineOperationIdTakesPrecedenceOverNestedTranslationId() {
        val operationId = AppLogStore.extractOperationId(
            diagnosticContext = mapOf(
                "翻译诊断 ID" to "translation-run-789",
                "操作关联 ID" to "pipeline-run-123",
            ),
            details = "",
        )

        assertEquals("pipeline-run-123", operationId)
    }
}
