package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationDiagnosticPackPolicyTest {
    private val firstId = "8caaf8ef-8d5b-437d-b292-5fc859a6e17d"
    private val secondId = "e7f42e37-7cda-4a71-b3c1-c327291f6c2b"

    private fun row(
        timestamp: Long,
        id: String,
        message: String,
    ) = AppLogRecord(
        timestampMs = timestamp,
        level = "ERROR",
        tag = "Translation",
        message = message,
        details = "故障上下文：\n- 翻译诊断 ID：$id\n- 当前批次：1/2",
        thread = "DefaultDispatcher-worker-1",
    )

    @Test
    fun extractsOnlyAValidTranslationDiagnosticId() {
        assertEquals(firstId, TranslationDiagnosticPackPolicy.idFromRecord(row(1, firstId, "翻译请求失败")))
        assertNull(TranslationDiagnosticPackPolicy.normalizeId("not-a-uuid"))
        assertNull(
            TranslationDiagnosticPackPolicy.idFromRecord(
                row(2, "invalid-id", "invalid"),
            ),
        )
    }

    @Test
    fun selectsAllAndOnlyRecordsFromTheSameTranslationOperationInChronologicalOrder() {
        val records = listOf(
            row(30, firstId, "最终失败"),
            row(10, secondId, "另一条任务"),
            row(20, firstId, "模型响应"),
            row(5, firstId, "任务开始"),
        )

        val selected = TranslationDiagnosticPackPolicy.relatedRecords(records, firstId)

        assertEquals(listOf("任务开始", "模型响应", "最终失败"), selected.map { it.message })
        assertTrue(selected.all { TranslationDiagnosticPackPolicy.idFromRecord(it) == firstId })
        assertFalse(selected.any { it.message == "另一条任务" })
    }

    @Test
    fun invalidIdNeverMatchesAnyLogRecord() {
        val records = listOf(row(1, firstId, "event"))
        assertTrue(TranslationDiagnosticPackPolicy.relatedRecords(records, "bad-id").isEmpty())
    }

    @Test
    fun incidentRepairMergeIncludesEveryMatchingTranslationEventAndCorrelatedContext() {
        val start = row(10, firstId, "任务开始")
        val response = row(20, firstId, "模型响应")
        val selected = row(30, firstId, "当前故障")
        val unrelatedTranslation = row(25, secondId, "另一条翻译任务")
        val context = AppLogRecord(
            timestampMs = 22,
            level = "INFO",
            tag = "Translation",
            message = "同操作上下文",
            details = "当前请求上下文",
            thread = "DefaultDispatcher-worker-1",
        )

        val merged = TranslationDiagnosticPackPolicy.mergeIncidentRecords(
            allRecords = listOf(start, response, selected, unrelatedTranslation, context),
            incidentContextRecords = listOf(selected, context),
            selected = selected,
        )

        assertEquals(
            listOf("任务开始", "模型响应", "同操作上下文", "当前故障"),
            merged.map { it.message },
        )
        assertFalse(merged.any { it.message == "另一条翻译任务" })
    }

    @Test
    fun incidentRepairMergeNeverDropsTranslationEventsBeyondOrdinaryContextLimit() {
        val translationEvents = (0 until 90).map { index ->
            row(index.toLong(), firstId, "translation-event-$index")
        }
        val selected = translationEvents.last()

        val merged = TranslationDiagnosticPackPolicy.mergeIncidentRecords(
            allRecords = translationEvents,
            incidentContextRecords = listOf(selected),
            selected = selected,
            maxContextRecords = 80,
        )

        assertEquals(90, merged.size)
        assertEquals("translation-event-0", merged.first().message)
        assertEquals("translation-event-89", merged.last().message)
    }

    @Test
    fun incidentRepairMergeKeepsExistingContextLimitWithoutTranslationDiagnosticId() {
        val context = (0 until 90).map { index ->
            AppLogRecord(
                timestampMs = index.toLong(),
                level = "INFO",
                tag = "Playback",
                message = "context-$index",
                details = "context",
                thread = "main",
            )
        }
        val selected = context.last()

        val merged = TranslationDiagnosticPackPolicy.mergeIncidentRecords(
            allRecords = context,
            incidentContextRecords = context,
            selected = selected,
            maxContextRecords = 80,
        )

        assertEquals(80, merged.size)
        assertTrue(merged.any { it.message == selected.message })
    }
}
