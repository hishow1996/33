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
}
