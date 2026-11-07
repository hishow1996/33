package io.translatetube.local.translate

/** 发给 chat/completions 的采样与长度参数。temperature 为 null 表示不传。 */
internal data class ChatSamplingParams(
    val temperature: Double?,
    val maxTokens: Int,
    val useMaxCompletionTokens: Boolean,
)

/**
 * 按模型能力、当前批次的预计译文长度和推理特征决定请求参数。
 * 输出上限不会超过已知模型规格；未知模型采用保守预算，推理模型保留至少 8192 tokens 的历史额度。
 * OpenAI 的 o 系列/gpt-5 不接受自定义 temperature，一律不传；
 *   OpenAI 官方接口还要求用 max_completion_tokens 代替 max_tokens，其他网关保持 max_tokens 以免不认识新参数。
 */
internal object ChatCompletionParamsPolicy {
    const val NORMAL_MAX_TOKENS = 2048
    const val REASONING_MAX_TOKENS = 8192
    private const val NORMAL_TEMPERATURE = 0.2

    private val openAiReasoningRegex = Regex("""(^|[-_/])(o1|o3|o4)([-_/]|$)""")
    private val thinkingWordRegex = Regex("""(^|[-_/])thinking($|[-_/])""")

    /** 关不掉思考的模型家族（传 normalized：小写）。 */
    fun isThinkingOnlyModel(normalizedModel: String): Boolean =
        normalizedModel.contains("qwq") ||
            normalizedModel.contains("deepseek-r1") ||
            normalizedModel.contains("deepseek-reasoner") ||
            thinkingWordRegex.containsMatchIn(normalizedModel)

    /** OpenAI 的推理模型：o1/o3/o4 与 gpt-5（含 gpt-5.x）。 */
    fun isOpenAiReasoningModel(normalizedModel: String): Boolean =
        openAiReasoningRegex.containsMatchIn(normalizedModel) ||
            normalizedModel.contains("gpt-5") || normalizedModel.contains("gpt5")

    /** finish_reason 表示"输出额度用完被截断"（不同服务商写法不同）。 */
    fun isLengthTruncation(finishReason: String?): Boolean =
        finishReason?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("length", "max_tokens", "max_output_tokens")

    fun forModel(
        baseUrl: String,
        model: String,
        estimatedOutputTokens: Int = 0,
        outputBudgetTokens: Int? = null,
    ): ChatSamplingParams {
        val normalizedModel = model.trim().lowercase(java.util.Locale.ROOT)
        val normalizedBase = baseUrl.trim().lowercase(java.util.Locale.ROOT)
        val openAiReasoning = isOpenAiReasoningModel(normalizedModel)
        val thinkingOnly = isThinkingOnlyModel(normalizedModel)
        val officialOpenAi = normalizedBase.contains("api.openai.com") ||
            normalizedBase.contains(".openai.azure.com")
        val capability = ApiSubtitleBatchPolicy.capabilityForModel(normalizedModel, baseUrl)
        val dynamicLimit = ApiSubtitleBatchPolicy.outputLimitForModel(
            normalizedModel,
            estimatedOutputTokens.coerceAtLeast(1),
            baseUrl,
            outputBudgetTokens,
        )
        val isReasoningModel = openAiReasoning || thinkingOnly
        val reasoningFloor = if (isReasoningModel) REASONING_MAX_TOKENS else 0
        // Preserve the previous 8K reasoning allowance when a compatible gateway does not
        // publish its output limit; known model caps still constrain the requested output.
        val maximumOutput = if (isReasoningModel) {
            maxOf(capability.maxOutputTokens, REASONING_MAX_TOKENS)
        } else {
            capability.maxOutputTokens
        }
        return ChatSamplingParams(
            temperature = if (openAiReasoning) null else NORMAL_TEMPERATURE,
            maxTokens = maxOf(dynamicLimit, reasoningFloor)
                .coerceAtMost(maximumOutput),
            useMaxCompletionTokens = openAiReasoning && officialOpenAi,
        )
    }
}
