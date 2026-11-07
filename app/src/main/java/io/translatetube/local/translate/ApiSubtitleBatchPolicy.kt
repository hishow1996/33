package io.translatetube.local.translate

import kotlin.math.ceil
import java.util.concurrent.ConcurrentHashMap

internal data class ApiModelCapability(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val initialRows: Int,
    val maxRows: Int,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
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
)

/**
 * Runtime batch sizing for OpenAI-compatible and Gemini APIs.
 * Provider limits are treated separately from context length: a large context window never
 * implies that the same number of tokens can be generated in one response.
 */
internal object ApiSubtitleBatchPolicy {
    private const val UNKNOWN_CONTEXT_TOKENS = 32_768
    private const val UNKNOWN_OUTPUT_TOKENS = 8_192
    private const val DEFAULT_INITIAL_ROWS = 30
    private const val DEFAULT_MAX_ROWS = 60
    private const val PROMPT_OVERHEAD_TOKENS = 1_600
    private const val CONTEXT_RESERVE_TOKENS = 800

    const val EXPANDED_MAX_OUTPUT_TOKENS = 4_096
    private val providerCapabilities = ConcurrentHashMap<String, ApiModelCapability>()

    private fun normalized(model: String): String =
        model.trim().removePrefix("models/").lowercase(java.util.Locale.ROOT)

    private fun providerModelKey(model: String, providerKey: String): String =
        providerKey.trim().lowercase(java.util.Locale.ROOT) + "|" + normalized(model)

    fun capabilityForModel(model: String, providerKey: String = ""): ApiModelCapability =
        providerCapabilities[providerModelKey(model, providerKey)] ?: knownCapabilityForModel(normalized(model))

    private fun knownCapabilityForModel(id: String): ApiModelCapability {
        return when {
            id.contains("qwen2.5-7b-instruct-1m") -> ApiModelCapability(
                1_010_000, 8_192, 80, 160, 48_000, 16_384,
            )
            id.contains("qwen2.5-7b-instruct") -> ApiModelCapability(
                131_072, 8_192, 35, 80, 24_000, 8_192,
            )
            id.contains("glm-4-flash-250414") -> ApiModelCapability(
                131_072, 16_384, 50, 90, 24_000, 8_192,
            )
            id.contains("gemini-2.5-flash") || id.contains("gemini-2.5-pro") -> ApiModelCapability(
                1_048_576, 65_536, 80, 160, 48_000, 16_384,
            )
            id.contains("qwen3.8-max") || id.contains("qwen3.8-flash") -> ApiModelCapability(
                1_000_000, 131_072, 80, 160, 48_000, 16_384,
            )
            id.contains("qwen3.7") || id.contains("qwen3.6") ||
                id.contains("qwen3.5-plus") || id.contains("qwen3.5-flash") -> ApiModelCapability(
                1_000_000, 65_536, 80, 160, 48_000, 16_384,
            )
            id.contains("deepseek-v4") -> ApiModelCapability(
                1_000_000, 384_000, 80, 160, 48_000, 16_384,
            )
            id.contains("glm-5.3") || id.contains("glm-5.2") ||
                id.contains("kimi-k3") || id.contains("glm-4-long") -> ApiModelCapability(
                1_000_000, 8_192, 80, 160, 48_000, 16_384,
            )
            id.contains("1m") -> ApiModelCapability(
                1_000_000, 8_192, 80, 160, 48_000, 16_384,
            )
            id.contains("256k") -> ApiModelCapability(
                262_144, 8_192, 60, 120, 32_000, 12_288,
            )
            id.contains("128k") -> ApiModelCapability(
                131_072, 8_192, 50, 90, 24_000, 8_192,
            )
            else -> ApiModelCapability(
                UNKNOWN_CONTEXT_TOKENS, UNKNOWN_OUTPUT_TOKENS,
                DEFAULT_INITIAL_ROWS, DEFAULT_MAX_ROWS, 8_000, 4_096,
            )
        }
    }

    /**
     * Register limits advertised by a provider's /models catalog. Context, input and output
     * are independent limits; absent fields retain the curated profile or conservative default.
     */
    fun registerProviderLimits(
        model: String,
        contextWindowTokens: Int?,
        maxOutputTokens: Int?,
        maxInputTokens: Int? = null,
        providerKey: String = "",
    ) {
        val id = normalized(model)
        val known = knownCapabilityForModel(id)
        val context = contextWindowTokens?.takeIf { it > 0 } ?: known.contextWindowTokens
        val output = maxOutputTokens?.takeIf { it > 0 } ?: known.maxOutputTokens
        val inputSafetyCap = when {
            context >= 1_000_000 -> 48_000
            context >= 256_000 -> 32_000
            context >= 128_000 -> 24_000
            else -> 8_000
        }
        val outputSafetyCap = when {
            context >= 1_000_000 -> 16_384
            context >= 256_000 -> 12_288
            context >= 128_000 -> 8_192
            else -> 4_096
        }
        val estimatedInputLimit = if (context > 0) context / 5 else inputSafetyCap
        val inputBudget = minOf(
            maxInputTokens?.takeIf { it > 0 } ?: estimatedInputLimit,
            inputSafetyCap,
        ).coerceAtLeast(512)
        val rows = when {
            context >= 1_000_000 -> 80 to 160
            context >= 256_000 -> 60 to 120
            id.contains("glm-4-flash-250414") -> 50 to 90
            id.contains("qwen2.5-7b-instruct") && context <= 131_072 -> 35 to 80
            context >= 128_000 -> 50 to 90
            else -> DEFAULT_INITIAL_ROWS to DEFAULT_MAX_ROWS
        }
        providerCapabilities[providerModelKey(model, providerKey)] = ApiModelCapability(
            contextWindowTokens = context,
            maxOutputTokens = output,
            initialRows = rows.first.coerceAtMost(rows.second),
            maxRows = rows.second,
            inputBudgetTokens = inputBudget,
            outputBudgetTokens = minOf(output, outputSafetyCap),
        )
    }
    fun requiresExpandedOutputBudget(model: String): Boolean {
        val id = normalized(model)
        return id.contains("glm-4-flash-250414") ||
            id.contains("qwen2.5-7b-instruct") ||
            capabilityForModel(id).contextWindowTokens >= 131_072
    }

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

