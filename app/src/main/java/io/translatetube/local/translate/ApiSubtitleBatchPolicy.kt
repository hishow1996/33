package io.translatetube.local.translate

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

internal data class ApiModelCapability(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val initialRows: Int,
    val maxRows: Int,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val maxInputBudgetTokens: Int = inputBudgetTokens,
    val maxOutputBudgetTokens: Int = outputBudgetTokens,
)

internal data class ApiSubtitleBatchSelection(
    val endExclusive: Int,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val maxOutputTokens: Int,
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

    private fun providerModelKey(model: String, providerKey: String): String =
        providerKey.trim().lowercase(java.util.Locale.ROOT) + "|" + normalized(model)

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
        val context = value.contextWindowTokens.coerceIn(4_096, 2_000_000)
        val output = value.maxOutputTokens.coerceIn(512, 1_000_000)
        val maxInput = value.maxInputBudgetTokens.coerceIn(512, (context - 512).coerceAtLeast(512))
        val maxOutputBudget = value.maxOutputBudgetTokens.coerceIn(512, output)
        val maxRows = value.maxRows.coerceIn(1, 400)
        val initialRows = value.initialRows.coerceIn(1, maxRows)
        return value.copy(
            contextWindowTokens = context,
            maxOutputTokens = output,
            initialRows = initialRows,
            maxRows = maxRows,
            inputBudgetTokens = value.inputBudgetTokens.coerceIn(512, maxInput),
            outputBudgetTokens = value.outputBudgetTokens.coerceIn(512, maxOutputBudget),
            maxInputBudgetTokens = maxInput,
            maxOutputBudgetTokens = maxOutputBudget,
        )
    }

    private fun knownCapabilityForModel(id: String): ApiModelCapability {
        return when {
            id.contains("qwen2.5-7b-instruct-1m") -> ApiModelCapability(
                1_010_000, 8_192, 80, 160, 48_000, 8_192, 240_000, 8_192,
            )
            id.contains("qwen2.5-7b-instruct") -> ApiModelCapability(
                131_072, 8_192, 35, 80, 24_000, 8_192, 64_000, 8_192,
            )
            id.contains("glm-4-flash-250414") -> ApiModelCapability(
                131_072, 16_384, 50, 100, 24_000, 8_192, 64_000, 16_384,
            )
            id.contains("gemini-3.8-flash") ||
                id.contains("gemini-2.5-flash") ||
                id.contains("gemini-2.5-pro") -> ApiModelCapability(
                1_048_576, 65_536, 80, 200, 48_000, 16_384, 240_000, 65_536,
            )
            id.contains("256k") -> ApiModelCapability(
                262_144, 8_192, 60, 150, 32_000, 8_192, 96_000, 24_576,
            )
            id.contains("128k") -> ApiModelCapability(
                131_072, 8_192, 50, 120, 24_000, 8_192, 64_000, 16_384,
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
        val context = contextWindowTokens?.takeIf { it > 0 } ?: known.contextWindowTokens
        val output = maxOutputTokens?.takeIf { it > 0 } ?: known.maxOutputTokens
        val tier = when {
            context >= 1_000_000 -> ModelBudgetTier(80, 200, 48_000, 240_000, 16_384, 65_536)
            context >= 256_000 -> ModelBudgetTier(60, 150, 32_000, 96_000, 8_192, 24_576)
            context >= 128_000 -> ModelBudgetTier(50, 120, 24_000, 64_000, 8_192, 16_384)
            else -> ModelBudgetTier(30, 80, 8_000, 16_000, 4_096, 8_192)
        }
        val rows = when {
            id.contains("glm-4-flash-250414") -> 50 to 100
            id.contains("qwen2.5-7b-instruct") && context < 1_000_000 -> 35 to 80
            else -> tier.initialRows to tier.maxRows
        }
        val contextInputCeiling = (context - output - CONTEXT_RESERVE_TOKENS).coerceAtLeast(512)
        val advertisedInput = maxInputTokens?.takeIf { it > 0 } ?: contextInputCeiling
        val maxInputBudget = minOf(advertisedInput, tier.maxInputBudget, contextInputCeiling).coerceAtLeast(512)
        val initialInputBudget = minOf(tier.initialInputBudget, maxInputBudget).coerceAtLeast(512)
        val maxOutputBudget = minOf(output, tier.maxOutputBudget).coerceAtLeast(512)
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
            )
        )
        registerProviderCapability(model, capability, providerKey)
        return capability
    }

    private data class ModelBudgetTier(
        val initialRows: Int,
        val maxRows: Int,
        val initialInputBudget: Int,
        val maxInputBudget: Int,
        val initialOutputBudget: Int,
        val maxOutputBudget: Int,
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
    ): ApiSubtitleBatchSelection {
        require(startIndex in 0 until lines.size) { "startIndex must point to an existing subtitle cue" }
        val capability = capabilityForModel(model, providerKey)
        val rowLimit = learning.maxRows.coerceIn(1, capability.maxRows)
        val inputBudget = learning.inputBudgetTokens.coerceIn(512, capability.maxInputBudgetTokens)
        val outputBudget = learning.outputBudgetTokens.coerceIn(512, capability.maxOutputBudgetTokens)
        var endExclusive = startIndex
        var estimatedInput = PROMPT_OVERHEAD_TOKENS + CONTEXT_RESERVE_TOKENS
        var estimatedOutput = 0
        while (endExclusive < lines.size && endExclusive - startIndex < rowLimit) {
            val cueInput = estimateTextTokens(lines[endExclusive], "", outputText = false) + 3
            val cueOutput = estimateTextTokens(lines[endExclusive], targetLanguage, outputText = true)
            val adjustedInput = ceil(cueInput * learning.inputTokenMultiplier).toInt()
            val adjustedOutput = ceil(cueOutput * 1.25 * learning.outputTokenMultiplier).toInt()
            if (endExclusive > startIndex &&
                (estimatedInput + adjustedInput > inputBudget || estimatedOutput + adjustedOutput > outputBudget)
            ) break
            estimatedInput += adjustedInput
            estimatedOutput += adjustedOutput
            endExclusive++
        }
        val outputLimit = outputLimitForModel(model, estimatedOutput, providerKey, outputBudget)
        return ApiSubtitleBatchSelection(
            endExclusive = endExclusive,
            estimatedInputTokens = estimatedInput,
            estimatedOutputTokens = estimatedOutput.coerceAtLeast(1),
            maxOutputTokens = outputLimit,
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
) {
    private val capability: ApiModelCapability
    private var currentRows: Int
    private var inputMultiplier: Double
    private var outputMultiplier: Double
    private var currentInputBudgetTokens: Int
    private var currentOutputBudgetTokens: Int
    private var consecutiveSuccessfulBatches = 0
    private var averageLatencyMs = 0.0

    init {
        store?.restoreCapability(model, providerKey)
        capability = ApiSubtitleBatchPolicy.capabilityForModel(model, providerKey)
        val saved = store?.loadLearning(model, capability)
        currentRows = saved?.maxRows ?: capability.initialRows
        inputMultiplier = saved?.inputTokenMultiplier ?: 1.0
        outputMultiplier = saved?.outputTokenMultiplier ?: 1.0
        currentInputBudgetTokens = saved?.inputBudgetTokens ?: capability.inputBudgetTokens
        currentOutputBudgetTokens = saved?.outputBudgetTokens ?: capability.outputBudgetTokens
        averageLatencyMs = saved?.averageLatencyMs ?: 0.0
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
    ) {
        if (actualInputTokens != null && actualInputTokens > 0 && estimatedInputTokens > 0) {
            val ratio = (actualInputTokens.toDouble() / estimatedInputTokens).coerceIn(0.5, 4.0)
            inputMultiplier = (inputMultiplier * 0.7 + ratio * 0.3).coerceIn(0.75, 3.0)
        }
        if (actualOutputTokens != null && actualOutputTokens > 0 && estimatedOutputTokens > 0) {
            val ratio = (actualOutputTokens.toDouble() / estimatedOutputTokens).coerceIn(0.5, 4.0)
            outputMultiplier = (outputMultiplier * 0.7 + ratio * 0.3).coerceIn(0.75, 3.0)
        }
        if (latencyMs > 0) {
            averageLatencyMs = if (averageLatencyMs <= 0.0) latencyMs.toDouble()
            else averageLatencyMs * 0.7 + latencyMs * 0.3
        }
        persist()
    }

    /** Called exactly once after the outer/original subtitle batch has completed. */
    @Synchronized
    fun recordOriginalBatchSuccess(batchRows: Int, recoveredBySplitting: Boolean) {
        if (recoveredBySplitting) {
            consecutiveSuccessfulBatches = 0
            persist()
            return
        }
        consecutiveSuccessfulBatches++
        val notSlow = averageLatencyMs <= 8_000.0
        if (notSlow && consecutiveSuccessfulBatches >= 2 && batchRows >= (currentRows * 0.6).toInt()) {
            currentRows = (currentRows + maxOf(2, currentRows / 5)).coerceAtMost(capability.maxRows)
            currentInputBudgetTokens = minOf(
                capability.maxInputBudgetTokens,
                maxOf(currentInputBudgetTokens + 1, (currentInputBudgetTokens * 1.2).toInt()),
            )
            currentOutputBudgetTokens = minOf(
                capability.maxOutputBudgetTokens,
                maxOf(currentOutputBudgetTokens + 1, (currentOutputBudgetTokens * 1.2).toInt()),
            )
            consecutiveSuccessfulBatches = 0
        }
        persist()
    }

    /** Only the final failure of the original batch reduces the next batch. */
    @Synchronized
    fun recordOriginalBatchFailure(attemptedRows: Int) {
        currentRows = minOf(currentRows, (attemptedRows / 2).coerceAtLeast(1)).coerceAtLeast(1)
        currentInputBudgetTokens = maxOf(
            minOf(capability.inputBudgetTokens, 512),
            (currentInputBudgetTokens * 0.8).toInt(),
        )
        currentOutputBudgetTokens = maxOf(
            minOf(capability.outputBudgetTokens, 512),
            (currentOutputBudgetTokens * 0.8).toInt(),
        )
        consecutiveSuccessfulBatches = 0
        persist()
    }

    private fun persist() {
        store?.saveLearning(
            model = model,
            record = ApiSubtitleBatchLearningRecord(
                maxRows = currentRows,
                inputTokenMultiplier = inputMultiplier,
                outputTokenMultiplier = outputMultiplier,
                inputBudgetTokens = currentInputBudgetTokens,
                outputBudgetTokens = currentOutputBudgetTokens,
                averageLatencyMs = averageLatencyMs,
            ),
        )
    }
}
