package io.translatetube.local.player

/**
 * A previously cached translation must not replace user-imported translated subtitles.
 * Other flows retain their existing cache-first behavior.
 */
internal object SubtitleCacheRestorePolicy {
    fun shouldRestoreCachedRows(
        cacheAvailable: Boolean,
        hasAuthoritativeImportedTranslation: Boolean,
    ): Boolean = cacheAvailable && !hasAuthoritativeImportedTranslation
}
