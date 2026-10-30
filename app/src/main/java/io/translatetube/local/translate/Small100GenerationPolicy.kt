package io.translatetube.local.translate

/**
 * Adaptive output limits and safe text splitting for SMaLL-100 inference.
 *
 * The decoder must emit EOS before a result is accepted. Longer cues receive a
 * larger output budget, and cues that still exceed the model's limits are
 * split at natural boundaries before being translated again.
 */

/** One subtitle exceeds the encoder's input-token limit. */
internal class Small100InputTooLongException(
    val actualTokens: Int,
    val maxTokens: Int,
) : IllegalArgumentException(
    "字幕过长：分词后 " + actualTokens + " tokens，模型上限为 " + maxTokens + "。"
)

/** A complete model response was returned, but it simply echoed translatable source text. */
internal class Small100QualityException(
    val sourceLanguage: String,
) : IllegalStateException(
    "SMaLL-100 已生成完整结果，但检测到译文仍与原文相同或只包含标点；已尝试拆分恢复。"
)

/** Generation hit its output budget without the decoder emitting EOS. */
internal class Small100OutputLimitException(
    val outputTokenLimit: Int,
    val generatedTokenCount: Int,
) : IllegalStateException(
    "SMaLL-100 译文生成达到 " + outputTokenLimit + " token 输出上限但未正常结束；" +
        "为避免把截断文本用于字幕和配音，本条翻译按失败处理。"
)

internal object Small100GenerationPolicy {
    const val MIN_OUTPUT_TOKENS = 512
    const val MAX_OUTPUT_TOKENS = 1024
    const val MAX_SPLIT_RECOVERY_DEPTH = 8
    const val MIN_TOKENS_FOR_NON_SEMANTIC_SPLIT = 64
    private const val MAX_BOUNDARY_CANDIDATES_TO_SCORE = 8

    /**
     * Reserve a generous output budget for short and long subtitle cues alike.
     * SMaLL-100 is an encoder-decoder model, so output length is not guaranteed
     * to equal source length.
     */
    fun outputTokenLimit(sourceTokenCount: Int): Int {
        val estimated = sourceTokenCount.coerceAtLeast(1).toLong() * 4L + 128L
        return estimated.coerceIn(MIN_OUTPUT_TOKENS.toLong(), MAX_OUTPUT_TOKENS.toLong()).toInt()
    }

    /**
     * Split at sentence punctuation first, then clause punctuation, then spaces.
     * The fallback uses a Unicode code-point boundary so surrogate pairs remain
     * intact even for scripts that do not use spaces.
     *
     * tokenCount must include any model-specific prefix tokens and the tokenizer
     * end token. Only boundaries that keep both halves within the source budget
     * are preferred; otherwise a balanced code-point split is returned and can
     * be recursively refined.
     */
    fun splitForRecovery(
        text: String,
        maxChunkTokens: Int,
        allowWeakBoundaries: Boolean = true,
        tokenCount: (String) -> Int,
    ): Pair<String, String>? {
        if (text.codePointCount(0, text.length) < 2 || text.isBlank()) return null

        data class Candidate(val offset: Int, val priority: Int)
        data class ScoredCandidate(
            val candidate: Candidate,
            val leftTokens: Int,
            val rightTokens: Int,
        )

        val candidates = ArrayList<Candidate>()
        for (index in text.indices) {
            val char = text[index]
            if (Character.isHighSurrogate(char) && index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) continue

            val priority = when (char) {
                '.', '!', '?', '。', '！', '？', ';', '；', '\n', '\r' -> 0
                ',', '，', '、', ':', '：' -> 1
                else -> if (char.isWhitespace() && allowWeakBoundaries) 2 else -1
            }
            if (priority >= 0) candidates += Candidate(index + 1, priority)
        }

        for (priority in 0..2) {
            val nearest = candidates.asSequence()
                .filter { it.priority == priority }
                .sortedBy { kotlin.math.abs(it.offset - text.length / 2) }
                .take(MAX_BOUNDARY_CANDIDATES_TO_SCORE)
            val best = nearest.mapNotNull { candidate ->
                val left = text.substring(0, candidate.offset)
                val right = text.substring(candidate.offset)
                if (left.isBlank() || right.isBlank()) return@mapNotNull null
                val leftTokens = tokenCount(left)
                val rightTokens = tokenCount(right)
                if (leftTokens <= maxChunkTokens && rightTokens <= maxChunkTokens) {
                    ScoredCandidate(candidate, leftTokens, rightTokens)
                } else null
            }.minWithOrNull(
                compareBy<ScoredCandidate> { maxOf(it.leftTokens, it.rightTokens) }
                    .thenBy { kotlin.math.abs(it.leftTokens - it.rightTokens) }
                    .thenBy { kotlin.math.abs(it.candidate.offset - text.length / 2) }
            )
            if (best != null) {
                return text.substring(0, best.candidate.offset) to text.substring(best.candidate.offset)
            }
        }

        if (!allowWeakBoundaries) return null
        var midpoint = text.length / 2
        if (midpoint in 1 until text.length &&
            Character.isLowSurrogate(text[midpoint]) &&
            Character.isHighSurrogate(text[midpoint - 1])
        ) {
            midpoint++
        }
        if (midpoint <= 0 || midpoint >= text.length) return null
        return text.substring(0, midpoint) to text.substring(midpoint)
    }

    fun requireTranslationQuality(
        sourceText: String,
        translatedText: String,
        sourceLanguage: String,
    ) {
        if (SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, translatedText, sourceLanguage)) {
            throw Small100QualityException(sourceLanguage)
        }
    }

    fun requireComplete(
        reachedEos: Boolean,
        generatedTokenCount: Int,
        outputTokenLimit: Int = MAX_OUTPUT_TOKENS,
    ) {
        if (!reachedEos) {
            throw Small100OutputLimitException(outputTokenLimit, generatedTokenCount)
        }
        if (generatedTokenCount <= 0) {
            throw IllegalStateException("SMaLL-100 未生成有效译文 token。")
        }
    }
}
