package io.translatetube.local.translate

/** Keeps CTranslate2 subtitle batches small when the device has limited free memory. */
internal object OpusMtBatchPolicy {
    private const val MEGABYTE = 1024L * 1024L
    private const val LOW_MEMORY_BYTES = 1024L * MEGABYTE
    private const val NORMAL_MEMORY_BYTES = 1536L * MEGABYTE

    data class Limits(
        val maxExamples: Int,
        val maxCharacters: Int,
    )

    /**
     * Available system memory is sampled for every batch. A single long cue is always allowed
     * through on its own; it is never truncated to satisfy the aggregate batch character budget.
     */
    fun resolve(availableMemoryBytes: Long): Limits {
        val available = availableMemoryBytes.coerceAtLeast(0L)
        return when {
            available < LOW_MEMORY_BYTES -> Limits(maxExamples = 2, maxCharacters = 1_024)
            available < NORMAL_MEMORY_BYTES -> Limits(maxExamples = 4, maxCharacters = 2_048)
            else -> Limits(maxExamples = 8, maxCharacters = 4_096)
        }
    }

    /** Returns an exclusive end index and always advances for a valid start index. */
    fun nextBatchEndIndex(
        lineLengths: List<Int>,
        startIndex: Int,
        limits: Limits,
    ): Int {
        require(limits.maxExamples > 0) { "maxExamples must be positive" }
        require(limits.maxCharacters > 0) { "maxCharacters must be positive" }
        require(startIndex in 0..lineLengths.size) { "startIndex is outside the input list" }
        if (startIndex == lineLengths.size) return startIndex

        var endIndex = startIndex
        var totalCharacters = 0L
        while (
            endIndex < lineLengths.size &&
            endIndex - startIndex < limits.maxExamples
        ) {
            val nextLength = lineLengths[endIndex].coerceAtLeast(0).toLong()
            // Do not split one oversized subtitle. Isolate it into a one-row native request.
            if (endIndex > startIndex && totalCharacters + nextLength > limits.maxCharacters) {
                break
            }
            totalCharacters += nextLength
            endIndex++
        }
        return endIndex
    }
}
