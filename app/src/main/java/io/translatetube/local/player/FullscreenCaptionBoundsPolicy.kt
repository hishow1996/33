package io.translatetube.local.player

/**
 * Geometry for the complete fullscreen subtitle surface, not just its text.
 *
 * All values are pixels. The policy is deliberately independent of Compose so drag handling,
 * viewport/content-size recovery, and unit tests share the same boundary rules.
 */
internal object FullscreenCaptionBoundsPolicy {
    data class Bounds(
        val viewportWidthPx: Float,
        val viewportHeightPx: Float,
        val captionWidthPx: Float,
        val captionHeightPx: Float,
        val minLeftPx: Float,
        val maxLeftPx: Float,
        val minTopPx: Float,
        val maxTopPx: Float,
    )

    fun calculate(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        captionWidthPx: Float,
        captionHeightPx: Float,
    ): Bounds {
        val viewportWidth = finitePositive(viewportWidthPx)
        val viewportHeight = finitePositive(viewportHeightPx)
        val captionWidth = finiteNonNegative(captionWidthPx).coerceAtMost(viewportWidth)
        val captionHeight = finiteNonNegative(captionHeightPx).coerceAtMost(viewportHeight)

        return Bounds(
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
            captionWidthPx = captionWidth,
            captionHeightPx = captionHeight,
            minLeftPx = 0f,
            maxLeftPx = (viewportWidth - captionWidth).coerceAtLeast(0f),
            minTopPx = 0f,
            maxTopPx = (viewportHeight - captionHeight).coerceAtLeast(0f),
        )
    }

    fun clampLeft(leftPx: Float, bounds: Bounds): Float =
        finiteOr(leftPx, bounds.minLeftPx).coerceIn(bounds.minLeftPx, bounds.maxLeftPx)

    fun clampTop(topPx: Float, bounds: Bounds): Float =
        finiteOr(topPx, bounds.minTopPx).coerceIn(bounds.minTopPx, bounds.maxTopPx)

    fun clampCenterFraction(
        centerFraction: Float,
        viewportExtentPx: Float,
        surfaceExtentPx: Float,
    ): Float {
        val viewport = finitePositive(viewportExtentPx)
        val surface = finiteNonNegative(surfaceExtentPx).coerceAtMost(viewport)
        val halfExtentFraction = (surface / (2f * viewport)).coerceIn(0f, 0.5f)
        return finiteOr(centerFraction, 0.5f).coerceIn(
            halfExtentFraction,
            1f - halfExtentFraction,
        )
    }

    fun centerFractionForPosition(
        positionPx: Float,
        surfaceExtentPx: Float,
        viewportExtentPx: Float,
    ): Float {
        val viewport = finitePositive(viewportExtentPx)
        val surface = finiteNonNegative(surfaceExtentPx).coerceAtMost(viewport)
        val position = finiteOr(positionPx, 0f).coerceIn(0f, viewport - surface)
        return ((position + surface / 2f) / viewport).coerceIn(0f, 1f)
    }

    private fun finitePositive(value: Float): Float =
        if (value.isFinite() && value > 0f) value else 1f

    private fun finiteNonNegative(value: Float): Float =
        if (value.isFinite() && value > 0f) value else 0f

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback
}
