package io.translatetube.local.player

import java.util.LinkedHashMap

/**
 * PCM held in RAM is reusable only for the exact cache identity that produced it.
 * The identity includes cue text, speaker, TTS implementation/voice, speed and cue duration.
 */
internal data class DubbingPcmMemoryEntry(
    val samples: FloatArray,
    val sampleRate: Int,
    val cacheIdentity: String,
)

/** Fail closed when the active cache identity is missing or differs from the stored PCM identity. */
internal object DubbingMemoryCacheIdentityPolicy {
    fun canReuse(cachedIdentity: String?, activeIdentity: String?): Boolean =
        !cachedIdentity.isNullOrBlank() &&
            !activeIdentity.isNullOrBlank() &&
            cachedIdentity == activeIdentity
}

/**
 * Access is synchronized by DubbingEngine at the call site. Values count only FloatArray payload
 * bytes, which dominate PCM cache memory; metadata overhead is bounded by maxEntries.
 *
 * This uses composition rather than exposing LinkedHashMap's inherited mutators: methods such as
 * putAll/replaceAll on a Map subclass can bypass an overridden put() and corrupt the byte budget.
 */
internal class DubbingPcmMemoryCache(
    private var maxBytes: Long = maxBytes.coerceAtLeast(0L),
    private val maxEntries: Int = 48,
) {
    private val storage = LinkedHashMap<Int, DubbingPcmMemoryEntry>(16, 0.75f, true)
    private var retainedBytes = 0L

    val size: Int get() = storage.size

    fun isEmpty(): Boolean = storage.isEmpty()

    fun containsKey(key: Int): Boolean = storage.containsKey(key)

    operator fun get(key: Int): DubbingPcmMemoryEntry? = storage[key]

    operator fun set(key: Int, value: DubbingPcmMemoryEntry) {
        put(key, value)
    }

    fun put(key: Int, value: DubbingPcmMemoryEntry): DubbingPcmMemoryEntry? {
        val previous = remove(key)
        val valueBytes = value.samples.size.toLong() * java.lang.Float.BYTES
        val validEntry = value.sampleRate in 8_000..192_000 &&
            DubbingPcmValidation.isSampleCountAllowed(value.samples.size) &&
            value.cacheIdentity.isNotBlank()
        if (!validEntry || maxBytes <= 0L || maxEntries <= 0 || valueBytes > maxBytes) {
            return previous
        }

        storage[key] = value
        retainedBytes += valueBytes
        trimToBudget()
        return previous
    }

    /** Insert via the same bounded path as individual entries; never bypass byte accounting. */
    fun putAll(values: Map<Int, DubbingPcmMemoryEntry>) {
        values.forEach { (key, value) -> put(key, value) }
    }

    fun remove(key: Int): DubbingPcmMemoryEntry? {
        val previous = storage.remove(key)
        if (previous != null) {
            retainedBytes = (retainedBytes - bytesFor(previous)).coerceAtLeast(0L)
        }
        return previous
    }

    fun clear() {
        storage.clear()
        retainedBytes = 0L
    }

    /** Lowering the budget immediately evicts least-recently-used PCM until it fits. */
    fun updateBudget(newMaxBytes: Long) {
        maxBytes = newMaxBytes.coerceAtLeast(0L)
        trimToBudget()
    }

    fun retainedBytes(): Long = retainedBytes

    private fun trimToBudget() {
        val iterator = storage.entries.iterator()
        while ((retainedBytes > maxBytes || storage.size > maxEntries) && iterator.hasNext()) {
            val entry = iterator.next()
            retainedBytes = (retainedBytes - bytesFor(entry.value)).coerceAtLeast(0L)
            iterator.remove()
        }
    }

    private fun bytesFor(entry: DubbingPcmMemoryEntry): Long =
        entry.samples.size.toLong() * java.lang.Float.BYTES
}

/** Memory budgets for pre-synthesized dubbing PCM, selected by Android's app-heap class. */
internal object DubbingPcmMemoryPolicy {
    private const val MIB = 1024L * 1024L

    fun maxBytes(isLowRamDevice: Boolean, memoryClassMb: Int): Long = when {
        isLowRamDevice -> 4L * MIB
        memoryClassMb in 1..256 -> 8L * MIB
        memoryClassMb in 257..384 -> 16L * MIB
        memoryClassMb > 384 -> 32L * MIB
        else -> 8L * MIB
    }

    fun maxBytes(snapshot: io.translatetube.local.performance.DevicePerformanceSnapshot): Long =
        io.translatetube.local.performance.DevicePerformancePolicy.dubbingPcmCacheBytes(snapshot)
}
