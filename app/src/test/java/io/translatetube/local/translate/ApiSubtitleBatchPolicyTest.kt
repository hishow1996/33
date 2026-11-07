package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSubtitleBatchPolicyTest {
    private fun snapshot(model: String) = ApiSubtitleBatchLearningState(model).snapshot()

    @Test
    fun thirtyOneAnd1291CuesArePartitionedIntoThirtyRowBatchesAndOneRemainder() {
        fun batchSizes(totalRows: Int): List<Int> {
            val lines = List(totalRows) { "subtitle-$it" }
            val model = "custom-provider/fixed-batch-test"
            val learning = snapshot(model)
            val sizes = mutableListOf<Int>()
            var start = 0
            while (start < lines.size) {
                val selection = ApiSubtitleBatchPolicy.selectBatch(
                    lines = lines,
                    startIndex = start,
                    model = model,
                    targetLanguage = "zh-CN",
                    learning = learning,
                )
                sizes += selection.endExclusive - start
                start = selection.endExclusive
            }
            return sizes
        }

        val rows = FIXED_API_TRANSLATION_BATCH_ROWS
        assertEquals(listOf(rows, 15), batchSizes(rows + 15))
        val largeVideoBatches = batchSizes(1_291)
        val fullBatches = 1_291 / rows
        val remainder = 1_291 % rows
        assertEquals(fullBatches + if (remainder > 0) 1 else 0, largeVideoBatches.size)
        assertEquals(List(fullBatches) { rows } + listOfNotNull(remainder.takeIf { it > 0 }), largeVideoBatches)
    }

    @Test
    fun everyApiModelUsesTheFixedThirtyRowBatch() {
        val lines = List(200) { "A short subtitle." }
        val glm = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "GLM-4-Flash-250414", "zh-CN", snapshot("GLM-4-Flash-250414"),
        )
        val qwen = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN", snapshot("Qwen/Qwen2.5-7B-Instruct"),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, glm.endExclusive)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, qwen.endExclusive)
    }

    @Test
    fun successfulBatchesNeverChangeTheFixedThirtyRowSize() {
        val learning = ApiSubtitleBatchLearningState("GLM-4-Flash-250414")
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)

        repeat(2) {
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }

        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun registeredGlmCatalogLimitsKeepTheFixedRowMetadata() {
        val model = "GLM-4-Flash-250414"
        val provider = "https://zhipu.example/v4"
        val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
            model = model,
            contextWindowTokens = 131_072,
            maxOutputTokens = 16_384,
            providerKey = provider,
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, capability.initialRows)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, capability.maxRows)

        val learning = ApiSubtitleBatchLearningState(model, provider)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun oneMillionTokenModelsKeepTheFixedThirtyRowBatchAndRespectOutputCaps() {
        val lines = List(200) { "A short subtitle." }
        val model = "Qwen/Qwen2.5-7B-Instruct-1M"
        val selection = ApiSubtitleBatchPolicy.selectBatch(lines, 0, model, "zh-CN", snapshot(model))
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, selection.endExclusive)
        assertEquals(1_010_000, ApiSubtitleBatchPolicy.capabilityForModel(model).contextWindowTokens)
        // The output allowance is estimated independently from the fixed row count.
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
    fun longSubtitlesKeepThirtyRowsAndFailTheHardBudgetPreflight() {
        val lines = List(80) { "中".repeat(300) }
        val glm = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "glm-4-flash-250414", "zh-CN", snapshot("glm-4-flash-250414"),
        )
        val qwen = ApiSubtitleBatchPolicy.selectBatch(
            lines, 0, "Qwen/Qwen2.5-7B-Instruct", "zh-CN", snapshot("Qwen/Qwen2.5-7B-Instruct"),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, glm.endExclusive)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, qwen.endExclusive)
    }

    @Test
    fun unknownModelsKeepThirtyRowsAfterCleanOriginalBatches() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun finalOriginalBatchFailureNeverChangesTheFixedRowCount() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun glmResponseFailuresNeverChangeTheFixedRowCount() {
        val learning = ApiSubtitleBatchLearningState("GLM-4-Flash-250414")
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun singleCueRecoveryCannotChangeTheFixedApiBatchSize() {
        val learning = ApiSubtitleBatchLearningState("GLM-4-Flash-250414")
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            hadRecoverableFailure = true,
            recoveredBySplitting = true,
            failureKinds = emptySet(),
            allRowsAccepted = true,
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun actualUsageCalibratesInputAndOutputEstimates() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordUsage(2_000, 1_000, 3_000, 1_500, 750)
        assertTrue(learning.snapshot().inputTokenMultiplier > 1.0)
        assertTrue(learning.snapshot().outputTokenMultiplier > 1.0)
    }

    @Test
    fun extremeUsageOutliersDoNotPoisonTokenCalibration() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordUsage(
            estimatedInputTokens = 1_000,
            estimatedOutputTokens = 1_000,
            actualInputTokens = 10_000,
            actualOutputTokens = 10_000,
            latencyMs = 500,
        )
        val after = learning.snapshot()
        assertEquals(before.inputTokenMultiplier, after.inputTokenMultiplier, 0.0001)
        assertEquals(before.outputTokenMultiplier, after.outputTokenMultiplier, 0.0001)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, discovered.initialRows)
        assertEquals(48_000, discovered.inputBudgetTokens)
        assertEquals(32_768, other.contextWindowTokens)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, other.initialRows)
    }

    @Test
    fun unknownNamesContainingOneMillionMarkerAreNotTrustedWithoutMetadata() {
        val capability = ApiSubtitleBatchPolicy.capabilityForModel("vendor/custom-1m-edition")
        assertEquals(32_768, capability.contextWindowTokens)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, capability.initialRows)
    }

    @Test
    fun knownModelLimitsRequireARecognizedBasenamePrefix() {
        val official = ApiSubtitleBatchPolicy.capabilityForModel(
            "deepseek-ai/DeepSeek-V4-Pro-2026-11",
        )
        val spoofed = ApiSubtitleBatchPolicy.capabilityForModel(
            "relay/custom-deepseek-v4-pro",
        )
        val genericMarker = ApiSubtitleBatchPolicy.capabilityForModel(
            "vendor/custom-128k-edition",
        )
        assertEquals(1_048_576, official.contextWindowTokens)
        assertEquals(32_768, spoofed.contextWindowTokens)
        assertEquals(32_768, genericMarker.contextWindowTokens)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, genericMarker.initialRows)
    }

    @Test
    fun providerCatalogLimitsOverrideBuiltInModelFamilyDefaults() {
        val model = "vendor/deepseek-v4-pro"
        val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
            model = model,
            contextWindowTokens = 64_000,
            maxOutputTokens = 4_096,
            maxInputTokens = 48_000,
            providerKey = "https://catalog.example/v1",
        )
        val resolved = ApiSubtitleBatchPolicy.capabilityForModel(
            model,
            "https://catalog.example/v1",
        )
        assertEquals(64_000, resolved.contextWindowTokens)
        assertEquals(4_096, resolved.maxOutputTokens)
        assertEquals(48_000, resolved.maxInputTokens ?: 0)
        assertEquals(48_000, resolved.maxInputBudgetTokens)
        assertEquals(4_096, resolved.maxOutputBudgetTokens)
        assertEquals(capability, resolved)
    }

    @Test
    fun conflictingCatalogLimitsStayInsideTheCombinedContextWindow() {
        val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
            model = "vendor/custom-model",
            contextWindowTokens = 64_000,
            maxOutputTokens = 80_000,
            maxInputTokens = 60_000,
            providerKey = "https://catalog-conflict.example/v1",
        )
        assertTrue(
            capability.maxInputBudgetTokens +
                capability.maxOutputBudgetTokens + 800 <= capability.contextWindowTokens,
        )
        assertTrue(capability.maxOutputBudgetTokens < capability.maxOutputTokens)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, capability.initialRows)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, capability.maxRows)
    }

    @Test
    fun knownKimiAndGpt6ModelsDoNotFallBackToUnknownModelLimits() {
        val kimi = ApiSubtitleBatchPolicy.capabilityForModel("kimi-k3.1-pro")
        val gpt6 = ApiSubtitleBatchPolicy.capabilityForModel("gpt-6-astra")
        assertEquals(1_048_576, kimi.contextWindowTokens)
        assertEquals(1_050_000, gpt6.contextWindowTokens)
        assertEquals(128_000, gpt6.maxOutputTokens)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, kimi.initialRows)
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, gpt6.initialRows)
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
    fun slowSuccessfulOriginalBatchesCannotGrowTheFixedRowLimit() {
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun fullPromptEstimatorDoesNotChangeTheFixedRowCount() {
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, selection.endExclusive)
        assertEquals(18_000 + FIXED_API_TRANSLATION_BATCH_ROWS * 300, selection.estimatedInputTokens)
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET in selection.limitingReasons)
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT in selection.limitingReasons)
    }

    @Test
    fun aFixedBatchThatExceedsInputBudgetIsMarkedBeforeDispatch() {
        val model = "glm-4-flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(30) { "a very long subtitle $it" },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model),
            inputTokenEstimator = { 100_000 },
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, selection.endExclusive)
        assertTrue(selection.batchExceedsInputBudget)
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET in selection.limitingReasons)
    }

    @Test
    fun aFixedBatchThatExceedsOutputBudgetIsMarkedBeforeDispatch() {
        val model = "glm-4-flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(30) { "中".repeat(2_000) },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model).copy(outputBudgetTokens = 512),
        )
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, selection.endExclusive)
        assertTrue(selection.batchExceedsOutputBudget)
        assertTrue(ApiSubtitleBatchLimitReason.OUTPUT_BUDGET in selection.limitingReasons)
    }

    @Test
    fun preflightReportsBothInputAndOutputBudgetOverrunsForTheSameFixedBatch() {
        val model = "glm-4-flash-250414"
        val selection = ApiSubtitleBatchPolicy.selectBatch(
            lines = List(30) { "中".repeat(2_000) },
            startIndex = 0,
            model = model,
            targetLanguage = "zh-CN",
            learning = snapshot(model).copy(
                inputBudgetTokens = 512,
                outputBudgetTokens = 512,
            ),
            inputTokenEstimator = { 100_000 },
        )

        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, selection.endExclusive)
        assertTrue(selection.batchExceedsInputBudget)
        assertTrue(selection.batchExceedsOutputBudget)
        assertTrue(ApiSubtitleBatchLimitReason.INPUT_BUDGET in selection.limitingReasons)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
    }

    @Test
    fun formatFailureDoesNotChangeFixedRowsOrTokenBudgets() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        val before = learning.snapshot()
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = setOf(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT),
        )
        val after = learning.snapshot()
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, after.maxRows)
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
        assertEquals(1_000_000, capability.maxInputBudgetTokens)
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
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, enToZh.snapshot().maxRows)
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
    fun outputBudgetFailureDoesNotChangeTheFixedRowCount() {
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
        assertTrue(ApiSubtitleBatchLimitReason.ROW_LIMIT in selection.limitingReasons)
    }

    @Test
    fun performanceMetricsTrackSuccessRateFailuresAndSplitRecoveries() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            hadRecoverableFailure = false,
            recoveredBySplitting = false,
            failureKinds = emptySet(),
            allRowsAccepted = true,
        )
        learning.recordOriginalBatchSuccess(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            hadRecoverableFailure = true,
            recoveredBySplitting = true,
            failureKinds = emptySet(),
            allRowsAccepted = true,
        )
        learning.recordOriginalBatchFailure(
            limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
            failureKinds = emptySet(),
        )
        val performance = learning.performanceSnapshot()
        assertEquals(3, performance.sampledBatchCount)
        assertEquals(2, performance.successfulBatchCount)
        assertEquals(66.6667, performance.successRatePercent, 0.001)
        assertEquals(1, performance.recoveredBatchCount)
        assertEquals(1, performance.splitRecoveryCount)
        assertEquals(1, performance.failedBatchCount)
    }

    @Test
    fun lowSuccessRateBlocksGrowthEvenWhenThroughputSamplesAreSparse() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(6) {
            learning.recordOriginalBatchFailure(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                failureKinds = emptySet(),
            )
        }
        repeat(2) {
            learning.recordUsage(1_000, 500, 1_000, 500, 10_000, batchRows = 10)
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, learning.snapshot().maxRows)
        assertEquals(25.0, learning.performanceSnapshot().successRatePercent, 0.001)
    }

    @Test
    fun repeatedSplitRecoveriesBlockFurtherBatchGrowth() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(8) {
            learning.recordUsage(1_000, 500, 1_000, 500, 10_000, batchRows = 10)
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = true,
                recoveredBySplitting = true,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        val beforeCleanBatches = learning.snapshot()
        repeat(2) {
            learning.recordUsage(1_000, 500, 1_000, 500, 10_000, batchRows = 10)
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(beforeCleanBatches.maxRows, learning.snapshot().maxRows)
        assertEquals(8, learning.performanceSnapshot().splitRecoveryCount)
    }

    @Test
    fun throughputRegressionPausesBatchGrowthWithoutShrinkingKnownCapacity() {
        val learning = ApiSubtitleBatchLearningState("custom-provider/unknown-model")
        repeat(8) {
            learning.recordUsage(1_000, 500, 1_000, 500, 12_000, batchRows = 20)
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        val beforeSlowWindow = learning.snapshot().maxRows
        assertEquals(FIXED_API_TRANSLATION_BATCH_ROWS, beforeSlowWindow)
        repeat(2) {
            learning.recordUsage(1_000, 500, 1_000, 500, 120_000, batchRows = 20)
            learning.recordOriginalBatchSuccess(
                limitingReasons = setOf(ApiSubtitleBatchLimitReason.ROW_LIMIT),
                hadRecoverableFailure = false,
                recoveredBySplitting = false,
                failureKinds = emptySet(),
                allRowsAccepted = true,
            )
        }
        assertEquals(beforeSlowWindow, learning.snapshot().maxRows)
    }

    @Test
    fun performanceMetricsTrackP90LatencyAndRowsPerMinute() {
        val learning = ApiSubtitleBatchLearningState(
            "custom-provider/unknown-model",
            learningScope = "en->zh",
        )
        learning.recordUsage(
            estimatedInputTokens = 1_000,
            estimatedOutputTokens = 500,
            actualInputTokens = 1_000,
            actualOutputTokens = 500,
            latencyMs = 1_000,
            batchRows = 20,
        )
        learning.recordUsage(
            estimatedInputTokens = 1_000,
            estimatedOutputTokens = 500,
            actualInputTokens = 1_000,
            actualOutputTokens = 500,
            latencyMs = 2_000,
            batchRows = 10,
        )
        val performance = learning.performanceSnapshot()
        assertEquals(2_000L, performance.p90LatencyMs)
        assertEquals(750.0, performance.averageRowsPerMinute, 0.0001)
        assertTrue(performance.averageLatencyMs > 0L)
    }

}
