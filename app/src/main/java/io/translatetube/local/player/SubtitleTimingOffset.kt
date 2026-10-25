package io.translatetube.local.player

/**
 * 字幕/配音共用的固定提前量（不再提供手动调节）。
 *
 * 转写出来的字幕时间戳普遍比真实说话偏晚（首句尤其明显），统一提前 300ms 对冲这个系统偏差：
 * 字幕选句（PlaybackService）与配音挂载（DubbingEngine）必须使用同一个值，
 * 否则字幕提前了而配音还停在原始时间，听起来永远慢半拍。
 */
internal object SubtitleTimingOffset {
    const val LEAD_MS = 300L

    /**
     * 第一句配音额外多提前的量。第一句的字幕起点来自"从静音/片头音乐里检测到人声"，
     * 偏晚得比后面各句更多，统一的 [LEAD_MS] 补不够，表现为"第一句配音比视频原声慢一点"。
     * 只作用在配音挂载的开口时间上，字幕显示仍用 [LEAD_MS]。
     */
    const val FIRST_CUE_EXTRA_LEAD_MS = 200L

    /** 配音挂载时使用的提前量：第一句用加大的值，其余句子不变。 */
    fun dubbingLeadMs(isFirstSpokenCue: Boolean): Long =
        if (isFirstSpokenCue) LEAD_MS + FIRST_CUE_EXTRA_LEAD_MS else LEAD_MS
}
