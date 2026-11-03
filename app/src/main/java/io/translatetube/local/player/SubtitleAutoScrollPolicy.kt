package io.translatetube.local.player

import kotlin.math.roundToInt

/**
 * Pure positioning and timing policy for the shared subtitle list.
 *
 * Ordinary captions stay inside a generous reading band. Captions taller than the viewport
 * move only after leaving it completely, so playback ticks do not pull them away mid-read.
 */
internal object SubtitleAutoScrollPolicy {
    private const val READING_BAND_RATIO = 0.22f
    private const val LONG_CAPTION_RATIO = 0.82f
    private const val LONG_CAPTION_TOP_RATIO = 0.08f
    private const val NORMAL_TARGET_RATIO = 0.44f
    private const val IMMEDIATE_JUMP_LINE_GAP = 4L

    internal data class Decision(
        val shouldScroll: Boolean,
        val scrollOffset: Int,
        val isLongCaption: Boolean,
    )

    fun decide(
        viewportStartOffset: Int,
        viewportEndOffset: Int,
        itemOffset: Int?,
        itemSize: Int?,
    ): Decision {
        val viewportHeight = (viewportEndOffset - viewportStartOffset).coerceAtLeast(1)
        val normalOffset = -(viewportHeight * NORMAL_TARGET_RATIO).roundToInt()
        if (viewportEndOffset <= viewportStartOffset || itemOffset == null || itemSize == null || itemSize <= 0) {
            return Decision(
                shouldScroll = itemOffset == null && viewportEndOffset > viewportStartOffset,
                scrollOffset = normalOffset,
                isLongCaption = false,
            )
        }

        val itemBottom = itemOffset.toLong() + itemSize.toLong()
        val isLongCaption = itemSize >= (viewportHeight * LONG_CAPTION_RATIO).roundToInt().coerceAtLeast(1)
        if (isLongCaption) {
            val isFullyOutside = itemBottom <= viewportStartOffset.toLong() ||
                itemOffset >= viewportEndOffset
            return Decision(
                shouldScroll = isFullyOutside,
                scrollOffset = -(viewportHeight * LONG_CAPTION_TOP_RATIO).roundToInt(),
                isLongCaption = true,
            )
        }

        val topGuard = viewportStartOffset + (viewportHeight * READING_BAND_RATIO).roundToInt()
        val bottomGuard = viewportEndOffset - (viewportHeight * READING_BAND_RATIO).roundToInt()
        return Decision(
            shouldScroll = itemOffset < topGuard || itemBottom > bottomGuard.toLong(),
            scrollOffset = normalOffset,
            isLongCaption = false,
        )
    }

    /**
     * Short cues get a shorter coalescing window so they are not skipped by a fixed debounce.
     * Longer cues retain a little more coalescing to avoid unnecessary scroll animation churn.
     */
    fun coalescingDelayMs(startMs: Long, endMs: Long): Long {
        val durationMs = if (endMs > startMs) endMs - startMs else 0L
        return when {
            durationMs < 350L -> 35L
            durationMs < 800L -> 55L
            durationMs < 1_600L -> 75L
            else -> 90L
        }
    }

    /**
     * Small adjacent cue changes animate; first positioning, large jumps and reverse seeks snap
     * to the destination so the list never chases intermediate cues.
     */
    fun shouldJumpImmediately(
        previousLine: Int,
        currentLine: Int,
        forceImmediate: Boolean = false,
    ): Boolean {
        if (forceImmediate || previousLine == Int.MIN_VALUE) return true
        val gap = currentLine.toLong() - previousLine.toLong()
        return gap >= IMMEDIATE_JUMP_LINE_GAP || gap <= -IMMEDIATE_JUMP_LINE_GAP
    }

    /** Ignore the first emission after manual browsing when playback is still on that same cue. */
    fun shouldSkipImmediateResume(
        wasManuallyBrowsing: Boolean,
        lastManualLine: Int,
        currentLine: Int,
    ): Boolean = wasManuallyBrowsing && currentLine == lastManualLine
}
