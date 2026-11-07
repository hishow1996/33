package io.translatetube.local.translate

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

internal enum class ApiSubtitleBatchFailureKind {
    RESPONSE_FORMAT,
    INPUT_CAPACITY,
    OUTPUT_CAPACITY,
}

/** The constraint that stopped the selector from adding the next subtitle cue. */
internal enum class ApiSubtitleBatchLimitReason {
    ROW_LIMIT,
    INPUT_BUDGET,
    OUTPUT_BUDGET,
    END_OF_INPUT,
}

internal data class ApiModelCapability(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val initialRows: Int,
    val maxRows: Int,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val maxInputBudgetTokens: Int = inputBudgetTokens,
    val maxOutputBudgetTokens: Int = outputBudgetTokens,
    val maxInputTokens: Int? = null,
)

internal data class ApiSubtitleBatchSelection(
    val endExclusive: Int,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val maxOutputTokens: Int,
    val singleCueExceedsInputBudget: Boolean = false,
    val singleCueExceedsOutputBudget: Boolean = false,
    val limitingReasons: Set<ApiSubtitleBatchLimitReason> = emptySet(),
)

internal data class ApiSubtitleBatchLearningSnapshot(
    val maxRows: Int,
    val inputTokenMultiplier: Double,
    val outputTokenMultiplier: Double,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
)

internal data class ApiSubtitleBatchLearningRecord(
    val maxRows: Int,
    val inputTokenMultiplier: Double,
    val outputTokenMultiplier: Double,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val averageLatencyMs: Double = 0.0,
    val latencySamplesMs: List<Long> = emptyList(),
    val rowsPerMinuteSamples: List<Double> = emptyList(),
    val batchOutcomeSamples: List<Int> = emptyList(),
)

internal data class ApiSubtitleBatchPerformanceSnapshot(
    val averageLatencyMs: Long,
    val p90LatencyMs: Long,
    val averageRowsPerMinute: Double,
    val sampledBatchCount: Int = 0,
    val successfulBatchCount: Int = 0,
    val successRatePercent: Double = 0.0,
    val recoveredBatchCount: Int = 0,
    val splitRecoveryCount: Int = 0,
    val failedBatchCount: Int = 0,
)

internal object ApiSubtitleBatchPolicy {
    private const val UNKNOWN_CONTEXT_TOKENS = 32_768
    private const val UNKNOWN_OUTPUT_TOKENS = 8_192
    private const val DEFAULT_INITIAL_ROWS = 30
    private const val DEFAULT_MAX_ROWS = 80
    private const val PROMPT_OVERHEAD_TOKENS = 1_600
    private const val CONTEXT_RESERVE_TOKENS = 800

    private val providerCapabilities = ConcurrentHashMap<String, ApiModelCapability>()

    private fun normalized(model: String): String =
        model.trim().removePrefix("models/").lowercase(java.util.Locale.ROOT)

