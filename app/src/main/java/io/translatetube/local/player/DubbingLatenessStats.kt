package io.translatetube.local.player

/**
 * 配音"落后多少"的统计（纯逻辑，只用于日志，不影响任何播放行为）。
 *
 * 每句配音挂到总线时记一笔：实际开口时间比期望开口时间晚了多少，以及晚的原因——
 * - 合成/准备太慢：句子挂上来时播放头已经过了它的期望时间（[Summary.preparedLateCues]）；
 * - 被前一句顶后：句子准备得及时，但上一句还没说完，排在后面（[Summary.queuedBehindCues]）。
 * 两种原因的调法完全不同（前者该加快合成/加大预读，后者该合并或压缩），所以分开数。
 * 攒够 [reportEvery] 句出一份汇总，播放结束时再把不足一批的补出来。
 */
internal class DubbingLatenessStats(private val reportEvery: Int = DEFAULT_REPORT_EVERY) {
    data class Summary(
        val cues: Int,
        val delayedCues: Int,
        val avgDelayMs: Long,
        val maxDelayMs: Long,
        val preparedLateCues: Int,
        val queuedBehindCues: Int,
        val compressedCues: Int,
        val minCatchUpRatio: Float,
        val longestDelayedStreak: Int,
    )

    private var cues = 0
    private var delayedCues = 0
    private var totalDelayUs = 0L
    private var maxDelayUs = 0L
    private var preparedLateCues = 0
    private var queuedBehindCues = 0
    private var compressedCues = 0
    private var minCatchUpRatio = 1f
    private var currentStreak = 0
    private var longestStreak = 0

    /** @return 攒够一批时返回汇总并开始下一批；否则 null。开口时间为负（被拒绝）的句子不计。 */
    @Synchronized
    fun record(desiredUs: Long, playheadUs: Long, startedAtUs: Long, catchUpRatio: Float): Summary? {
        if (startedAtUs < 0L) return null
        val delayUs = (startedAtUs - desiredUs).coerceAtLeast(0L)
        val preparedLateUs = (playheadUs - desiredUs).coerceAtLeast(0L)
        val queuedBehindUs = (startedAtUs - maxOf(desiredUs, playheadUs)).coerceAtLeast(0L)

        cues++
        totalDelayUs += delayUs
        if (delayUs > maxDelayUs) maxDelayUs = delayUs
        if (delayUs > DELAYED_THRESHOLD_US) {
            delayedCues++
            currentStreak++
            if (currentStreak > longestStreak) longestStreak = currentStreak
            if (preparedLateUs > DELAYED_THRESHOLD_US) preparedLateCues++
            if (queuedBehindUs > DELAYED_THRESHOLD_US) queuedBehindCues++
        } else {
            currentStreak = 0
        }
        if (catchUpRatio.isFinite() && catchUpRatio < 1f) {
            compressedCues++
            if (catchUpRatio < minCatchUpRatio) minCatchUpRatio = catchUpRatio
        }
        return if (cues >= reportEvery.coerceAtLeast(1)) drainLocked() else null
    }

    /** 把不足一批的统计也取出来（播放结束时调用）；没有数据返回 null。 */
    @Synchronized
    fun drain(): Summary? = if (cues > 0) drainLocked() else null

    private fun drainLocked(): Summary {
        val summary = Summary(
            cues = cues,
            delayedCues = delayedCues,
            avgDelayMs = totalDelayUs / cues / 1_000L,
            maxDelayMs = maxDelayUs / 1_000L,
            preparedLateCues = preparedLateCues,
            queuedBehindCues = queuedBehindCues,
            compressedCues = compressedCues,
            minCatchUpRatio = minCatchUpRatio,
            longestDelayedStreak = longestStreak,
        )
        cues = 0
        delayedCues = 0
        totalDelayUs = 0L
        maxDelayUs = 0L
        preparedLateCues = 0
        queuedBehindCues = 0
        compressedCues = 0
        minCatchUpRatio = 1f
        // 连续落后可能跨批：新一批从当前连续长度起算。
        longestStreak = currentStreak
        return summary
    }

    companion object {
        const val DEFAULT_REPORT_EVERY = 25

        /** 与原来"被前一句顶后"日志同一个阈值：150ms 以内人耳基本听不出。 */
        const val DELAYED_THRESHOLD_US = 150_000L
    }
}
