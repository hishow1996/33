package io.translatetube.local.player

/**
 * A forced synthesis run cannot trust an existing disk cache just because its filename matches.
 * Current in-memory PCM is safe only after the caller verifies its active cache identity.
 */
internal object DubbingCacheFreshnessPolicy {
    fun canReadCache(
        forceFreshSynthesis: Boolean,
        wasWrittenByCurrentRun: Boolean,
        hasMatchingCurrentMemoryEntry: Boolean = false,
    ): Boolean =
        hasMatchingCurrentMemoryEntry || !forceFreshSynthesis || wasWrittenByCurrentRun
}