    /**
     * Canonical endpoint identity shared by in-memory capability lookup and persisted learning.
     * Ignore credentials, query strings, fragments and trailing slashes so harmless URL spelling
     * differences do not create separate profiles; preserve the path because relay routes can
     * apply different model mappings and limits.
     */
    internal fun normalizeProviderKey(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "unknown-provider"
        return runCatching {
            val uri = java.net.URI(value)
            val scheme = uri.scheme.orEmpty().lowercase(java.util.Locale.ROOT)
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            require(scheme.isNotBlank() && host.isNotBlank())
            val port = when {
                uri.port <= 0 -> ""
                scheme == "https" && uri.port == 443 -> ""
                scheme == "http" && uri.port == 80 -> ""
                else -> ":" + uri.port
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            scheme + "://" + host + port + path
        }.getOrElse {
            value.substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .trimEnd('/')
                .lowercase(java.util.Locale.ROOT)
                .ifBlank { "unknown-provider" }
        }
    }

    private fun providerModelKey(model: String, providerKey: String): String =
        normalizeProviderKey(providerKey) + "|" + normalized(model)

    fun capabilityForModel(model: String, providerKey: String = ""): ApiModelCapability =
        providerCapabilities[providerModelKey(model, providerKey)] ?: knownCapabilityForModel(normalized(model))

    fun registerProviderCapability(
        model: String,
        capability: ApiModelCapability,
        providerKey: String = "",
    ) {
        providerCapabilities[providerModelKey(model, providerKey)] = sanitizeCapability(capability)
    }

    private fun sanitizeCapability(value: ApiModelCapability): ApiModelCapability {
        // Zero means the provider did not publish a combined context-window limit.
        val context = value.contextWindowTokens.takeIf { it > 0 }?.coerceIn(4_096, 2_000_000) ?: 0
        val output = value.maxOutputTokens.coerceIn(512, 1_000_000)
        val maxInput = value.maxInputBudgetTokens.coerceIn(512, 2_000_000)
        val advertisedInput = value.maxInputTokens?.takeIf { it > 0 }?.coerceIn(512, 2_000_000)
        val maxOutputBudget = value.maxOutputBudgetTokens.coerceIn(512, output)
        val maxRows = value.maxRows.coerceIn(1, 400)
        val initialRows = value.initialRows.coerceIn(1, maxRows)
        return value.copy(
            contextWindowTokens = context,
            maxOutputTokens = output,
            maxInputTokens = advertisedInput,
            initialRows = initialRows,
            maxRows = maxRows,
            inputBudgetTokens = value.inputBudgetTokens.coerceIn(512, maxInput),
            outputBudgetTokens = value.outputBudgetTokens.coerceIn(512, maxOutputBudget),
            maxInputBudgetTokens = maxInput,
            maxOutputBudgetTokens = maxOutputBudget,
        )
    }

    /**
     * Only trust capabilities for recognized model families. The model basename is used so
     * official publisher prefixes (for example, Qwen/ or deepseek-ai/) remain compatible, while
     * arbitrary names that merely contain a familiar token cannot claim a larger context window.
     * Generic "128k"/"256k" suffixes are deliberately not treated as proof of capacity; a provider
     * catalog must advertise those limits explicitly.
     */
    private fun modelFamilyMatches(id: String, vararg families: String): Boolean {
        val baseName = id.substringAfterLast('/').trim()
        return families.any { name ->
            baseName == name || baseName.startsWith("$name-") || baseName.startsWith("$name:")
        }
    }

    private fun knownCapabilityForModel(id: String): ApiModelCapability {
        return when {
            modelFamilyMatches(id, "deepseek-v4-pro", "deepseek-v4-flash") ->
                ApiModelCapability(
                    1_048_576, 393_216, 80, 200, 48_000, 16_384, 240_000, 65_536,
                )
            modelFamilyMatches(id, "kimi-k3.1-pro") ->
                ApiModelCapability(
                    1_048_576, 65_536, 80, 200, 48_000, 16_384, 240_000, 65_536,
                )
            modelFamilyMatches(id, "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna") ->
                ApiModelCapability(
                    1_050_000, 128_000, 80, 200, 48_000, 16_384, 240_000, 65_536,
                )
            modelFamilyMatches(id, "qwen2.5-7b-instruct-1m") -> ApiModelCapability(
                1_010_000, 8_192, 80, 160, 48_000, 8_192, 240_000, 8_192,
            )
            modelFamilyMatches(id, "qwen2.5-7b-instruct") -> ApiModelCapability(
                131_072, 8_192, 35, 80, 24_000, 8_192, 64_000, 8_192,
            )
            modelFamilyMatches(id, "glm-4-flash-250414") -> ApiModelCapability(
                131_072, 16_384, 50, 100, 24_000, 8_192, 64_000, 16_384,
            )
            modelFamilyMatches(id, "gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.5-pro") ->
                ApiModelCapability(
                    1_048_576, 65_536, 80, 200, 48_000, 16_384, 240_000, 65_536,
                )
            else -> ApiModelCapability(
                UNKNOWN_CONTEXT_TOKENS, UNKNOWN_OUTPUT_TOKENS,
                DEFAULT_INITIAL_ROWS, DEFAULT_MAX_ROWS, 8_000, 4_096, 16_000, 8_192,
            )
        }
    }

    /**
     * Register catalog limits without treating context length as output capacity.
     * The initial budgets stay conservative; successful original batches can grow them gradually.
     */
    fun registerProviderLimits(
        model: String,
        contextWindowTokens: Int?,
        maxOutputTokens: Int?,
        maxInputTokens: Int? = null,
        providerKey: String = "",
    ): ApiModelCapability {
        val id = normalized(model)
        val known = knownCapabilityForModel(id)
        val explicitContext = contextWindowTokens?.takeIf { it > 0 }
        val explicitInput = maxInputTokens?.takeIf { it > 0 }
        // A separate inputTokenLimit is not the same thing as a shared input+output context window.
        val context = explicitContext ?: if (explicitInput == null) known.contextWindowTokens else 0
        val output = maxOutputTokens?.takeIf { it > 0 } ?: known.maxOutputTokens
        val tierBasis = explicitContext ?: explicitInput ?: known.contextWindowTokens
        val tier = when {
            tierBasis >= 1_000_000 -> ModelBudgetTier(80, 200, 48_000, 16_384)
            tierBasis >= 256_000 -> ModelBudgetTier(60, 150, 32_000, 8_192)
            tierBasis >= 128_000 -> ModelBudgetTier(50, 120, 24_000, 8_192)
            else -> ModelBudgetTier(30, 80, 8_000, 4_096)
        }
        val rows = when {
            modelFamilyMatches(id, "glm-4-flash-250414") -> 50 to 100
            modelFamilyMatches(id, "qwen2.5-7b-instruct") && tierBasis < 1_000_000 -> 35 to 80
            else -> tier.initialRows to tier.maxRows
        }
        // Keep startup budgets conservative, but let successful learning grow up to the actual
        // catalog limits instead of an unrelated fixed tier ceiling. When catalog fields conflict,
        // preserve room for input and framing inside a combined context window.
        val combinedContextOutputCeiling = if (context > 0) {
            (context - CONTEXT_RESERVE_TOKENS - 512).coerceAtLeast(512)
        } else {
            output
        }
        val maxOutputBudget = minOf(output, combinedContextOutputCeiling).coerceAtLeast(512)
        val contextInputCeiling = if (context > 0) {
            (context - maxOutputBudget - CONTEXT_RESERVE_TOKENS).coerceAtLeast(512)
        } else {
            2_000_000
        }
        val advertisedInput = explicitInput ?: contextInputCeiling
        val maxInputBudget = minOf(advertisedInput, contextInputCeiling).coerceAtLeast(512)
        val initialInputBudget = minOf(tier.initialInputBudget, maxInputBudget).coerceAtLeast(512)
        val initialOutputBudget = minOf(maxOutputBudget, tier.initialOutputBudget).coerceAtLeast(512)
        val capability = sanitizeCapability(
            ApiModelCapability(
                contextWindowTokens = context,
                maxOutputTokens = output,
                initialRows = rows.first.coerceAtMost(rows.second),
                maxRows = rows.second,
                inputBudgetTokens = initialInputBudget,
                outputBudgetTokens = initialOutputBudget,
                maxInputBudgetTokens = maxInputBudget,
                maxOutputBudgetTokens = maxOutputBudget,
                maxInputTokens = explicitInput,
            )
        )
        registerProviderCapability(model, capability, providerKey)
        return capability
    }

    private data class ModelBudgetTier(
        val initialRows: Int,
        val maxRows: Int,
        val initialInputBudget: Int,
        val initialOutputBudget: Int,
    )

    fun estimateOutputTokens(
        lines: List<String>,
        targetLanguage: String,
        tokenMultiplier: Double = 1.0,
    ): Int {
        if (lines.isEmpty()) return 1
        return lines.sumOf { line ->
            ceil(estimateTextTokens(line, targetLanguage, outputText = true) * 1.25 * tokenMultiplier).toInt()
        }.coerceAtLeast(1)
    }

    /** Estimate the complete serialized prompt, not just the subtitle rows. */
    fun estimatePromptTokens(
        systemPrompt: String,
        userPrompt: String,
        tokenMultiplier: Double = 1.0,
    ): Int {
        val textTokens = estimateTextTokens(systemPrompt, "", outputText = false) +
            estimateTextTokens(userPrompt, "", outputText = false)
        // Message framing, role labels and separators are small but non-zero.
        return ceil((textTokens + 24).coerceAtLeast(1) * tokenMultiplier.coerceIn(0.75, 3.0)).toInt()
    }

    fun estimateInputTokens(lines: List<String>): Int {
        val subtitleTokens = lines.sumOf { estimateTextTokens(it, "", outputText = false) }
        val numberingTokens = lines.size * 3
        return PROMPT_OVERHEAD_TOKENS + CONTEXT_RESERVE_TOKENS + subtitleTokens + numberingTokens
    }

    fun selectBatch(
        lines: List<String>,
        startIndex: Int,
        model: String,
        targetLanguage: String,
        learning: ApiSubtitleBatchLearningSnapshot,
        providerKey: String = "",
        inputTokenEstimator: ((List<String>) -> Int)? = null,
    ): ApiSubtitleBatchSelection {
        require(startIndex in 0 until lines.size) { "startIndex must point to an existing subtitle cue" }
        val capability = capabilityForModel(model, providerKey)
        val rowLimit = learning.maxRows.coerceIn(1, capability.maxRows)
        val inputBudget = learning.inputBudgetTokens.coerceIn(512, capability.maxInputBudgetTokens)
        val outputBudget = learning.outputBudgetTokens.coerceIn(512, capability.maxOutputBudgetTokens)
        var endExclusive = startIndex
        var estimatedInput = PROMPT_OVERHEAD_TOKENS + CONTEXT_RESERVE_TOKENS
        var estimatedOutput = 0
        var singleCueExceedsInputBudget = false
        var singleCueExceedsOutputBudget = false
        var stoppedByInputBudget = false
        var stoppedByOutputBudget = false

        while (endExclusive < lines.size && endExclusive - startIndex < rowLimit) {
            val cueInput = estimateTextTokens(lines[endExclusive], "", outputText = false) + 3
            val cueOutput = estimateTextTokens(lines[endExclusive], targetLanguage, outputText = true)
            val adjustedInput = ceil(cueInput * learning.inputTokenMultiplier).toInt()
            val adjustedOutput = ceil(cueOutput * 1.25 * learning.outputTokenMultiplier).toInt()
            val candidateEnd = endExclusive + 1
            val candidateInput = inputTokenEstimator?.invoke(lines.subList(startIndex, candidateEnd))
                ?: (estimatedInput + adjustedInput)
            val candidateOutput = estimatedOutput + adjustedOutput
            val exceedsInput = candidateInput > inputBudget
            val exceedsOutput = candidateOutput > outputBudget

            if (endExclusive > startIndex && (exceedsInput || exceedsOutput)) {
                stoppedByInputBudget = exceedsInput
                stoppedByOutputBudget = exceedsOutput
                break
            }
            if (endExclusive == startIndex && (exceedsInput || exceedsOutput)) {
                singleCueExceedsInputBudget = exceedsInput
                singleCueExceedsOutputBudget = exceedsOutput
                stoppedByInputBudget = exceedsInput
                stoppedByOutputBudget = exceedsOutput
                estimatedInput = candidateInput
                estimatedOutput = candidateOutput
                endExclusive = candidateEnd
                break
            }
            estimatedInput = candidateInput
            estimatedOutput = candidateOutput
            endExclusive = candidateEnd
        }

        val limitingReasons = linkedSetOf<ApiSubtitleBatchLimitReason>()
        if (endExclusive >= lines.size) {
            limitingReasons += ApiSubtitleBatchLimitReason.END_OF_INPUT
        } else if (endExclusive - startIndex >= rowLimit) {
            limitingReasons += ApiSubtitleBatchLimitReason.ROW_LIMIT
            // A row cap can hide a second, tighter constraint. Check the next cue so learning
            // does not grow token budgets when they would already have stopped this batch.
            val nextInput = inputTokenEstimator?.invoke(lines.subList(startIndex, endExclusive + 1))
                ?: run {
                    val nextCueInput =
                        estimateTextTokens(lines[endExclusive], "", outputText = false) + 3
                    estimatedInput + ceil(nextCueInput * learning.inputTokenMultiplier).toInt()
                }
            val nextCueOutput = ceil(
                estimateTextTokens(lines[endExclusive], targetLanguage, outputText = true) *
                    1.25 * learning.outputTokenMultiplier
            ).toInt()
            if (nextInput > inputBudget) stoppedByInputBudget = true
            if (estimatedOutput + nextCueOutput > outputBudget) stoppedByOutputBudget = true
        }
        if (stoppedByInputBudget) limitingReasons += ApiSubtitleBatchLimitReason.INPUT_BUDGET
        if (stoppedByOutputBudget) limitingReasons += ApiSubtitleBatchLimitReason.OUTPUT_BUDGET

        val outputLimit = outputLimitForModel(model, estimatedOutput, providerKey, outputBudget)
        return ApiSubtitleBatchSelection(
            endExclusive = endExclusive,
            estimatedInputTokens = estimatedInput,
            estimatedOutputTokens = estimatedOutput.coerceAtLeast(1),
            maxOutputTokens = outputLimit,
            singleCueExceedsInputBudget = singleCueExceedsInputBudget,
            singleCueExceedsOutputBudget = singleCueExceedsOutputBudget,
            limitingReasons = limitingReasons,
        )
    }

    fun outputLimitForModel(
        model: String,
        estimatedOutputTokens: Int,
        providerKey: String = "",
        outputBudgetTokens: Int? = null,
    ): Int {
        val capability = capabilityForModel(model, providerKey)
        val budget = (outputBudgetTokens ?: capability.outputBudgetTokens)
            .coerceIn(512, capability.maxOutputBudgetTokens)
        val requested = ceil(estimatedOutputTokens.coerceAtLeast(1) * 1.6 + 512.0).toInt()
        val safeOutputCeiling = minOf(capability.maxOutputTokens, budget)
        val floor = minOf(2_048, safeOutputCeiling)
        return requested.coerceAtLeast(floor).coerceAtMost(safeOutputCeiling)
    }

    private fun estimateTextTokens(text: String, targetLanguage: String, outputText: Boolean): Int {
        if (text.isBlank()) return 1
        var visible = 0
        var cjk = 0
        text.forEach { char ->
            if (!char.isWhitespace()) {
                visible++
                if (char.isCjkCharacter()) cjk++
            }
        }
        if (visible == 0) return 1
        val target = targetLanguage.trim().lowercase(java.util.Locale.ROOT)
        val toChinese = outputText && (target.startsWith("zh") || target.contains("中文"))
        val estimate = if (!outputText || toChinese) {
            cjk.toDouble() + (visible - cjk) / 3.2
        } else {
            visible / 3.2
        }
        return ceil(estimate.coerceAtLeast(1.0)).toInt()
    }

    private fun Char.isCjkCharacter(): Boolean =
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
}

internal class ApiSubtitleBatchLearningState(
    private val model: String,
    providerKey: String = "",
    private val store: ApiSubtitleBatchLearningStore? = null,
    private val learningScope: String = "",
) {
    private val capability: ApiModelCapability
    private var currentRows: Int
    private var inputMultiplier: Double
    private var outputMultiplier: Double
    private var currentInputBudgetTokens: Int
    private var currentOutputBudgetTokens: Int
    private var consecutiveSuccessfulBatches = 0
    private val successfulLimitingReasons = linkedSetOf<ApiSubtitleBatchLimitReason>()
    private var averageLatencyMs = 0.0
    private val recentLatencySamplesMs = mutableListOf<Long>()
    private val recentRowsPerMinuteSamples = mutableListOf<Double>()
    private val recentBatchOutcomeSamples = mutableListOf<Int>()

    init {
        store?.restoreCapability(model, providerKey)
        capability = ApiSubtitleBatchPolicy.capabilityForModel(model, providerKey)
        val saved = store?.loadLearning(model, capability, learningScope)
        currentRows = saved?.maxRows ?: capability.initialRows
        inputMultiplier = saved?.inputTokenMultiplier ?: 1.0
        outputMultiplier = saved?.outputTokenMultiplier ?: 1.0
        currentInputBudgetTokens = saved?.inputBudgetTokens ?: capability.inputBudgetTokens
        currentOutputBudgetTokens = saved?.outputBudgetTokens ?: capability.outputBudgetTokens
        averageLatencyMs = saved?.averageLatencyMs ?: 0.0
        recentLatencySamplesMs.addAll(saved?.latencySamplesMs.orEmpty().takeLast(MAX_PERFORMANCE_SAMPLES))
        recentRowsPerMinuteSamples.addAll(
            saved?.rowsPerMinuteSamples.orEmpty().takeLast(MAX_PERFORMANCE_SAMPLES)
        )
        recentBatchOutcomeSamples.addAll(
            saved?.batchOutcomeSamples.orEmpty()
                .filter { it in OUTCOME_FAILED..OUTCOME_REJECTED }
                .takeLast(MAX_PERFORMANCE_SAMPLES)
        )
    }

    @Synchronized
    fun snapshot(): ApiSubtitleBatchLearningSnapshot = ApiSubtitleBatchLearningSnapshot(
        maxRows = currentRows.coerceIn(1, capability.maxRows),
        inputTokenMultiplier = inputMultiplier.coerceIn(0.75, 3.0),
        outputTokenMultiplier = outputMultiplier.coerceIn(0.75, 3.0),
        inputBudgetTokens = currentInputBudgetTokens.coerceIn(512, capability.maxInputBudgetTokens),
        outputBudgetTokens = currentOutputBudgetTokens.coerceIn(512, capability.maxOutputBudgetTokens),
    )

    @Synchronized
    fun adjustedOutputEstimate(lines: List<String>, targetLanguage: String): Int =
        ApiSubtitleBatchPolicy.estimateOutputTokens(lines, targetLanguage, outputMultiplier)

    @Synchronized
    fun recordUsage(
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        actualInputTokens: Int?,
        actualOutputTokens: Int?,
        latencyMs: Long,
        batchRows: Int = 0,
    ) {
        if (actualInputTokens != null && actualInputTokens > 0 && estimatedInputTokens > 0) {
            inputMultiplier = calibrateMultiplier(
                current = inputMultiplier,
                estimated = estimatedInputTokens,
                actual = actualInputTokens,
            )
        }
        if (actualOutputTokens != null && actualOutputTokens > 0 && estimatedOutputTokens > 0) {
            outputMultiplier = calibrateMultiplier(
                current = outputMultiplier,
                estimated = estimatedOutputTokens,
                actual = actualOutputTokens,
            )
        }
        if (latencyMs > 0) {
            averageLatencyMs = if (averageLatencyMs <= 0.0) latencyMs.toDouble()
            else averageLatencyMs * 0.7 + latencyMs * 0.3
            recentLatencySamplesMs += latencyMs
            while (recentLatencySamplesMs.size > MAX_PERFORMANCE_SAMPLES) {
                recentLatencySamplesMs.removeAt(0)
            }
            if (batchRows > 0) {
                val rowsPerMinute = batchRows * 60_000.0 / latencyMs
                if (rowsPerMinute.isFinite() && rowsPerMinute > 0.0) {
                    recentRowsPerMinuteSamples += rowsPerMinute.coerceAtMost(MAX_ROWS_PER_MINUTE_SAMPLE)
                    while (recentRowsPerMinuteSamples.size > MAX_PERFORMANCE_SAMPLES) {
                        recentRowsPerMinuteSamples.removeAt(0)
                    }
                }
            }
        }
        persist()
    }

    @Synchronized
    fun performanceSnapshot(): ApiSubtitleBatchPerformanceSnapshot {
        val sortedLatencies = recentLatencySamplesMs.sorted()
        val p90Index = if (sortedLatencies.isEmpty()) {
            -1
        } else {
            (ceil(sortedLatencies.size * 0.9).toInt() - 1).coerceIn(0, sortedLatencies.lastIndex)
        }
        val outcomes = recentBatchOutcomeSamples
        val sampled = outcomes.size
        val successful = outcomes.count { it in OUTCOME_CLEAN_SUCCESS..OUTCOME_SPLIT_SUCCESS }
        val recovered = outcomes.count {
            it == OUTCOME_RECOVERED_SUCCESS || it == OUTCOME_SPLIT_SUCCESS
        }
        val splitRecoveries = outcomes.count { it == OUTCOME_SPLIT_SUCCESS }
        val failed = outcomes.count { it == OUTCOME_FAILED || it == OUTCOME_REJECTED }
        return ApiSubtitleBatchPerformanceSnapshot(
            averageLatencyMs = averageLatencyMs.toLong().coerceAtLeast(0L),
            p90LatencyMs = sortedLatencies.getOrNull(p90Index) ?: averageLatencyMs.toLong().coerceAtLeast(0L),
            averageRowsPerMinute = if (recentRowsPerMinuteSamples.isEmpty()) 0.0
            else recentRowsPerMinuteSamples.average(),
            sampledBatchCount = sampled,
            successfulBatchCount = successful,
            successRatePercent = if (sampled == 0) 0.0 else successful * 100.0 / sampled,
            recoveredBatchCount = recovered,
            splitRecoveryCount = splitRecoveries,
            failedBatchCount = failed,
        )
    }

    private fun recordBatchOutcome(outcome: Int) {
        recentBatchOutcomeSamples += outcome
        while (recentBatchOutcomeSamples.size > MAX_PERFORMANCE_SAMPLES) {
            recentBatchOutcomeSamples.removeAt(0)
        }
    }

    /**
     * Avoid expanding a profile whose recent original batches are unstable. Once there are enough
     * observations, require a high accepted-row rate, few split recoveries, and no material
     * throughput regression. Latency alone never reduces provider capacity because network delay
     * is not evidence of a token or context limit.
     */
    private fun performanceAllowsGrowth(): Boolean {
        if (recentBatchOutcomeSamples.size < MIN_GROWTH_PERFORMANCE_SAMPLES ||
            recentRowsPerMinuteSamples.size < MIN_GROWTH_PERFORMANCE_SAMPLES
        ) return true

        val outcomes = recentBatchOutcomeSamples
        val successful = outcomes.count { it in OUTCOME_CLEAN_SUCCESS..OUTCOME_SPLIT_SUCCESS }
        val successRate = successful.toDouble() / outcomes.size
        val splitRate = outcomes.count { it == OUTCOME_SPLIT_SUCCESS }.toDouble() / outcomes.size
        if (successRate < MIN_GROWTH_SUCCESS_RATE || splitRate > MAX_GROWTH_SPLIT_RATE) {
            return false
        }

        val throughput = recentRowsPerMinuteSamples
        val midpoint = throughput.size / 2
        if (midpoint <= 0 || throughput.size - midpoint <= 0) return true
        val earlierAverage = throughput.subList(0, midpoint).average()
        val recentAverage = throughput.subList(midpoint, throughput.size).average()
        return earlierAverage <= 0.0 || recentAverage >= earlierAverage * MIN_THROUGHPUT_RETENTION
    }

    /** Called exactly once after the outer/original subtitle batch has completed. */
    @Synchronized
    fun recordOriginalBatchSuccess(
        limitingReasons: Set<ApiSubtitleBatchLimitReason>,
        hadRecoverableFailure: Boolean,
        recoveredBySplitting: Boolean,
        failureKinds: Set<ApiSubtitleBatchFailureKind>,
        allRowsAccepted: Boolean,
    ) {
        recordBatchOutcome(
            when {
                !allRowsAccepted -> OUTCOME_REJECTED
                recoveredBySplitting -> OUTCOME_SPLIT_SUCCESS
                hadRecoverableFailure -> OUTCOME_RECOVERED_SUCCESS
                else -> OUTCOME_CLEAN_SUCCESS
            }
        )
        if (recoveredBySplitting || hadRecoverableFailure) {
            // Feed recoverable failures back into the matching constraint even when a single
            // cue cannot be split. Only a real split is evidence for reducing the global row cap.
            val adjustments = if (recoveredBySplitting) {
                failureKinds
            } else {
                failureKinds - ApiSubtitleBatchFailureKind.RESPONSE_FORMAT
            }
            applyFailureAdjustments(limitingReasons, adjustments)
            consecutiveSuccessfulBatches = 0
            successfulLimitingReasons.clear()
            persist()
            return
        }
        if (!allRowsAccepted) {
            consecutiveSuccessfulBatches = 0
            successfulLimitingReasons.clear()
            persist()
            return
        }

        consecutiveSuccessfulBatches++
        successfulLimitingReasons += limitingReasons
        if (consecutiveSuccessfulBatches >= 2) {
            val allowGrowth = performanceAllowsGrowth()
            if (allowGrowth &&
                ApiSubtitleBatchLimitReason.ROW_LIMIT in successfulLimitingReasons
            ) {
                currentRows = (currentRows + TRANSLATION_RECOVERY_BATCH_STEP_ROWS)
                    .coerceAtMost(capability.maxRows)
            }
            if (allowGrowth &&
                ApiSubtitleBatchLimitReason.INPUT_BUDGET in successfulLimitingReasons
            ) {
                currentInputBudgetTokens = growBudget(
                    currentInputBudgetTokens,
                    capability.maxInputBudgetTokens,
                )
            }
            if (allowGrowth &&
                ApiSubtitleBatchLimitReason.OUTPUT_BUDGET in successfulLimitingReasons
            ) {
                currentOutputBudgetTokens = growBudget(
                    currentOutputBudgetTokens,
                    capability.maxOutputBudgetTokens,
                )
            }
            consecutiveSuccessfulBatches = 0
            successfulLimitingReasons.clear()
        }
        persist()
    }

    /** Only the final failure of the original batch updates the persisted limits. */
    @Synchronized
    fun recordOriginalBatchFailure(
        limitingReasons: Set<ApiSubtitleBatchLimitReason>,
        failureKinds: Set<ApiSubtitleBatchFailureKind>,
    ) {
        recordBatchOutcome(OUTCOME_FAILED)
        applyFailureAdjustments(limitingReasons, failureKinds)
        consecutiveSuccessfulBatches = 0
        successfulLimitingReasons.clear()
        persist()
    }

    private fun applyFailureAdjustments(
        limitingReasons: Set<ApiSubtitleBatchLimitReason>,
        failureKinds: Set<ApiSubtitleBatchFailureKind>,
    ) {
        val minimumRows = minOf(MIN_TRANSLATION_RECOVERY_BATCH_ROWS, capability.maxRows)
        if (ApiSubtitleBatchFailureKind.RESPONSE_FORMAT in failureKinds &&
            ApiSubtitleBatchLimitReason.ROW_LIMIT in limitingReasons &&
            currentRows > minimumRows
        ) {
            currentRows = (currentRows - TRANSLATION_RECOVERY_BATCH_STEP_ROWS)
                .coerceAtLeast(minimumRows)
        }
        if (ApiSubtitleBatchFailureKind.INPUT_CAPACITY in failureKinds) {
            currentInputBudgetTokens = shrinkBudget(
                currentInputBudgetTokens,
                capability.inputBudgetTokens,
            )
        }
        if (ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY in failureKinds) {
            currentOutputBudgetTokens = shrinkBudget(
                currentOutputBudgetTokens,
                capability.outputBudgetTokens,
            )
        }
    }

    private fun growBudget(current: Int, maximum: Int): Int =
        minOf(maximum, maxOf(current + 1, (current * 1.2).toInt()))

    private fun shrinkBudget(current: Int, initialFloor: Int): Int =
        maxOf(minOf(initialFloor, 512), (current * 0.8).toInt())

    /**
     * Estimates passed to this method already include the current multiplier. Correct the
     * multiplier multiplicatively; blending the raw ratio directly would converge to its square
     * root and systematically under-calibrate after the first adjustment.
     */
    private fun calibrateMultiplier(current: Double, estimated: Int, actual: Int): Double {
        if (estimated <= 0 || actual <= 0) return current
        val rawRatio = actual.toDouble() / estimated
        // Token usage can occasionally be missing, provider-specific, or wildly inconsistent.
        // Ignore extreme observations instead of allowing one bad sample to poison future batches.
        if (!rawRatio.isFinite() || rawRatio !in MIN_CALIBRATION_RATIO..MAX_CALIBRATION_RATIO) {
            return current
        }
        val observedRatio = rawRatio.coerceIn(0.75, 1.5)
        val correctedTarget = current * observedRatio
        return (current * 0.8 + correctedTarget * 0.2).coerceIn(0.75, 3.0)
    }

    private fun persist() {
        store?.saveLearning(
            model = model,
            learningScope = learningScope,
            record = ApiSubtitleBatchLearningRecord(
                maxRows = currentRows,
                inputTokenMultiplier = inputMultiplier,
                outputTokenMultiplier = outputMultiplier,
                inputBudgetTokens = currentInputBudgetTokens,
                outputBudgetTokens = currentOutputBudgetTokens,
                averageLatencyMs = averageLatencyMs,
                latencySamplesMs = recentLatencySamplesMs.toList(),
                rowsPerMinuteSamples = recentRowsPerMinuteSamples.toList(),
                batchOutcomeSamples = recentBatchOutcomeSamples.toList(),
            ),
        )
    }

    private companion object {
        const val MAX_PERFORMANCE_SAMPLES = 24
        const val MAX_ROWS_PER_MINUTE_SAMPLE = 1_000_000.0
        const val MIN_GROWTH_PERFORMANCE_SAMPLES = 8
        const val MIN_GROWTH_SUCCESS_RATE = 0.90
        const val MAX_GROWTH_SPLIT_RATE = 0.125
        const val MIN_THROUGHPUT_RETENTION = 0.85
        const val MIN_CALIBRATION_RATIO = 0.5
        const val MAX_CALIBRATION_RATIO = 2.0
        const val OUTCOME_FAILED = 0
        const val OUTCOME_CLEAN_SUCCESS = 1
        const val OUTCOME_RECOVERED_SUCCESS = 2
        const val OUTCOME_SPLIT_SUCCESS = 3
        const val OUTCOME_REJECTED = 4
    }
}
