package io.translatetube.local.player

/**
 * YouTube ASR output depends on the audio and ASR configuration, not on the preferred caption
 * language. Keep the active key stable across caption-mode switches while still recognizing
 * mode-scoped keys written by older versions for migration.
 */
internal object YouTubeAsrSourceCacheIdentity {
    fun key(
        videoKey: String,
        sourceCacheVersion: Int,
        asrCacheIdentity: String,
    ): String =
        "$videoKey|source|youtube-asr-v$sourceCacheVersion|asr-$asrCacheIdentity"

    fun legacyKeys(
        videoKey: String,
        sourceCacheVersion: Int,
        subtitleModes: Iterable<String>,
        asrCacheIdentity: String,
    ): List<String> =
        subtitleModes.distinct().map { mode ->
            "$videoKey|source|youtube-v$sourceCacheVersion-subtitle-$mode|asr-$asrCacheIdentity"
        }
}
