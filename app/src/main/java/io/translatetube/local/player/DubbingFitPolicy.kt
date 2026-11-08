package io.translatetube.local.player

/**
 * 配音时长拟合规则（纯逻辑，JVM 可测）。
 *
 * 两个阶段，分工明确：
 * 1. 合成阶段 [synthesisRatio]：整块 PCM 比字幕窗口长时才压缩（变速不变调），**绝不拉长**。
 *    够放的句子保持 TTS 自然语速——旧实现会把短句拉长到窗口的 1.5 倍，听起来拖腔、不自然。
 * 2. 挂载阶段 [catchUpRatio]：句子被前一句顶后（排队）之后，若会拖到窗口末尾之后，
 *    再按需额外压缩一点，把时间债还掉，避免延迟越积越多。缓存里的音频不受影响。
 *    压缩下限由 [adaptiveCatchUpFloor] 给出：按"在约 [PAYOFF_HORIZON_MS] 的语音内还清落后"
 *    算出所需速率，再按句长分配——长句省下的时间多，压得多；短句省不了多少，只轻微加速。
 */
internal object DubbingFitPolicy {
    /** 合成阶段的最大压缩倍数（语速加快 1.5 倍）。更快的部分交给 TTS 引擎原生提速。 */
    const val MAX_COMPRESS = 1.15f

    /** 追赶阶段允许的额外压缩下限（0.75 ≈ 在已有基础上再快 1.33 倍）。没有落后信息时使用。 */
    const val MIN_CATCH_UP_RATIO = 0.8f

    /**
     * 旧的大落后压缩下限。追赶压缩现在由 [adaptiveCatchUpFloor] 动态决定，
     * 此常量仅为兼容保留。
     */
    const val MIN_CATCH_UP_RATIO_LARGE_LAG = 0.65f

    /** 追赶期望在这段语音时长内还清落后（毫秒）：速率 = 1 / (1 - 落后 / 期限)。 */
    const val PAYOFF_HORIZON_MS = 12_000L

    /** 追赶的最高速率（倍）。再快会影响听清。 */
    const val CATCH_UP_MAX_RATE = 1.6f

    /** 不超过这个时长的句子算短句，只分到 [SHORT_CUE_WEIGHT] 的加速份额。 */
    const val SHORT_CUE_MS = 1_200L

    /** 不短于这个时长的句子算长句，分到 [LONG_CUE_WEIGHT] 的加速份额。 */
    const val LONG_CUE_MS = 3_500L
    const val SHORT_CUE_WEIGHT = 0.3f
    const val LONG_CUE_WEIGHT = 1.5f

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
    fun nativeRetryBudgetMs(windowMs: Long): Long {
        if (windowMs <= 0L) return 1L
        return kotlin.math.ceil(
            windowMs.toDouble() * MAX_COMPRESS.toDouble()
        ).toLong().coerceAtLeast(1L)
    }

    /**
     * 追赶压缩比例的下限（≤ 1.0，越小压得越多），由落后时间和句长共同决定：
     * 1. 还债期限：想在约 [PAYOFF_HORIZON_MS] 的语音内还清 [lagUs]，需要的速率 r = 1 / (1 - 落后 / 期限)，
     *    最高 [CATCH_UP_MAX_RATE]。落后 2 秒 ≈ 1.2 倍，落后 4 秒 ≈ 1.5 倍。
     * 2. 按句长分配：加速省下的时间 = 句长 × (1 - 1/速率)，长句省得多。短句（≤ [SHORT_CUE_MS]）
     *    只拿 [SHORT_CUE_WEIGHT] 的份额，长句（≥ [LONG_CUE_MS]）拿 [LONG_CUE_WEIGHT]，中间线性过渡。
     *
     * 没有落后（[lagUs] ≤ 0）或句长无效时返回 1.0（不压缩）。
     */
    fun adaptiveCatchUpFloor(lagUs: Long, durationUs: Long): Float {
        if (lagUs <= 0L || durationUs <= 0L) return 1f
        val horizonUs = PAYOFF_HORIZON_MS.toDouble() * 1_000.0
        val lag = lagUs.toDouble().coerceAtMost(horizonUs * 0.9)
        val maxRate = CATCH_UP_MAX_RATE.toDouble()
        val baseRate = (1.0 / (1.0 - lag / horizonUs)).coerceIn(1.0, maxRate)
        val rate = (1.0 + (baseRate - 1.0) * cueWeight(durationUs.toDouble() / 1_000.0))
            .coerceIn(1.0, maxRate)
        return (1.0 / rate).toFloat()
    }

    private fun cueWeight(durationMs: Double): Double {
        val shortMs = SHORT_CUE_MS.toDouble()
        val longMs = LONG_CUE_MS.toDouble()
        val shortWeight = SHORT_CUE_WEIGHT.toDouble()
        val longWeight = LONG_CUE_WEIGHT.toDouble()
        return when {
            durationMs <= shortMs -> shortWeight
            durationMs >= longMs -> longWeight
            else -> shortWeight + (longWeight - shortWeight) * (durationMs - shortMs) / (longMs - shortMs)
        }
    }

    /**
     * 句子实际开口时间 [startUs] 因排队被推迟后，是否需要额外压缩以在 [endLimitUs] 前结束。
     * 返回 ≤ 1.0 的 WSOLA 比例；1.0 表示不需要（或收益太小）。
     * 有 [scheduledStartUs] 时压缩下限由 [adaptiveCatchUpFloor] 动态决定（长句多压、短句少压）；
     * 没有时使用固定下限 [MIN_CATCH_UP_RATIO]：宁可略有延迟也不让语速快到听不清。
     */
    fun catchUpRatio(
        startUs: Long,
        durationUs: Long,
        endLimitUs: Long,
        scheduledStartUs: Long? = null,
        /** True while a [DubbingLagTracker] says catch-up is under way (hysteresis). */
        catchingUp: Boolean? = null,
    ): Float {
        if (durationUs <= 0L) return 1f
        var floor = MIN_CATCH_UP_RATIO
        // Only real lateness justifies speeding speech up. A merely long sentence overruns its
        // own window instead of being rushed; the next cue is shifted back by the queue.
        if (scheduledStartUs != null) {
            val lagUs = startUs - scheduledStartUs
            val active = catchingUp ?: (lagUs >= DubbingTiming.LAG_TOLERANCE_MS * 1_000L)
            if (!active || lagUs < DubbingTiming.LAG_RESUME_MS * 1_000L) return 1f
            floor = adaptiveCatchUpFloor(lagUs, durationUs)
        }
        val allowedUs = endLimitUs - startUs
        if (allowedUs >= durationUs) return 1f
        val ratio = maxOf(allowedUs.toDouble() / durationUs.toDouble(), floor.toDouble())
        return if (1.0 - ratio < CATCH_UP_MIN_SAVING) 1f else ratio.toFloat()
    }
}
