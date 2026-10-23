package io.translatetube.local.player

/**
 * 配音时长拟合规则（纯逻辑，JVM 可测）。
 *
 * 两个阶段，分工明确：
 * 1. 合成阶段 [synthesisRatio]：整块 PCM 比字幕窗口长时才压缩（变速不变调），**绝不拉长**。
 *    够放的句子保持 TTS 自然语速——旧实现会把短句拉长到窗口的 1.5 倍，听起来拖腔、不自然。
 * 2. 挂载阶段 [catchUpRatio]：句子被前一句顶后（排队）之后，若会拖到窗口末尾之后，
 *    再按需额外压缩一点，把时间债还掉，避免延迟越积越多。缓存里的音频不受影响。
 */
internal object DubbingFitPolicy {
    /** 合成阶段的最大压缩倍数（语速加快 1.5 倍）。更快的部分交给 TTS 引擎原生提速。 */
    const val MAX_COMPRESS = 1.5f

    /** 追赶阶段允许的额外压缩下限（0.75 ≈ 在已有基础上再快 1.33 倍）。 */
    const val MIN_CATCH_UP_RATIO = 0.75f

    /** 压缩后留出的余量，避免尾音恰好贴着窗口边缘。 */
    private const val FILL = 0.97

    /** 超出窗口不足 4% 时不处理：变速带来的瑕疵比这点超出更明显。 */
    private const val NEGLIGIBLE = 0.04

    /** 允许整句比窗口末尾晚结束的容忍量。 */
    const val CATCH_UP_TOLERANCE_MS = 400L

    /** 追赶只在要还的时间债/空间足够大时才启动，避免对每句都做微调。 */
    private const val CATCH_UP_MIN_SAVING = 0.05

    /**
     * 返回合成阶段应使用的 WSOLA 比例（≤ 1.0）。1.0 表示不处理。
     * 音频没超出窗口 → 1.0（保持自然语速，绝不拉长）。
     */
    fun synthesisRatio(sampleCount: Int, sampleRate: Int, windowMs: Long): Float {
        if (sampleCount <= 0 || sampleRate <= 0 || windowMs <= 0L) return 1f
        val audioMs = sampleCount.toDouble() * 1_000.0 / sampleRate
        // 够放，或只超出一点点：保持原样（微小的超出由挂载阶段的排队吸收）。
        if (audioMs <= windowMs.toDouble() * (1.0 + NEGLIGIBLE)) return 1f
        val needed = windowMs.toDouble() * FILL / audioMs
        return maxOf(needed, 1.0 / MAX_COMPRESS).toFloat()
    }

    /**
     * 原生提速重合成的目标时长预算：压缩上限 × 窗口。
     * 原始音频超过该预算的 1.15 倍时，WSOLA 单独压缩也放不下，才值得让 TTS 重新提速合成。
     */
    fun nativeRetryBudgetMs(windowMs: Long): Long =
        (windowMs.coerceAtLeast(1L).toDouble() * MAX_COMPRESS).toLong().coerceAtLeast(1L)

    /**
     * 句子实际开口时间 [startUs] 因排队被推迟后，是否需要额外压缩以在 [endLimitUs] 前结束。
     * 返回 ≤ 1.0 的 WSOLA 比例；1.0 表示不需要（或收益太小）。
     * 压缩不低于 [MIN_CATCH_UP_RATIO]：宁可略有延迟也不让语速快到听不清。
     */
    fun catchUpRatio(startUs: Long, durationUs: Long, endLimitUs: Long): Float {
        if (durationUs <= 0L) return 1f
        val allowedUs = endLimitUs - startUs
        if (allowedUs >= durationUs) return 1f
        val ratio = maxOf(allowedUs.toDouble() / durationUs.toDouble(), MIN_CATCH_UP_RATIO.toDouble())
        return if (1.0 - ratio < CATCH_UP_MIN_SAVING) 1f else ratio.toFloat()
    }
}
