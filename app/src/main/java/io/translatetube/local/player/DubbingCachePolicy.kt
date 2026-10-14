package io.translatetube.local.player

/** Metadata used to prune old per-video PCM caches without touching the active video's cache. */
internal data class DubbingCacheEntry(
    val path: String,
    val bytes: Long,
    val lastModified: Long,
    val isCurrentVideo: Boolean,
)

internal object DubbingCachePolicy {
    const val BUDGET_BYTES = 160L * 1024L * 1024L
    const val MAX_PCM_SAMPLE_COUNT = 16_000_000

    /** Empty PCM is never a successful TTS result and must not persist as a reusable cache hit. */
    fun isReusablePcmSampleCount(sampleCount: Int): Boolean =
        sampleCount in 1..MAX_PCM_SAMPLE_COUNT

    /** Evict least-recently-used inactive entries until under budget, never choosing active files. */
    fun filesToEvict(
        entries: List<DubbingCacheEntry>,
        budgetBytes: Long = BUDGET_BYTES,
    ): List<DubbingCacheEntry> {
        val valid = entries.filter { it.bytes > 0L }
        var totalBytes = valid.sumOf { it.bytes }
        val budget = budgetBytes.coerceAtLeast(0L)
        if (totalBytes <= budget) return emptyList()

        val evicted = mutableListOf<DubbingCacheEntry>()
        for (entry in valid.asSequence().filterNot { it.isCurrentVideo }.sortedBy { it.lastModified }) {
            if (totalBytes <= budget) break
            evicted += entry
            totalBytes = (totalBytes - entry.bytes).coerceAtLeast(0L)
        }
        return evicted
    }
}
