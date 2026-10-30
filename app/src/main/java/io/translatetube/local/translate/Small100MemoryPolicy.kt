package io.translatetube.local.translate

/**
 * Shared available-memory gate for native on-device translation model runtimes.
 *
 * Native model loading needs headroom for weights, runtime state and inference activations.
 */
internal object LocalTranslationMemoryPolicy {
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
