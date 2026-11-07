package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSubtitleBatchPolicyTest {
    @Test
    fun glmUsesFiftyRowsForShortSubtitles() {
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            List(80) { "A short subtitle." }, 0, "GLM-4-Flash-250414", "zh-CN",
        )
        assertEquals(50, selection.endExclusive)
    }

    @Test
    fun qwenUsesThirtyFiveRowsForShortSubtitles() {
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            List(80) { "A short subtitle." }, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN",
        )
        assertEquals(35, selection.endExclusive)
    }

    @Test
    fun longSubtitlesReduceBothModelBatchSizes() {
        val lines = List(80) { "中".repeat(100) }
        val glm = ApiSubtitleBatchPolicy.selectBatch(lines, 0, "glm-4-flash-250414", "zh-CN")
        val qwen = ApiSubtitleBatchPolicy.selectBatch(lines, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN")
        assertTrue(glm.endExclusive in 1 until 50)
        assertTrue(qwen.endExclusive in 1 until 35)
    }

    @Test
    fun otherModelsKeepTheExistingThirtyRowLimit() {
        val lines = List(80) { "中".repeat(100) }
        val selection = ApiSubtitleBatchPolicy.selectBatch(lines, 0, "qwen-plus", "zh-CN")
        assertEquals(30, selection.endExclusive)
    }

    @Test
    fun consecutiveBatchesCoverAllSourceCuesInOrder() {
        val lines = List(80) { "中".repeat(100) }
        var nextIndex = 0
        var totalRows = 0
        while (nextIndex < lines.size) {
            val selection = ApiSubtitleBatchPolicy.selectBatch(
                lines, nextIndex, "glm-4-flash-250414", "zh-CN",
            )
            assertTrue(selection.endExclusive > nextIndex)
            totalRows += selection.endExclusive - nextIndex
            nextIndex = selection.endExclusive
        }
        assertEquals(lines.size, nextIndex)
        assertEquals(lines.size, totalRows)
    }
}