package io.translatetube.local.player

import java.math.BigInteger

/** Metadata used to prune old per-video PCM caches without touching the active video's cache. */
internal data class DubbingCacheEntry(
    val path: String,
    val bytes: Long,
    val lastModified: Long,
    val isCurrentSessionFile: Boolean,
    /** A cached voice/model variant belonging to the video currently open in the player. */
    val isCurrentVideoFile: Boolean = false,
)

internal object DubbingCachePolicy {
    const val BUDGET_BYTES = 160L * 1024L * 1024L

    /** Evict least-recently-used entries until under budget, never choosing files required by the active session. */
    fun filesToEvict(
        entries: List<DubbingCacheEntry>,
        budgetBytes: Long = BUDGET_BYTES,
    ): List<DubbingCacheEntry> {
        val valid = entries.filter { it.bytes > 0L }
        var totalBytes = valid.fold(BigInteger.ZERO) { total, entry ->
            total.add(BigInteger.valueOf(entry.bytes))
        }
        val budget = BigInteger.valueOf(budgetBytes.coerceAtLeast(0L))
        if (totalBytes <= budget) return emptyList()

        val evicted = mutableListOf<DubbingCacheEntry>()
        // Preserve old voice/model variants for the active video as long as unrelated videos
        // can be evicted first. If the active video's variants alone exceed the budget, they remain
        // eligible after inactive-video caches are exhausted; the exact active session is protected.
        val evictionOrder = valid.asSequence()
            .filterNot { it.isCurrentSessionFile }
            .sortedWith(
                compareBy<DubbingCacheEntry> { it.isCurrentVideoFile }
                    .thenBy { it.lastModified },
            )
        for (entry in evictionOrder) {
            if (totalBytes <= budget) break
            evicted += entry
            totalBytes = totalBytes.subtract(BigInteger.valueOf(entry.bytes)).max(BigInteger.ZERO)
        }
        return evicted
    }
}
