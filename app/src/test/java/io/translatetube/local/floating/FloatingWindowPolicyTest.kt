package io.translatetube.local.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowPolicyTest {
    @Test
    fun clampsWindowSizeToDisplayAndRetainsMinimumWhenPossible() {
        assertEquals(640, FloatingWindowPolicy.boundedDimension(640, 280, 1080))
        assertEquals(1080, FloatingWindowPolicy.boundedDimension(1280, 280, 1080))
        assertEquals(280, FloatingWindowPolicy.boundedDimension(100, 280, 1080))
        assertEquals(220, FloatingWindowPolicy.boundedDimension(300, 280, 220))
    }

    @Test
    fun preventsWindowFromBeingDraggedBeyondDisplayEdges() {
        assertEquals(0, FloatingWindowPolicy.boundedOffset(-40, 640, 1080))
        assertEquals(440, FloatingWindowPolicy.boundedOffset(900, 640, 1080))
        assertEquals(0, FloatingWindowPolicy.boundedOffset(500, 640, 640))
    }

    @Test
    fun preventsInvalidDisplayMetricsFromCreatingNegativeBounds() {
        assertEquals(1, FloatingWindowPolicy.boundedDimension(20, 0, 0))
        assertEquals(0, FloatingWindowPolicy.boundedOffset(20, 100, -10))
    }

    @Test
    fun dragFollowsThePointerOnTheScreenRegardlessOfHowOftenItIsRecomputed() {
        // 按下时窗口在 x=100，手指在屏幕 500；手指移到 650 → 窗口应在 250。
        val first = FloatingWindowPolicy.draggedOffset(100, 500f, 650f, 640, 1080)
        assertEquals(250, first)
        // 同一个手指位置反复计算（窗口位置更新有延迟时就会这样）结果不变：不会叠加、不会来回跳。
        repeat(5) { assertEquals(first, FloatingWindowPolicy.draggedOffset(100, 500f, 650f, 640, 1080)) }
        // 手指往回收，窗口跟着往回。
        assertEquals(180, FloatingWindowPolicy.draggedOffset(100, 500f, 580f, 640, 1080))
    }

    @Test
    fun dragIsClampedToTheDisplayAndComesBackWithThePointer() {
        assertEquals(440, FloatingWindowPolicy.draggedOffset(100, 500f, 2000f, 640, 1080))
        assertEquals(0, FloatingWindowPolicy.draggedOffset(100, 500f, -2000f, 640, 1080))
        // 手指拖出屏幕边缘又回到抓取点：窗口回到按下时的位置，而不是卡在边缘。
        assertEquals(100, FloatingWindowPolicy.draggedOffset(100, 500f, 500f, 640, 1080))
    }

    @Test
    fun expandingWindowToFullDisplayWidthAlignsBothHorizontalEdges() {
        val displayWidth = 1080
        val expandedWidth = FloatingWindowPolicy.resizedDimension(
            anchorSize = 640,
            anchorPointer = 900f,
            currentPointer = 3000f,
            minimum = 280,
            available = displayWidth,
        )

        assertEquals(displayWidth, expandedWidth)
        // 缩放到全屏宽度时，左边缘回到 x=0，右边缘恰好等于屏幕宽度。
        val left = FloatingWindowPolicy.boundedOffset(240, expandedWidth, displayWidth)
        assertEquals(0, left)
        assertEquals(displayWidth, left + expandedWidth)
    }

    @Test
    fun resizeFollowsThePointerAndRespectsLimits() {
        assertEquals(740, FloatingWindowPolicy.resizedDimension(640, 900f, 1000f, 280, 1080))
        assertEquals(740, FloatingWindowPolicy.resizedDimension(640, 900f, 1000f, 280, 1080))
        assertEquals(280, FloatingWindowPolicy.resizedDimension(640, 900f, 100f, 280, 1080))
        assertEquals(1080, FloatingWindowPolicy.resizedDimension(640, 900f, 3000f, 280, 1080))
    }
}
