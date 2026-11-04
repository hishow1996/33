package io.translatetube.local.player

/**
 * A previously cached translation must not replace user-imported translated subtitles.
 * Other flows retain their existing cache-first behavior.
 */
internal object SubtitleCacheRestorePolicy {
    fun shouldUseCachedRows(
        hasAuthoritativeImportedTranslation: Boolean,
    ): Boolean = !hasAuthoritativeImportedTranslation

    /**
     * A local/web translation cache is tied to a URI/URL and engine identity, not a cryptographic
     * hash of the media contents. If the corresponding source timeline is missing, never assume
     * the cached rows still describe the current media.
     */
    fun shouldInvalidateUnverifiedMediaTranslationCache(
        hasImportedSource: Boolean,
        isYouTube: Boolean,
        hasMediaSource: Boolean,
        hasValidatedSourceTimeline: Boolean,
    ): Boolean =
        !hasImportedSource &&
            !isYouTube &&
            hasMediaSource &&
            !hasValidatedSourceTimeline
}
