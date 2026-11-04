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
    fun supplementalStageEvidenceSurvivesLiveUiSnapshotRefreshes() {
        val operation = AppLogOperationContext(
            operationId = "pipeline-run-321",
            mediaSource = "本地视频",
            videoSessionFingerprint = "video-fp-stage",
            pipelineGeneration = 23L,
        )
        operation.updateSnapshot(
            mapOf(
                "当前处理阶段" to "DOWNLOADING_AUDIO",
                "当前处理步骤" to "正在定位音轨",
                "字幕总行数" to "0",
            ),
        )
        operation.updateSupplementalSnapshot(
            mapOf(
                "当前处理阶段" to "TRANSCRIBING",
                "当前处理子步骤" to "16 kHz PCM 已准备，开始分段识别",
                "PCM 音频时长 ms" to "125000",
                "PCM 采样点数" to "2000000",
            ),
        )

        // A later UI tick refreshes the ordinary snapshot. It must not erase technical
        // evidence gathered by the ASR stage.
        operation.updateSnapshot(
            mapOf(
                "当前 UI 阶段" to "TRANSCRIBING",
                "当前处理步骤" to "转写中 3/8 段",
                "转写已完成分段" to "3",
                "转写总分段" to "8",
            ),
        )

        val fields = operation.diagnosticFields()
        assertEquals("TRANSCRIBING", fields["当前处理阶段"])
        assertEquals("转写中 3/8 段", fields["当前处理步骤"])
        assertEquals("16 kHz PCM 已准备，开始分段识别", fields["当前处理子步骤"])
        assertEquals("125000", fields["PCM 音频时长 ms"])
        assertEquals("2000000", fields["PCM 采样点数"])
        assertEquals("3", fields["转写已完成分段"])
        assertEquals("8", fields["转写总分段"])
    }

    @Test
    fun clearingSupplementalSnapshotRemovesPreviousStageEvidence() {
        val operation = AppLogOperationContext(
            operationId = "pipeline-run-654",
            mediaSource = "YouTube",
            videoSessionFingerprint = "video-fp-next-stage",
            pipelineGeneration = 24L,
        )
        operation.updateSupplementalSnapshot(mapOf("PCM 音频时长 ms" to "125000"))
        operation.updateSupplementalSnapshot(emptyMap())

        assertTrue(!operation.diagnosticFields().containsKey("PCM 音频时长 ms"))
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
