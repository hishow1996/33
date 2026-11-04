package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        operation.updateSnapshot(
            mapOf(
                "当前处理阶段" to "TRANSLATING",
                "当前处理步骤" to "正在处理第 2/5 批",
                "处理流程" to "AUTO",
                "当前字幕行数" to "40",
                "已获得有效译文行数" to "16",
            ),
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
        assertEquals("TRANSLATING", enriched["当前处理阶段"])
        assertEquals("正在处理第 2/5 批", enriched["当前处理步骤"])
        assertEquals("AUTO", enriched["处理流程"])
        assertEquals("40", enriched["当前字幕行数"])
        assertEquals("16", enriched["已获得有效译文行数"])
        assertTrue(enriched["本轮处理已耗时 ms"]!!.toLong() >= 0L)
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
