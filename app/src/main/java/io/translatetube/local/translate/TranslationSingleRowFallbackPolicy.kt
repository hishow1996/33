package io.translatetube.local.translate

/**
 * 批量重试仍有缺失行时的最后一道兜底：把缺失行一行一行单独请求。
 * 单行请求没有"相邻行"可供模型合并，能避开碎句合并导致的空返回。
 */
internal object TranslationSingleRowFallbackPolicy {
    /** 缺失行超过这个数量，说明是整体性失败（模型/服务问题），不再逐行补请求，沿用原有报错。 */
    const val MAX_ROWS = 12

    /** 每一行最多单独请求的次数。 */
    const val MAX_ATTEMPTS_PER_ROW = 2

    /**
     * 被翻译服务商内容安全过滤拒绝的行不可能靠重试成功，出现时直接沿用原有报错，
     * 以便报错里仍然准确指出是安全过滤。
     */
    fun shouldRun(unresolvedCount: Int, contentFilteredCount: Int): Boolean =
        unresolvedCount in 1..MAX_ROWS && contentFilteredCount == 0
}
