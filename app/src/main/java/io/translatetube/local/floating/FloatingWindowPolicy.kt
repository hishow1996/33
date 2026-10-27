package io.translatetube.local.floating

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Keeps a draggable/resizable overlay inside the usable display bounds and preserves video aspect. */
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

    fun normalizeAspectRatio(aspectRatio: Float, fallbackWidth: Int, fallbackHeight: Int): Float {
        if (aspectRatio.isFinite() && aspectRatio in 0.1f..10f) return aspectRatio
        return fallbackWidth.coerceAtLeast(1).toFloat() / fallbackHeight.coerceAtLeast(1)
    }

    /**
     * Fit the requested window area to the actual video aspect ratio. The initial window keeps
     * approximately the requested area while avoiding an independent width/height clamp that
     * would stretch portrait or ultrawide video.
     */
    fun aspectLockedSize(
        requestedWidth: Int,
        requestedHeight: Int,
        aspectRatio: Float,
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Pair<Int, Int> {
        val ratio = normalizeAspectRatio(aspectRatio, requestedWidth, requestedHeight)
        val widthLimit = maxWidth.coerceAtLeast(1)
        val heightLimit = maxHeight.coerceAtLeast(1)

        // Keep the scale bounds ratio-aware, so the minimum size never distorts the video.
        val maxScaleHeight = minOf(
            heightLimit.toDouble(),
            widthLimit.toDouble() / ratio.toDouble(),
        ).coerceAtLeast(1.0)
        val minScaleHeight = maxOf(
            minHeight.coerceAtLeast(1).toDouble(),
            minWidth.coerceAtLeast(1).toDouble() / ratio.toDouble(),
        ).coerceAtMost(maxScaleHeight)

        val requestedArea = requestedWidth.coerceAtLeast(1).toDouble() *
            requestedHeight.coerceAtLeast(1).toDouble()
        val requestedScaleHeight = sqrt(requestedArea / ratio.toDouble())
        val scaleHeight = requestedScaleHeight.coerceIn(minScaleHeight, maxScaleHeight)

        val height = scaleHeight.roundToInt().coerceIn(1, heightLimit)
        // Pixel rounding can make one edge differ by at most a pixel from the ideal ratio.
        val width = (height.toDouble() * ratio).roundToInt().coerceIn(1, widthLimit)
        return width to height
    }

    /**
     * Resize from the bottom-right handle while keeping the video's width/height ratio.
     * Horizontal drag is converted to its equivalent height change; whichever axis moved farther
     * controls the scale, so users can resize by pulling mostly horizontally or vertically.
     */
    fun aspectLockedResizedSize(
        anchorWidth: Int,
        anchorHeight: Int,
        deltaX: Float,
        deltaY: Float,
        aspectRatio: Float,
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Pair<Int, Int> {
        val ratio = normalizeAspectRatio(aspectRatio, anchorWidth, anchorHeight)
        val heightDeltaFromWidth = deltaX / ratio
        val heightDelta = if (abs(heightDeltaFromWidth) > abs(deltaY)) {
            heightDeltaFromWidth
        } else {
            deltaY
        }
        val requestedHeight = (anchorHeight.toFloat() + heightDelta).roundToInt().coerceAtLeast(1)
        val requestedWidth = (requestedHeight.toDouble() * ratio).roundToInt().coerceAtLeast(1)
        return aspectLockedSize(
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            aspectRatio = ratio,
            minWidth = minWidth,
            minHeight = minHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
    }

    /**
     * Drag position = start window position + pointer movement in absolute screen coordinates.
     * This does not depend on the window's current position, so delayed layout updates cannot
     * accumulate movement or make the overlay jump back and forth.
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

    /** Legacy single-dimension helper retained for existing callers/tests. */
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
