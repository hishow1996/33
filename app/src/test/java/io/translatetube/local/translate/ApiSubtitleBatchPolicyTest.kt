package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSubtitleBatchPolicyTest {
    private fun snapshot(model: String) = ApiSubtitleBatchLearningState(model).snapshot()

    @Test
    fun glmStartsAtFiftyRowsAndQwenStartsAtThirtyFiveRows() {
        val lines = List(200) { "A short subtitle." }
        val glm = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "GLM-4-Flash-250414", "zh-CN", snapshot("GLM-4-Flash-250414"),
        )
        val qwen = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN", snapshot("Qwen/Qwen2.5-7B-Instruct"),
        )
        assertEquals(50, glm.endExclusive)
        assertEquals(35, qwen.endExclusive)
    }

    @Test
    fun oneMillionTokenModelsCanStartWithLargerBatches() {
        val lines = List(200) { "A short subtitle." }
        val model = "Qwen/Qwen2.5-7B-Instruct-1M"
        val selection = ApiSubtitleBatchPolicy.selectBatch(lines, 0, model, "zh-CN", snapshot(model))
        assertEquals(80, selection.endExclusive)
        assertEquals(
            1_010_000,
            ApiSubtitleBatchPolicy.capabilityForModel(model).contextWindowTokens,
        )
    }

    @Test
    fun inputAndOutputLimitsAreIndependentFromTheContextWindow() {
        val normal = ApiSubtitleBatchPolicy.capabilityForModel("Qwen/Qwen2.5-7B-Instruct")
        val longContext = ApiSubtitleBatchPolicy.capabilityForModel("Qwen/Qwen2.5-7B-Instruct-1M")
        assertEquals(131_072, normal.contextWindowTokens)
        assertEquals(8_192, normal.maxOutputTokens)
        assertEquals(1_010_000, longContext.contextWindowTokens)
        assertEquals(8_192, longContext.maxOutputTokens)
    }

    @Test
    fun longSubtitlesReduceBatchSizeByTokenBudget() {
        val lines = List(80) { "中".repeat(300) }
        val glm = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "glm-4-flash-250414", "zh-CN", snapshot("glm-4-flash-250414"),
        )
        val qwen = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN", snapshot("Qwen/Qwen2.5-7B-Instruct"),
        )
        assertTrue(glm.endExclusive in 1 until 50)
        assertTrue(qwen.endExclusive in 1 until 35)
    }

    @Test
    fun unknownModelsStartConservativelyAndLearnToIncrease() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        assertEquals(30, learning.snapshot().maxRows)
        repeat(2) {
            learning.recordSuccess(30, 4_000, 1_200, 4_000, 1_200, 4_096, "stop")
        }
        assertTrue(learning.snapshot().maxRows > 30)
    }

    @Test
    fun recoverableFailuresReduceTheNextBatchLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordFailure(30)
        assertEquals(15, learning.snapshot().maxRows)
    }

    @Test
    fun actualUsageCalibratesTokenEstimates() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordSuccess(30, 2_000, 1_000, 3_000, 1_500, 4_096, "stop")
        assertTrue(learning.snapshot().inputTokenMultiplier > 1.0)
        assertTrue(learning.snapshot().outputTokenMultiplier > 1.0)
    }

    @Test
    fun consecutiveBatchesCoverAllCuesWithoutGaps() {
        val lines = List(80) { "中".repeat(100) }
        val model = "glm-4-flash-250414"
        val learning = ApiSubtitleBatchLearningState(model)
        var nextIndex = 0
        var totalRows = 0
        while (nextIndex < lines.size) {
            val selection = ApiSubtitleBatchPolicy.selectBatch(
                lines, nextIndex, model, "zh-CN", learning.snapshot(),
            )
            assertTrue(selection.endExclusive > nextIndex)
            totalRows += selection.endExclusive - nextIndex
            nextIndex = selection.endExclusive
        }
        assertEquals(lines.size, nextIndex)
        assertEquals(lines.size, totalRows)
    }
}