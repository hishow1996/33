package io.translatetube.local.floating

/** Keeps a draggable/resizable overlay inside the usable display bounds. */
internal object FloatingWindowPolicy {
    fun boundedDimension(requested: Int, minimum: Int, available: Int): Int {
        val maximum = available.coerceAtLeast(1)
        val lowerBound = minimum.coerceAtLeast(1).coerceAtMost(maximum)
        return requested.coerceIn(lowerBound, maximum)
    }

    fun boundedOffset(requested: Int, windowSize: Int, available: Int): Int {
        val maximumOffset = (available.coerceAtLeast(0) - windowSize.coerceAtLeast(0))
            .coerceAtLeast(0)
        return requested.coerceIn(0, maximumOffset)
    }

    /**
     * 拖动：窗口位置 = 按下时的窗口位置 + 手指在屏幕上的位移（屏幕绝对坐标）。
     * 只依赖"按下时的锚点"和"手指当前的屏幕位置"，不依赖窗口此刻在哪，
     * 所以不管系统多久才把上一次的位置更新生效，同一个手指位置永远算出同一个窗口位置，不会来回跳。
     */
    fun draggedOffset(
        anchorOffset: Int,
        anchorPointer: Float,
        currentPointer: Float,
        windowSize: Int,
        available: Int,
    ): Int = boundedOffset(
        anchorOffset + Math.round(currentPointer - anchorPointer),
        windowSize,
        available,
    )

    /** 缩放：窗口尺寸 = 按下时的尺寸 + 手指在屏幕上的位移，同样只依赖锚点和手指的屏幕位置。 */
    fun resizedDimension(
        anchorSize: Int,
        anchorPointer: Float,
        currentPointer: Float,
        minimum: Int,
        available: Int,
    ): Int = boundedDimension(
        anchorSize + Math.round(currentPointer - anchorPointer),
        minimum,
        available,
    )
}
