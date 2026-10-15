package io.translatetube.local.player

import java.math.BigInteger

/** Metadata used to prune old per-video PCM caches without touching the active video's cache. */
internal data class DubbingCacheEntry(
    val path: String,
    val bytes: Long,
    val lastModified: Long,
    val isCurrentVideo: Boolean,
)

internal object DubbingCachePolicy {
    const val BUDGET_BYTES = 160L * 1024L * 1024L

    /** Evict least-recently-used inactive entries until under budget, never choosing active files. */
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
        for (entry in valid.asSequence().filterNot { it.isCurrentVideo }.sortedBy { it.lastModified }) {
            if (totalBytes <= budget) break
            evicted += entry
            totalBytes = totalBytes.subtract(BigInteger.valueOf(entry.bytes)).max(BigInteger.ZERO)
        }
        return evicted
    }
}
