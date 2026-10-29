package io.translatetube.local.player

/**
 * YouTube ASR output depends on the audio and ASR configuration, not on caption selection.
 * Keep its version independent from YouTubeSubtitleSelectionPolicy.SOURCE_CACHE_VERSION so
 * changing subtitle discovery rules cannot invalidate completed audio transcription.
 */
internal object YouTubeAsrSourceCacheIdentity {
    const val CACHE_VERSION = 1

    fun key(
        videoKey: String,
        asrCacheIdentity: String,
    ): String =
        "$videoKey|source|youtube-asr-v$CACHE_VERSION|asr-$asrCacheIdentity"

    /**
     * Previous releases coupled ASR keys to the caption-source version. Query those versions only
     * during migration; the active key above never depends on caption selection or its version.
     * The current key version is excluded from the legacy list to prevent duplicate lookups.
     */
    fun legacyKeys(
        videoKey: String,
        legacySourceCacheVersions: Iterable<Int>,
        subtitleModes: Iterable<String>,
        asrCacheIdentity: String,
    ): List<String> = buildList {
        legacySourceCacheVersions.distinct()
            .filter { it > 0 && it != CACHE_VERSION }
            .forEach { version ->
                add("$videoKey|source|youtube-asr-v$version|$asrCacheIdentity")
            }
        legacySourceCacheVersions.distinct()
            .filter { it > 0 }
            .forEach { version ->
                subtitleModes.distinct().forEach { mode ->
                    add("$videoKey|source|youtube-v$version-subtitle-$mode|$asrCacheIdentity")
                }
            }
    }.distinct()
}
