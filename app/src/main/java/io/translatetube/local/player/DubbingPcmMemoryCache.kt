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
 */
internal class DubbingPcmMemoryCache(
    private val maxBytes: Long,
    private val maxEntries: Int = 48,
) : LinkedHashMap<Int, DubbingPcmMemoryEntry>(16, 0.75f, true) {
    private var retainedBytes = 0L

    override fun put(key: Int, value: DubbingPcmMemoryEntry): DubbingPcmMemoryEntry? {
        val previous = remove(key)
        val valueBytes = value.samples.size.toLong() * java.lang.Float.BYTES
        if (maxBytes <= 0L || maxEntries <= 0 || valueBytes > maxBytes) return previous

        super.put(key, value)
        retainedBytes += valueBytes
        trimToBudget()
        return previous
    }

    override fun remove(key: Int): DubbingPcmMemoryEntry? {
        val previous = super.remove(key)
        if (previous != null) {
            retainedBytes = (retainedBytes - previous.samples.size.toLong() * java.lang.Float.BYTES)
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
            retainedBytes = (retainedBytes - entry.value.samples.size.toLong() * java.lang.Float.BYTES)
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
