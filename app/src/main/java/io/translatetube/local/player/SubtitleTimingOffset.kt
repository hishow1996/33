package io.translatetube.local.player

import android.content.Context

/**
 * 设置页"字幕/配音时间轴微调"：正数 = 字幕和配音更早出现。
 *
 * 字幕选句（PlaybackService）与配音挂载（DubbingEngine）必须共用同一个读取逻辑和默认值，
 * 否则字幕提前了而配音还停在原始时间，听起来永远慢半拍。
 */
internal object SubtitleTimingOffset {
    const val PREF_NAME = "settings"
    const val PREF_KEY = "subtitle_offset_ms"

    /** ASR 转写时间戳普遍偏晚（首句尤其明显）：默认提前 300ms 对冲系统偏差。 */
    const val DEFAULT_LEAD_MS = 300L

    private const val LIMIT_MS = 2_000L

    fun clamp(rawMs: Long): Long = rawMs.coerceIn(-LIMIT_MS, LIMIT_MS)

    fun read(context: Context): Long = clamp(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_KEY, DEFAULT_LEAD_MS)
    )
}
