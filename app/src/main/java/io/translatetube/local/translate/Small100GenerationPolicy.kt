package io.translatetube.local.translate

/**
 * Adaptive output limits for SMaLL-100 inference.
 *
 * The decoder must emit EOS before a result is accepted. The output budget is
 * derived from the encoded source length so longer subtitle cues are not cut
 * off by the old fixed 256-token ceiling.
 */

/** One subtitle exceeds the encoder's input-token limit; sibling rows can still be translated. */
internal class Small100InputTooLongException(
    val actualTokens: Int,
    val maxTokens: Int,
) : IllegalArgumentException(
    "字幕过长：分词后 " + actualTokens + " tokens，模型上限为 " + maxTokens + "。"
)

internal object Small100GenerationPolicy {
    const val MIN_OUTPUT_TOKENS = 512
    const val MAX_OUTPUT_TOKENS = 1024

    /**
     * Reserve a generous output budget for short and long subtitle cues alike.
     * SMaLL-100 is an encoder-decoder model, so output length is not guaranteed
     * to equal source length. Four times the source-token count plus headroom
     * avoids clipping expanded translations while keeping an explicit ceiling.
     */
    fun outputTokenLimit(sourceTokenCount: Int): Int {
        val estimated = sourceTokenCount.coerceAtLeast(1).toLong() * 4L + 128L
        return estimated.coerceIn(MIN_OUTPUT_TOKENS.toLong(), MAX_OUTPUT_TOKENS.toLong()).toInt()
    }

    fun requireComplete(
        reachedEos: Boolean,
        generatedTokenCount: Int,
        outputTokenLimit: Int = MAX_OUTPUT_TOKENS,
    ) {
        if (!reachedEos) {
            throw IllegalStateException(
                "SMaLL-100 译文生成达到 " + outputTokenLimit + " token 输出上限但未正常结束；" +
                    "为避免把截断文本用于字幕和配音，本条翻译按失败处理。"
            )
        }
        if (generatedTokenCount <= 0) {
            throw IllegalStateException("SMaLL-100 未生成有效译文 token。")
        }
    }
}
