package io.translatetube.local.player

/**
 * 配音语速校准（纯逻辑，JVM 可测）。
 *
 * [DubbingChunkPolicy.estimateSpeechMs] 用固定公式预判一段文本要读多久，但不同引擎、不同音色的
 * 真实语速差别很大：偏快的音色会被高估而多合并，偏慢的音色会被低估而少合并，最后都靠
 * WSOLA 硬压缩来补。这里把"实际合成出来的时长 ÷ 公式估算"记成一个系数，下次合并判断乘上它。
 *
 * - 系数默认 1.0：没有任何实测数据时，行为与原来完全一致；
 * - 每条合成结果更新一次（指数平滑），样本太短或异常时忽略，系数限制在 [MIN_SCALE, MAX_SCALE]；
 * - 按引擎的 cacheKey 持久化（见 [DubbingSpeechPaceStore]），下一个视频一开始就能用上。
 */
internal object DubbingSpeechPaceCalibration {
    const val DEFAULT_SCALE = 1.0
    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 2.0

    /** 新样本权重：约 8 条合成后就能基本收敛，又不会被单条异常带偏。 */
    private const val SMOOTHING = 0.25

    /** 估算或实测不到这个时长的短句，固定开销占比太大，不参与校准。 */
    private const val MIN_SAMPLE_MS = 800L

    /** 单个样本允许的比例范围；超出说明这次合成异常（被截断、整段静音等），直接忽略。 */
    private const val MIN_SAMPLE_RATIO = 0.35
    private const val MAX_SAMPLE_RATIO = 3.0

    fun sanitize(scale: Double): Double =
        scale.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: DEFAULT_SCALE

    /**
     * @param current 当前系数
     * @param observedAudioMs 实际合成音频时长（已去掉首尾静音）
     * @param synthesisSpeed 合成时使用的语速；音频时长按它换算回 1 倍语速再比较
     * @param baselineEstimateMs [DubbingChunkPolicy.estimateSpeechMs] 在默认系数、1 倍语速下的估算
     * @return 更新后的系数；样本不可用时原样返回
     */
    fun update(
        current: Double,
        observedAudioMs: Long,
        synthesisSpeed: Float,
        baselineEstimateMs: Long,
    ): Double {
        val safeCurrent = sanitize(current)
        if (observedAudioMs < MIN_SAMPLE_MS || baselineEstimateMs < MIN_SAMPLE_MS) return safeCurrent
        if (!synthesisSpeed.isFinite() || synthesisSpeed <= 0f) return safeCurrent
        val observedAt1xMs = observedAudioMs.toDouble() * synthesisSpeed.toDouble()
        val ratio = observedAt1xMs / baselineEstimateMs.toDouble()
        if (!ratio.isFinite() || ratio !in MIN_SAMPLE_RATIO..MAX_SAMPLE_RATIO) return safeCurrent
        return sanitize(safeCurrent * (1.0 - SMOOTHING) + ratio * SMOOTHING)
    }
}
