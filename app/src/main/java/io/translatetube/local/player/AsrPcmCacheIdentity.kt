package io.translatetube.local.player

/**
 * Identity of the decoded 16 kHz mono PCM kept in the shared PCM cache.
 *
 * Both local transcription and person recognition need the same decoded audio. Without a shared
 * identity each of them decoded the whole track again. A null result means the source cannot be
 * identified reliably, so callers must decode into a temporary file instead of caching.
 */
internal object AsrPcmCacheIdentity {
    /** Unchanged since the first local-media PCM cache so existing cache files keep matching. */
    private const val LOCAL_SUFFIX = "|local-asr-pcm16-v1"
    private const val UNVERIFIED_LOCAL_PREFIX = "local-media-unverified-v1:"

    fun local(cacheKeyBase: String): String? =
        cacheKeyBase.takeIf { it.isNotBlank() && !it.startsWith(UNVERIFIED_LOCAL_PREFIX) }
            ?.let { it + LOCAL_SUFFIX }

    fun youtube(videoId: String): String? =
        videoId.takeIf { it.isNotBlank() }?.let { "youtube-asr-audio:$it|asr-pcm16-v1" }

    /** [audioKey] is MediaUrlCacheIdentity.audioKey: it already isolates referrer and cookie session. */
    fun remote(audioKey: String): String? =
        audioKey.takeIf { it.isNotBlank() }?.let { "remote-asr-audio:$it|asr-pcm16-v1" }
}
