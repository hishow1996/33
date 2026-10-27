package io.translatetube.local.translate

/**
 * Output limits for SMaLL-100 inference.
 *
 * Reaching the token budget is not a successful translation unless EOS was generated.
 * Keeping this policy independent makes truncated-output handling regression-testable.
 */

/** One subtitle exceeds the encoder's input-token limit; sibling rows can still be translated. */
internal class Small100InputTooLongException(
    val actualTokens: Int,
    val maxTokens: Int,
) : IllegalArgumentException(
    "字幕过长：分词后 " + actualTokens + " tokens，模型上限为 " + maxTokens + "。"
)

internal object Small100GenerationPolicy {
    const val MAX_OUTPUT_TOKENS = 256

    fun requireComplete(reachedEos: Boolean, generatedTokenCount: Int) {
        if (!reachedEos) {
            throw IllegalStateException(
                "SMaLL-100 译文生成达到 " + MAX_OUTPUT_TOKENS + " token 上限但未正常结束；" +
                    "为避免把截断文本用于字幕和配音，本条翻译按失败处理。"
            )
        }
        if (generatedTokenCount <= 0) {
            throw IllegalStateException("SMaLL-100 未生成有效译文 token。")
        }
    }
}