    fun estimateInputTokens(lines: List<String>): Int {
        val subtitleTokens = lines.sumOf { estimateTextTokens(it, "", outputText = false) }
        val numberingTokens = lines.size * 3
        return PROMPT_OVERHEAD_TOKENS + CONTEXT_RESERVE_TOKENS + subtitleTokens + numberingTokens
    }

    /**
     * Select a contiguous subtitle range using row, input-token and output-token budgets.
     * Actual usage from prior successful calls adjusts the estimates in [learning].
     */
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
        val inputBudget = capability.inputBudgetTokens
        val outputBudget = capability.outputBudgetTokens
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
        val outputLimit = outputLimitForModel(model, estimatedOutput, providerKey)
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
    ): Int {
        val capability = capabilityForModel(model, providerKey)
        val requested = ceil(estimatedOutputTokens.coerceAtLeast(1) * 1.6 + 512.0).toInt()
        val floor = minOf(2_048, capability.maxOutputTokens, capability.outputBudgetTokens)
        return requested.coerceAtLeast(floor).coerceAtMost(
            minOf(capability.maxOutputTokens, capability.outputBudgetTokens),
        )
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

/** Per-engine learning: successful batches cautiously grow; length/format failures halve the next cap. */
internal class ApiSubtitleBatchLearningState(model: String, providerKey: String = "") {
    private val capability = ApiSubtitleBatchPolicy.capabilityForModel(model, providerKey)
    private var currentRows = capability.initialRows
    private var consecutiveSuccessfulBatches = 0
    private var inputMultiplier = 1.0
    private var outputMultiplier = 1.0

    @Synchronized
    fun snapshot(): ApiSubtitleBatchLearningSnapshot = ApiSubtitleBatchLearningSnapshot(
        maxRows = currentRows.coerceAtMost(capability.maxRows),
        inputTokenMultiplier = inputMultiplier,
        outputTokenMultiplier = outputMultiplier,
    )

    @Synchronized
    fun adjustedOutputEstimate(lines: List<String>, targetLanguage: String): Int =
        ApiSubtitleBatchPolicy.estimateOutputTokens(lines, targetLanguage, outputMultiplier)

    @Synchronized
    fun recordSuccess(
        batchRows: Int,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        actualInputTokens: Int?,
        actualOutputTokens: Int?,
        maxOutputTokens: Int,
        finishReason: String?,
    ) {
        if (actualInputTokens != null && actualInputTokens > 0 && estimatedInputTokens > 0) {
            val ratio = (actualInputTokens.toDouble() / estimatedInputTokens).coerceIn(0.75, 3.0)
            inputMultiplier = (inputMultiplier * 0.7 + ratio * 0.3).coerceIn(0.75, 3.0)
        }
        if (actualOutputTokens != null && actualOutputTokens > 0 && estimatedOutputTokens > 0) {
            val ratio = (actualOutputTokens.toDouble() / estimatedOutputTokens).coerceIn(0.75, 3.0)
            outputMultiplier = (outputMultiplier * 0.7 + ratio * 0.3).coerceIn(0.75, 3.0)
        }
        val normalizedFinish = finishReason?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (ChatCompletionParamsPolicy.isLengthTruncation(normalizedFinish) ||
            (actualOutputTokens != null && maxOutputTokens > 0 && actualOutputTokens >= maxOutputTokens * 0.9)
        ) {
            recordFailureLocked(batchRows)
            return
        }
        consecutiveSuccessfulBatches++
        if (consecutiveSuccessfulBatches >= 2 && batchRows >= (currentRows * 0.6).toInt()) {
            val growth = maxOf(2, currentRows / 5)
            currentRows = (currentRows + growth).coerceAtMost(capability.maxRows)
            consecutiveSuccessfulBatches = 0
        }
    }

    @Synchronized
    fun recordFailure(attemptedRows: Int) {
        recordFailureLocked(attemptedRows)
    }

    private fun recordFailureLocked(attemptedRows: Int) {
        val reduced = (attemptedRows / 2).coerceAtLeast(1)
        currentRows = minOf(currentRows, reduced).coerceAtLeast(1)
        consecutiveSuccessfulBatches = 0
    }
}