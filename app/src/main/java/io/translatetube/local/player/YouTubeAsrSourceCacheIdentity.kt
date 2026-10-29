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
     * Mode-specific keys and the first mode-independent key were used by previous commits.
     * Read them during migration, then write the stable CACHE_VERSION key after validation.
     */
    fun legacyKeys(
        videoKey: String,
        sourceCacheVersion: Int,
        subtitleModes: Iterable<String>,
        asrCacheIdentity: String,
    ): List<String> = buildList {
        add("$videoKey|source|youtube-asr-v$sourceCacheVersion|asr-$asrCacheIdentity")
        subtitleModes.distinct().forEach { mode ->
            add("$videoKey|source|youtube-v$sourceCacheVersion-subtitle-$mode|asr-$asrCacheIdentity")
        }
    }.distinct()
}
