package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDiagnosticContextPolicyTest {
    @Test
    fun priorityEvidenceIsRetainedAndOmittedFieldsAreCounted() {
        val input = linkedMapOf<String, String>()
        repeat(90) { input["extra-$it"] = "value-$it" }
        input["当前处理阶段"] = "TRANSLATING"
        input["操作关联 ID"] = "operation-123"
        val result = AppLogDiagnosticContextPolicy.bound(
            input = input,
            priorityKeys = setOf("当前处理阶段", "操作关联 ID"),
        )
        assertTrue(result.fields.size <= AppLogDiagnosticContextPolicy.MAX_FIELDS)
        assertEquals("TRANSLATING", result.fields["当前处理阶段"])
        assertEquals("operation-123", result.fields["操作关联 ID"])
        assertEquals(92, result.totalFieldCount)
        assertTrue(result.omittedFieldCount > 0)
        assertEquals(result.omittedFieldCount.toString(), result.fields["日志诊断字段省略数"])
    }

    @Test
    fun stageSpecificFailureEvidenceCanBePrioritizedTogether() {
        val important = linkedMapOf(
            "当前处理阶段" to "TRANSCRIBING",
            "操作关联 ID" to "pipeline-123",
            "当前处理子步骤" to "16 kHz PCM 已准备",
            "失败子阶段" to "云端响应解析",
            "转写服务商 ID" to "elevenlabs",
            "云端模型 ID" to "scribe_v2",
            "PCM 音频文件字节数" to "6400000",
            "PCM 音频时长 ms" to "200000",
            "PCM 采样率 Hz" to "16000",
            "VAD 最终分段总数" to "24",
            "云端请求阶段" to "上传 WAV 并提交任务",
            "最近转写进度" to "已收到服务端响应，正在整理词级时间戳",
            "当前批次" to "2/5",
            "本次请求耗时 ms" to "18450",
            "字幕行号" to "17",
            "当前视频位置 ms" to "125000",
            "待合成队列总长度" to "8",
            "PCM 内存缓存条目数" to "12",
            "配音引擎 ID" to "edge",
        )
        val input = linkedMapOf<String, String>()
        repeat(90) { input["optional-$it"] = "value-$it" }
        input.putAll(important)

        val result = AppLogDiagnosticContextPolicy.bound(
            input = input,
            priorityKeys = important.keys,
        )

        important.forEach { (key, value) ->
            assertEquals(value, result.fields[key])
        }
        assertTrue(result.fields.size <= AppLogDiagnosticContextPolicy.MAX_FIELDS)
        assertTrue(result.omittedFieldCount > 0)
    }

    @Test
    fun allPriorityFieldsFitInsideTheProductionContextBudget() {
        val priority = (0 until 81).associate { index ->
            "priority-$index" to "value-$index"
        }
        val result = AppLogDiagnosticContextPolicy.bound(
            input = priority + mapOf("optional-1" to "extra-1", "optional-2" to "extra-2"),
            priorityKeys = priority.keys,
        )

        priority.forEach { (key, value) ->
            assertEquals(value, result.fields[key])
        }
        assertEquals(0, result.omittedFieldCount)
        assertTrue(result.fields.size <= AppLogDiagnosticContextPolicy.MAX_FIELDS)
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
