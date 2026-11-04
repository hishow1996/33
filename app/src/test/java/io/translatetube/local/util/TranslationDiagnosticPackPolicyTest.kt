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
}
