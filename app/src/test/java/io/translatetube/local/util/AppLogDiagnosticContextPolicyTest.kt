package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDiagnosticContextPolicyTest {
    @Test
    fun priorityEvidenceIsRetainedAndOmittedFieldsAreCounted() {
        val input = linkedMapOf<String, String>()
        repeat(70) { input["extra-$it"] = "value-$it" }
        input["当前处理阶段"] = "TRANSLATING"
        input["操作关联 ID"] = "operation-123"
        val result = AppLogDiagnosticContextPolicy.bound(
            input = input,
            priorityKeys = setOf("当前处理阶段", "操作关联 ID"),
        )
        assertTrue(result.fields.size <= AppLogDiagnosticContextPolicy.MAX_FIELDS)
        assertEquals("TRANSLATING", result.fields["当前处理阶段"])
        assertEquals("operation-123", result.fields["操作关联 ID"])
        assertEquals(72, result.totalFieldCount)
        assertTrue(result.omittedFieldCount > 0)
        assertEquals(result.omittedFieldCount.toString(), result.fields["日志诊断字段省略数"])
    }

    @Test
    fun overlongValuesAreTruncatedWithAnExplicitMarkerAndCount() {
        val result = AppLogDiagnosticContextPolicy.bound(
            input = mapOf("响应摘要" to "x".repeat(3_000)),
            priorityKeys = emptySet(),
        )
        val value = result.fields.getValue("响应摘要")
        assertTrue(value.length <= AppLogDiagnosticContextPolicy.MAX_VALUE_CHARS)
        assertTrue(value.contains("字段截断"))
        assertEquals(1, result.truncatedFieldCount)
        assertEquals("1", result.fields["日志诊断字段截断数"])
        assertTrue(result.fields.getValue("日志诊断字段截断项（最多16项）").contains("响应摘要"))
    }

    @Test
    fun smallContextIsPreservedWithoutLoss() {
        val result = AppLogDiagnosticContextPolicy.bound(
            input = mapOf("当前处理阶段" to "TRANSCRIBING"),
            priorityKeys = setOf("当前处理阶段"),
        )
        assertEquals("TRANSCRIBING", result.fields["当前处理阶段"])
        assertEquals(0, result.omittedFieldCount)
        assertEquals(0, result.truncatedFieldCount)
        assertEquals("0", result.fields["日志诊断字段省略数"])
        assertEquals("0", result.fields["日志诊断字段截断数"])
    }
}
