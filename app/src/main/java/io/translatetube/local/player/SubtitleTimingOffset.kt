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
}
