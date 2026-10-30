package io.translatetube.local.translate

/**
 * Conservative available-memory gate for loading the two-file SMaLL-100 ONNX model.
 *
 * The checkpoint contains roughly 609 MB of encoder/decoder weights on disk;
 * loading both sessions also needs room for native runtime state and activations.
 */
internal object Small100MemoryPolicy {
    const val MEGABYTE = 1024L * 1024L
    const val MIN_AVAILABLE_BYTES = 768L * MEGABYTE
    const val MAX_AVAILABLE_BYTES = 1536L * MEGABYTE
    private const val TOTAL_MEMORY_DIVISOR = 8L

    fun requiredAvailableBytes(totalMemoryBytes: Long): Long {
        val proportional = (totalMemoryBytes.coerceAtLeast(0L) / TOTAL_MEMORY_DIVISOR)
            .coerceAtMost(MAX_AVAILABLE_BYTES)
        return maxOf(MIN_AVAILABLE_BYTES, proportional)
    }

    fun hasEnoughAvailableMemory(availableMemoryBytes: Long, totalMemoryBytes: Long): Boolean =
        availableMemoryBytes >= requiredAvailableBytes(totalMemoryBytes)
}
