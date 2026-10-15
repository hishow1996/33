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
}
