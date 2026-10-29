package io.translatetube.local.player

/**
 * A forced synthesis run cannot trust an existing disk cache just because its filename matches.
 * Current in-memory PCM is safe only after the caller verifies its active cache identity.
 */
internal object DubbingCacheFreshnessPolicy {
    /**
     * Stage-5 restarts after changing a voice/engine should synthesize on a cache miss, but they
     * must not delete a previous successful PCM file whose complete identity matches again.
     * Explicit fresh requests (for example, an imported external translation) keep the old rule.
     */
    fun shouldForceFreshSynthesis(
        forceRegenerateDubbing: Boolean,
        reuseMatchingDubbingCache: Boolean,
    ): Boolean = forceRegenerateDubbing && !reuseMatchingDubbingCache

    fun canReadCache(
        forceFreshSynthesis: Boolean,
        wasWrittenByCurrentRun: Boolean,
        hasMatchingCurrentMemoryEntry: Boolean = false,
    ): Boolean =
        hasMatchingCurrentMemoryEntry || !forceFreshSynthesis || wasWrittenByCurrentRun
}
