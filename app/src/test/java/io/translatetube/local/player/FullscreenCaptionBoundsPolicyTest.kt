package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenCaptionBoundsPolicyTest {
    @Test
    fun clampsTheWholeSurfaceToAllFourEdges() {
        val bounds = FullscreenCaptionBoundsPolicy.calculate(
            viewportWidthPx = 1_000f,
            viewportHeightPx = 500f,
            captionWidthPx = 300f,
            captionHeightPx = 80f,
        )

        assertEquals(0f, FullscreenCaptionBoundsPolicy.clampLeft(-40f, bounds), 0.001f)
        assertEquals(700f, FullscreenCaptionBoundsPolicy.clampLeft(900f, bounds), 0.001f)
        assertEquals(0f, FullscreenCaptionBoundsPolicy.clampTop(-40f, bounds), 0.001f)
        assertEquals(420f, FullscreenCaptionBoundsPolicy.clampTop(900f, bounds), 0.001f)
    }

    @Test
    fun allowsTheSurfaceToTouchEveryCornerWithoutCrossingIt() {
        val bounds = FullscreenCaptionBoundsPolicy.calculate(
            viewportWidthPx = 900f,
            viewportHeightPx = 400f,
            captionWidthPx = 240f,
            captionHeightPx = 72f,
        )

        val corners = listOf(
            0f to 0f,
            bounds.maxLeftPx to 0f,
            0f to bounds.maxTopPx,
            bounds.maxLeftPx to bounds.maxTopPx,
        )
        corners.forEach { (left, top) ->
            assertTrue(left >= bounds.minLeftPx)
            assertTrue(left + bounds.captionWidthPx <= bounds.viewportWidthPx)
            assertTrue(top >= bounds.minTopPx)
            assertTrue(top + bounds.captionHeightPx <= bounds.viewportHeightPx)
        }
    }

    @Test
    fun reclampsSavedCenterWhenViewportOrCaptionSizeChanges() {
        val resizedX = FullscreenCaptionBoundsPolicy.clampCenterFraction(
            centerFraction = 0.98f,
            viewportExtentPx = 500f,
            surfaceExtentPx = 300f,
        )
        val resizedY = FullscreenCaptionBoundsPolicy.clampCenterFraction(
            centerFraction = 0.02f,
            viewportExtentPx = 300f,
            surfaceExtentPx = 100f,
        )

        assertEquals(0.7f, resizedX, 0.001f)
        assertEquals(1f / 6f, resizedY, 0.001f)
    }

    @Test
    fun handlesAViewportSmallerThanTheMeasuredSurface() {
        val bounds = FullscreenCaptionBoundsPolicy.calculate(
            viewportWidthPx = 240f,
            viewportHeightPx = 80f,
            captionWidthPx = 500f,
            captionHeightPx = 180f,
        )

        assertEquals(240f, bounds.captionWidthPx, 0.001f)
        assertEquals(80f, bounds.captionHeightPx, 0.001f)
        assertEquals(0f, bounds.maxLeftPx, 0.001f)
        assertEquals(0f, bounds.maxTopPx, 0.001f)
        assertEquals(0f, FullscreenCaptionBoundsPolicy.clampLeft(35f, bounds), 0.001f)
        assertEquals(0f, FullscreenCaptionBoundsPolicy.clampTop(35f, bounds), 0.001f)
    }

    @Test
    fun nonFiniteMeasurementsCannotProduceInvalidCoordinates() {
        val bounds = FullscreenCaptionBoundsPolicy.calculate(
            viewportWidthPx = Float.NaN,
            viewportHeightPx = Float.POSITIVE_INFINITY,
            captionWidthPx = Float.NaN,
            captionHeightPx = Float.POSITIVE_INFINITY,
        )

        assertEquals(1f, bounds.viewportWidthPx, 0.001f)
        assertEquals(1f, bounds.viewportHeightPx, 0.001f)
        assertEquals(0f, bounds.captionWidthPx, 0.001f)
        assertEquals(0f, bounds.captionHeightPx, 0.001f)
        assertTrue(FullscreenCaptionBoundsPolicy.clampLeft(Float.NaN, bounds).isFinite())
        assertTrue(FullscreenCaptionBoundsPolicy.clampTop(Float.NaN, bounds).isFinite())
    }
}
