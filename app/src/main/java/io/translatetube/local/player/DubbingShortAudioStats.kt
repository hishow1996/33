package io.translatetube.local.player

/**
 * "配音比字幕窗口短很多"的统计（纯逻辑，只用于日志，不影响任何播放行为）。
 *
 * 用来回答"配音为什么比视频原声先说完"：每句配音挂到总线时记一笔——配音音频多长、
 * 字幕窗口多长。音频不到窗口 [SHORT_RATIO] 且差距超过 [MIN_GAP_MS] 的算"明显偏短"，
 * 汇总里给出偏短句数和差距最大的几句（行号、开始时间、原文开头），
 * 方便区分是"窗口本来就偏长/时间不准"还是"译文本身就短"。
 * 攒够 [reportEvery] 句出一份汇总，播放结束时再把不足一批的补出来。
 */
internal class DubbingShortAudioStats(private val reportEvery: Int = DEFAULT_REPORT_EVERY) {
    data class Entry(
        val row: Int,
        val startMs: Long,
        val windowMs: Long,
        val audioMs: Long,
        val text: String,
    ) {
        val gapMs: Long get() = windowMs - audioMs
    }

    data class Summary(
        val cues: Int,
        val shortCues: Int,
        val avgAudioToWindowPercent: Int,
        val worst: List<Entry>,
    )

    private var cues = 0
    private var shortCues = 0
    private var totalAudioMs = 0L
    private var totalWindowMs = 0L
    private val shortEntries = ArrayList<Entry>()

    /** @return 攒够一批时返回汇总并开始下一批；否则 null。窗口或音频时长无效的句子不计。 */
    @Synchronized
    fun record(row: Int, startMs: Long, windowMs: Long, audioMs: Long, text: String): Summary? {
        if (windowMs <= 0L || audioMs < 0L) return null
        cues++
        totalAudioMs += audioMs
        totalWindowMs += windowMs
        if (isShort(windowMs, audioMs)) {
            shortCues++
            shortEntries += Entry(row, startMs, windowMs, audioMs, text)
        }
        return if (cues >= reportEvery.coerceAtLeast(1)) drainLocked() else null
    }

    /** 把不足一批的统计也取出来（播放结束时调用）；没有数据返回 null。 */
    @Synchronized
    fun drain(): Summary? = if (cues > 0) drainLocked() else null

    private fun drainLocked(): Summary {
        val summary = Summary(
            cues = cues,
            shortCues = shortCues,
            avgAudioToWindowPercent = if (totalWindowMs > 0L) {
                (totalAudioMs * 100L / totalWindowMs).toInt()
            } else 0,
            worst = shortEntries.sortedByDescending { it.gapMs }.take(WORST_COUNT),
        )
        cues = 0
        shortCues = 0
        totalAudioMs = 0L
        totalWindowMs = 0L
        shortEntries.clear()
        return summary
    }

    companion object {
        const val DEFAULT_REPORT_EVERY = 40
        const val WORST_COUNT = 10

        /** 音频不到窗口的这个比例，且至少短 [MIN_GAP_MS]，才算"明显偏短"。 */
        const val SHORT_RATIO = 0.6
        const val MIN_GAP_MS = 1_500L

        fun isShort(windowMs: Long, audioMs: Long): Boolean =
            windowMs > 0L && audioMs < windowMs * SHORT_RATIO && windowMs - audioMs >= MIN_GAP_MS
    }
}
