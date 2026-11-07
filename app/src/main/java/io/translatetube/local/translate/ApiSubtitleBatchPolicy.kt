package io.translatetube.local.translate

import kotlin.math.ceil

internal data class ApiSubtitleBatchSelection(
    val endExclusive: Int,
    val estimatedOutputTokens: Int,
)

/** Select larger API batches for two known models, shrinking only when subtitle text is long. */
internal object ApiSubtitleBatchPolicy {
    private const val DEFAULT_BATCH_ROWS = 30
    private const val GLM_BATCH_ROWS = 50
    private const val QWEN_BATCH_ROWS = 35
    private const val GLM_ESTIMATED_OUTPUT_BUDGET = 3_200
    private const val QWEN_ESTIMATED_OUTPUT_BUDGET = 2_400
    const val EXPANDED_MAX_OUTPUT_TOKENS = 4_096

    private fun normalized(model: String) = model.trim().lowercase(java.util.Locale.ROOT)

    fun requiresExpandedOutputBudget(model: String): Boolean {
        val value = normalized(model)
        return value.contains("glm-4-flash-250414") || value.contains("qwen2.5-7b-instruct")
    }

    private fun rowLimit(model: String): Int {
        val value = normalized(model)
        return when {
            value.contains("glm-4-flash-250414") -> GLM_BATCH_ROWS
            value.contains("qwen2.5-7b-instruct") -> QWEN_BATCH_ROWS
            else -> DEFAULT_BATCH_ROWS
        }
    }

    private fun outputEstimateBudget(model: String): Int? {
        val value = normalized(model)
        return when {
            value.contains("glm-4-flash-250414") -> GLM_ESTIMATED_OUTPUT_BUDGET
            value.contains("qwen2.5-7b-instruct") -> QWEN_ESTIMATED_OUTPUT_BUDGET
            else -> null
        }
    }

    /** Always returns at least one row, so a single unusually long cue cannot stall progress. */
    fun selectBatch(
        lines: List<String>,
        startIndex: Int,
        model: String,
        targetLanguage: String,
    ): ApiSubtitleBatchSelection {
        require(startIndex in 0 until lines.size) { "startIndex must point to an existing subtitle cue" }
        val maxRows = rowLimit(model)
        val tokenBudget = outputEstimateBudget(model)
        var endExclusive = startIndex
        var estimatedTokens = 0
        while (endExclusive < lines.size && endExclusive - startIndex < maxRows) {
            val cueTokens = estimateOutputTokens(lines[endExclusive], targetLanguage)
            if (tokenBudget != null && endExclusive > startIndex && estimatedTokens + cueTokens > tokenBudget) break
            estimatedTokens += cueTokens
            endExclusive++
        }
        return ApiSubtitleBatchSelection(endExclusive, estimatedTokens)
    }

    /** Conservative token estimate with a 25% allowance for translation expansion. */
    private fun estimateOutputTokens(text: String, targetLanguage: String): Int {
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
        val toChinese = target.startsWith("zh") || target.contains("中文")
        val base = if (toChinese) cjk.toDouble() + (visible - cjk) / 3.2 else visible / 3.2
        return ceil((base * 1.25).coerceAtLeast(1.0)).toInt()
    }

    private fun Char.isCjkCharacter(): Boolean =
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
}