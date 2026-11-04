package io.translatetube.local.player

/** Stable key for downloaded YouTube captions, independent of the transcription engine. */
internal object YouTubeCaptionCacheIdentity {
    fun prefix(videoKey: String, sourceCacheVersion: Int, subtitleMode: String): String =
        videoKey + "|source|youtube-v" + sourceCacheVersion + "-subtitle-" + subtitleMode + "|"

    fun key(videoKey: String, sourceCacheVersion: Int, subtitleMode: String): String =
        prefix(videoKey, sourceCacheVersion, subtitleMode) + "captions-v2"

    fun isCaptionCacheKey(cacheKey: String?): Boolean =
        cacheKey?.endsWith("|captions-v1") == true || cacheKey?.endsWith("|captions-v2") == true
}
