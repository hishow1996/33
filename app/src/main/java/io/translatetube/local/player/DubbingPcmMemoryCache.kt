package io.translatetube.local.player

import java.util.LinkedHashMap

/**
 * Access is synchronized by DubbingEngine at the call site. Values count only FloatArray payload
 * bytes, which dominate PCM cache memory; metadata overhead is bounded by maxEntries.
 */
internal class DubbingPcmMemoryCache(
    private val maxBytes: Long,
    private val maxEntries: Int = 48,
) : LinkedHashMap<Int, Pair<FloatArray, Int>>(16, 0.75f, true) {
    private var retainedBytes = 0L

    override fun put(key: Int, value: Pair<FloatArray, Int>): Pair<FloatArray, Int>? {
        val previous = remove(key)
        val valueBytes = value.first.size.toLong() * java.lang.Float.BYTES
        if (maxBytes <= 0L || maxEntries <= 0 || valueBytes > maxBytes) return previous

        super.put(key, value)
        retainedBytes += valueBytes
        trimToBudget()
        return previous
    }

    override fun remove(key: Int): Pair<FloatArray, Int>? {
        val previous = super.remove(key)
        if (previous != null) {
            retainedBytes = (retainedBytes - previous.first.size.toLong() * java.lang.Float.BYTES)
                .coerceAtLeast(0L)
        }
        return previous
    }

    override fun clear() {
        super.clear()
        retainedBytes = 0L
    }

    fun retainedBytes(): Long = retainedBytes

    private fun trimToBudget() {
        val iterator = entries.iterator()
        while ((retainedBytes > maxBytes || size > maxEntries) && iterator.hasNext()) {
            val entry = iterator.next()
            retainedBytes = (retainedBytes - entry.value.first.size.toLong() * java.lang.Float.BYTES)
                .coerceAtLeast(0L)
            iterator.remove()
        }
    }
}

/** Memory budgets for pre-synthesized dubbing PCM, selected by Android's app-heap class. */
internal object DubbingPcmMemoryPolicy {
    private const val MIB = 1024L * 1024L

    fun maxBytes(isLowRamDevice: Boolean, memoryClassMb: Int): Long = when {
        isLowRamDevice -> 4L * MIB
        memoryClassMb in 1..256 -> 8L * MIB
        memoryClassMb in 257..384 -> 16L * MIB
        else -> 32L * MIB
    }
}
