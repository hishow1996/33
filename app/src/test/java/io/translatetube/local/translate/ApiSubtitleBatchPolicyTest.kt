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
        // Dynamic output sizing may be below the provider cap for short batches.
        assertTrue(selection.maxOutputTokens >= selection.estimatedOutputTokens)
        assertTrue(selection.maxOutputTokens <= ApiSubtitleBatchPolicy.capabilityForModel(model).maxOutputTokens)
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
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(35, learning.snapshot().maxRows)
    }

    @Test
    fun successfulSplitRecoveryDoesNotGrowTheGlobalLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(3) {
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = true,
                recoveredBySplitting = true,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(30, learning.snapshot().maxRows)
    }

    @Test
    fun onlyFinalOriginalBatchFailureReducesTheNextLimitByFiveRows() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(25, learning.snapshot().maxRows)
    }

    @Test
    fun glmFailureReducesTheNextBatchByFiveRowsAtATime() {
        val learning = ApiSubtitleBatchLearningState("GLM-4-Flash-250414")
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(45, learning.snapshot().maxRows)
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(40, learning.snapshot().maxRows)
    }

    @Test
    fun singleCueRecoveryCannotLowerTheModelWideBatchLimit() {
        val learning = ApiSubtitleBatchLearningState("GLM-4-Flash-250414")
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            hadRecoverableFailure = true,
            recoveredBySplitting = true,
            failureKinds = emptySet(),
            allRowsAccepted = true,
        )
        assertEquals(50, learning.snapshot().maxRows)
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
    fun providerEndpointNormalizationIgnoresCredentialsQueryAndTrailingSlash() {
        assertEquals(
            "https://relay.example/v1",
            ApiSubtitleBatchPolicy.normalizeProviderKey(
                "https://user:secret@Relay.Example:443/v1/?token=private#fragment",
            ),
        )
        assertEquals(
            ApiSubtitleBatchPolicy.normalizeProviderKey("https://relay.example/v1"),
            ApiSubtitleBatchPolicy.normalizeProviderKey("https://RELAY.example:443/v1/"),
        )
    }

    @Test
    fun providerEndpointPathRemainsPartOfTheLearningIdentity() {
        val model = "vendor/shared-model"
        ApiSubtitleBatchPolicy.registerProviderLimits(
            model = model,
            contextWindowTokens = 1_048_576,
            maxOutputTokens = 16_384,
            providerKey = "https://relay.example/api/v1",
        )
        val sameEndpoint = ApiSubtitleBatchPolicy.capabilityForModel(model, "https://relay.example/api/v1/")
        val differentEndpoint = ApiSubtitleBatchPolicy.capabilityForModel(model, "https://relay.example/v1")
        assertEquals(1_048_576, sameEndpoint.contextWindowTokens)
        assertEquals(32_768, differentEndpoint.contextWindowTokens)
    }

    @Test
    fun deepSeekV4UsesItsPublishedMillionTokenCatalogLimits() {
        val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
            model = "deepseek-v4-pro",
            contextWindowTokens = 1_048_576,
            maxOutputTokens = 393_216,
            providerKey = "https://api.deepseek.com",
        )
        assertEquals(1_048_576, capability.contextWindowTokens)
        assertEquals(393_216, capability.maxOutputTokens)
        assertEquals(80, capability.initialRows)
        assertEquals(200, capability.maxRows)
    }

    @Test
    fun knownKimiAndGpt6ModelsDoNotFallBackToUnknownModelLimits() {
        val kimi = ApiSubtitleBatchPolicy.capabilityForModel("kimi-k3.1-pro")
        val gpt6 = ApiSubtitleBatchPolicy.capabilityForModel("gpt-6-astra")
        assertEquals(1_048_576, kimi.contextWindowTokens)
        assertEquals(1_050_000, gpt6.contextWindowTokens)
        assertEquals(128_000, gpt6.maxOutputTokens)
        assertEquals(80, kimi.initialRows)
        assertEquals(80, gpt6.initialRows)
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
    fun slowSuccessfulOriginalBatchesCanStillGrowTheGlobalLimit() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(2) {
            learning.recordUsage(4_000, 1_200, 4_000, 1_200, 12_000)
            learning.recordOriginalBatchSuccess(
                    limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                    hadRecoverableFailure = false,
                    recoveredBySplitting = false,
                    failureKinds = emptySet(),
                    allRowsAccepted = true,
                )
        }
        assertTrue(learning.snapshot().maxRows > 30)
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
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET in selection.limitingReasons)
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT !in selection.limitingReasons)
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
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET in selection.limitingReasons)
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
        assertTrue(ApiSubtitleBatchLimitReason.OUTPUT_BUDGET in selection.limitingReasons)
    }

    @Test
    fun successfulSplitDoesNotClampGlobalRowCapToTheLargestLeaf() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            hadRecoverableFailure = true,
            recoveredBySplitting = true,
            failureKinds = emptySet(),
            allRowsAccepted = true,
        )
        assertEquals(30, learning.snapshot().maxRows)
    }

    @Test
    fun formatFailureReducesTheBindingRowCapByFiveWithoutChangingTokenBudgets() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        val after = learning.snapshot()
        assertEquals(25, after.maxRows)
        assertEquals(before.inputBudgetTokens, after.inputBudgetTokens)
        assertEquals(before.outputBudgetTokens, after.outputBudgetTokens)
    }

    @Test
    fun inputCapacityFailureChangesOnlyInputBudgetWhenRowCapWasNotBinding() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.INPUT_BUDGET),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.INPUT_CAPACITY),
        )
        val after = learning.snapshot()
        assertEquals(before.maxRows, after.maxRows)
        assertTrue(after.inputBudgetTokens < before.inputBudgetTokens)
        assertEquals(before.outputBudgetTokens, after.outputBudgetTokens)
    }

    @Test
    fun outputCapacityFailureChangesOnlyOutputBudgetWhenRowCapWasNotBinding() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.OUTPUT_BUDGET),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY),
        )
        val after = learning.snapshot()
        assertEquals(before.maxRows, after.maxRows)
        assertEquals(before.inputBudgetTokens, after.inputBudgetTokens)
        assertTrue(after.outputBudgetTokens < before.outputBudgetTokens)
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
    @Test
    fun cleanSuccessOnlyGrowsTheConstraintThatStoppedTheBatch() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        repeat(2) {
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.INPUT_BUDGET),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        val after = learning.snapshot()
        assertEquals(before.maxRows, after.maxRows)
        assertTrue(after.inputBudgetTokens > before.inputBudgetTokens)
        assertEquals(before.outputBudgetTokens, after.outputBudgetTokens)
    }

    @Test
    fun outputBudgetFailureDoesNotReduceTheRowLimitEvenWhenItWasAlsoReached() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(
                ApiSubtitleBatchLimitReason.ROW_LIMIT,
                ApiSubtitleBatchLimitReason.OUTPUT_BUDGET,
            ),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY),
        )
        val after = learning.snapshot()
        assertEquals(before.maxRows, after.maxRows)
        assertEquals(before.inputBudgetTokens, after.inputBudgetTokens)
        assertTrue(after.outputBudgetTokens < before.outputBudgetTokens)
    }

    @Test
    fun recoveredOutputCapacityFailureTrainsTheNextOriginalBatch() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.OUTPUT_BUDGET),
            hadRecoverableFailure = true,
            recoveredBySplitting = true,
            failureKinds = setOf(ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY),
            allRowsAccepted = true,
        )
        val after = learning.snapshot()
        assertEquals(before.maxRows, after.maxRows)
        assertEquals(before.inputBudgetTokens, after.inputBudgetTokens)
        assertTrue(after.outputBudgetTokens < before.outputBudgetTokens)
    }

    @Test
    fun responseFormatFailureDoesNotReduceRowsWhenTokenBudgetStoppedTheBatch() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.INPUT_BUDGET),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(before.maxRows, learning.snapshot().maxRows)
    }

    @Test
    fun languagePairsKeepIndependentLearningProfiles() {
        val enToZh = ApiSubtitleBatchLearningState(
            "custom-provider/unknown-model",
            learningScope = "en->zh-CN",
        )
        val enToJa = ApiSubtitleBatchLearningState(
            "custom-provider/unknown-model",
            learningScope = "en->ja",
        )
        val beforeJa = enToJa.snapshot()
        repeat(2) {
            enToZh.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(35, enToZh.snapshot().maxRows)
        assertEquals(beforeJa.maxRows, enToJa.snapshot().maxRows)
        assertEquals(beforeJa.inputBudgetTokens, enToJa.snapshot().inputBudgetTokens)
    }

    @Test
    fun usageCalibrationAppliesObservedRatioToTheAlreadyAdjustedEstimate() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordUsage(
            estimatedInputTokens = 1_000,
            estimatedOutputTokens = 1_000,
            actualInputTokens = 1_500,
            actualOutputTokens = 1_500,
            latencyMs = 500,
        )
        assertEquals(1.1, learning.snapshot().inputTokenMultiplier, 0.0001)
        assertEquals(1.1, learning.snapshot().outputTokenMultiplier, 0.0001)
    }

    @Test
    fun rowCapIsReportedOnlyWhenMoreSourceCuesRemain() {
        val model = "custom-provider/unknown-model"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(100) { "Short subtitle" },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
        )
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT in selection.limitingReasons)

        val finalSelection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(10) { "Short subtitle" },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
        )
        assertTrue(ApiSubtitleBatchLimitReason.END_OF_INPUT in finalSelection.limitingReasons)
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT !in finalSelection.limitingReasons)
    }

    @Test
    fun outputBudgetStopIsReportedSeparatelyFromInputAndRowLimits() {
        val model = "GLM-4-Flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(80) { "中".repeat(300) },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
        )
        assertTrue(ApiSubtitleBatchLimitReason.OUTPUT_BUDGET in selection.limitingReasons)
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET !in selection.limitingReasons)
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT !in selection.limitingReasons)
    }

}
