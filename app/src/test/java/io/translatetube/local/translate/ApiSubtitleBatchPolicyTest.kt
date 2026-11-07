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
    fun oneMillionTokenModelsStartWithLargerBatchesButRespectOutputCaps() {
        val lines = List(200) { "A short subtitle." }
        val model = "Qwen/Qwen2.5-7B-Instruct-1M"
        val selection = ApiSubtitleBatchPolicy.selectBatch(lines, 0, model, "zh-CN", snapshot(model))
        assertEquals(80, selection.endExclusive)
        assertEquals(1_010_000, ApiSubtitleBatchPolicy.capabilityForModel(model).contextWindowTokens)
        assertEquals(8_192, selection.maxOutputTokens)
    }

    @Test
    fun inputAndOutputLimitsAreIndependentFromContextLength() {
        val standard = ApiSubtitleBatchPolicy.capabilityForModel("Qwen/Qwen2.5-7B-Instruct")
        val longContext = ApiSubtitleBatchPolicy.capabilityForModel("Qwen/Qwen2.5-7B-Instruct-1M")
        assertEquals(131_072, standard.contextWindowTokens)
        assertEquals(8_192, standard.maxOutputTokens)
        assertEquals(1_010_000, longContext.contextWindowTokens)
        assertEquals(8_192, longContext.maxOutputTokens)
    }

    @Test
    fun longSubtitlesReduceTheBatchByTokenBudget() {
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
    fun unknownModelsIncreaseOnlyAfterCleanOriginalBatches() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        assertEquals(30, learning.snapshot().maxRows)
        repeat(2) {
            learning.recordUsage(4_000, 1_200, 4_000, 1_200, 1_000)
            learning.recordOriginalBatchSuccess(30, recoveredBySplitting = false)
        }
        assertTrue(learning.snapshot().maxRows > 30)
    }

    @Test
    fun successfulSplitRecoveryDoesNotGrowTheGlobalLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(3) {
            learning.recordOriginalBatchSuccess(30, recoveredBySplitting = true)
        }
        assertEquals(30, learning.snapshot().maxRows)
    }

    @Test
    fun onlyFinalOriginalBatchFailureReducesTheNextLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchFailure(30)
        assertEquals(15, learning.snapshot().maxRows)
    }

    @Test
    fun actualUsageCalibratesInputAndOutputEstimates() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordUsage(2_000, 1_000, 3_000, 1_500, 750)
        assertTrue(learning.snapshot().inputTokenMultiplier > 1.0)
        assertTrue(learning.snapshot().outputTokenMultiplier > 1.0)
    }

    @Test
    fun providerLimitsRemainScopedToTheProvider() {
        val model = "custom-provider/long-context-model"
        ApiSubtitleBatchPolicy.registerProviderLimits(
            model = model,
            contextWindowTokens = 1_000_000,
            maxOutputTokens = 8_192,
            maxInputTokens = 300_000,
            providerKey = "https://provider-a.example/v1",
        )
        val discovered = ApiSubtitleBatchPolicy.capabilityForModel(model, "https://provider-a.example/v1")
        val other = ApiSubtitleBatchPolicy.capabilityForModel(model, "https://provider-b.example/v1")
        assertEquals(1_000_000, discovered.contextWindowTokens)
        assertEquals(80, discovered.initialRows)
        assertEquals(48_000, discovered.inputBudgetTokens)
        assertEquals(32_768, other.contextWindowTokens)
        assertEquals(30, other.initialRows)
    }

    @Test
    fun unknownNamesContainingOneMillionMarkerAreNotTrustedWithoutMetadata() {
        val capability = ApiSubtitleBatchPolicy.capabilityForModel("vendor/custom-1m-edition")
        assertEquals(32_768, capability.contextWindowTokens)
        assertEquals(30, capability.initialRows)
    }

    @Test
    fun promptEstimateIncludesSystemAndContextText() {
        val short = ApiSubtitleBatchPolicy.estimatePromptTokens("Translate.", "1|Hello")
        val long = ApiSubtitleBatchPolicy.estimatePromptTokens(
            "Translate. ".repeat(200),
            """previous reference cues: [{"source":"hello","translation":"你好"}] current cues: 1|Hello""",
        )
        assertTrue(long > short)
    }

    @Test
    fun promptEstimateIncludesSystemContextAndCalibration() {
        val short = ApiSubtitleBatchPolicy.estimatePromptTokens("Translate.", "1|Hello")
        val long = ApiSubtitleBatchPolicy.estimatePromptTokens(
            "Translate. ".repeat(200),
            "previous reference cues: [{\"source\":\"hello\",\"translation\":\"你好\"}] current cues: 1|Hello",
        )
        val calibrated = ApiSubtitleBatchPolicy.estimatePromptTokens(
            "Translate.", "1|Hello", tokenMultiplier = 2.0,
        )
        assertTrue(long > short)
        assertTrue(calibrated > short)
    }

    @Test
    fun slowSuccessfulOriginalBatchesDoNotGrowTheGlobalLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(2) {
            learning.recordUsage(4_000, 1_200, 4_000, 1_200, 12_000)
            learning.recordOriginalBatchSuccess(30, recoveredBySplitting = false)
        }
        assertEquals(30, learning.snapshot().maxRows)
    }

    @Test
    fun fullPromptEstimatorLimitsRowsByTheCompleteRequest() {
        val model = "glm-4-flash-250414"
        val lines = List(60) { "subtitle $it" }
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = lines,
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
            inputTokenEstimator = { candidate -> 18_000 + candidate.size * 300 },
        )
        assertEquals(20, selection.endExclusive)
        assertEquals(24_000, selection.estimatedInputTokens)
    }

    @Test
    fun oneCueThatExceedsInputBudgetIsMarkedBeforeDispatch() {
        val model = "glm-4-flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = listOf("a very long subtitle"),
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
            inputTokenEstimator = { 100_000 },
        )
        assertEquals(1, selection.endExclusive)
        assertTrue(selection.singleCueExceedsInputBudget)
    }

    @Test
    fun oneCueThatExceedsOutputBudgetIsMarkedBeforeDispatch() {
        val model = "glm-4-flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = listOf("中".repeat(2_000)),
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model).copy(outputBudgetTokens = 512),
        )
        assertEquals(1, selection.endExclusive)
        assertTrue(selection.singleCueExceedsOutputBudget)
    }

    @Test
    fun splitRecoveryUsesLargestVerifiedLeafAsTheNextBatchCeiling() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchSuccess(
            batchRows = 30,
            recoveredBySplitting = true,
            largestSuccessfulBatchRows = 8,
        )
        assertEquals(8, learning.snapshot().maxRows)
    }

    @Test
    fun finalSplitFailureUsesLargestVerifiedLeafAndHalfAttemptCeiling() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchFailure(30, largestSuccessfulBatchRows = 8)
        assertEquals(8, learning.snapshot().maxRows)
    }

    @Test
    fun independentInputMetadataDoesNotBecomeACombinedContextWindow() {
        val model = "gemini-catalog-model-1m"
        val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
            model = model,
            contextWindowTokens = null,
            maxOutputTokens = 8_192,
            maxInputTokens = 1_000_000,
            providerKey = "https://gemini.example/v1",
        )
        assertEquals(0, capability.contextWindowTokens)
        assertEquals(1_000_000, capability.maxInputTokens ?: 0)
        assertEquals(240_000, capability.maxInputBudgetTokens)
        assertEquals(48_000, capability.inputBudgetTokens)
    }
}
