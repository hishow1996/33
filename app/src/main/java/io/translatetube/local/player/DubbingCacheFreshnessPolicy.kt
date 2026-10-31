package io.translatetube.local.player

/**
 * A processing restart caused by any transcription, translation, TTS engine, or voice change
 * must synthesize stage-5 PCM again. Exact old cache identities remain useful for ordinary playback,
 * but must never turn an engine-change restart into a cache-only restore.
 */
internal object DubbingCacheFreshnessPolicy {
    /**
     * Once the caller has decided that this run regenerates dubbing, always bypass old disk PCM.
     * The matching-cache hint is intentionally ignored: only PCM published by this run (or its
     * verified current in-memory synthesis result) may satisfy a cue during this attach.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldForceFreshSynthesis(
        forceRegenerateDubbing: Boolean,
        reuseMatchingDubbingCache: Boolean,
    ): Boolean = forceRegenerateDubbing

    fun canReadCache(
        forceFreshSynthesis: Boolean,
        wasWrittenByCurrentRun: Boolean,
        hasMatchingCurrentMemoryEntry: Boolean = false,
    ): Boolean =
        hasMatchingCurrentMemoryEntry || !forceFreshSynthesis || wasWrittenByCurrentRun
}
