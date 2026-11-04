package io.translatetube.local.player

/**
 * Binds source-card metadata to the selected remote media and its WebView session.
 *
 * The underlying cache identity strips rotating authentication signatures while retaining
 * meaningful rendition parameters. Only a one-way digest is carried into playback state.
 */
internal object SelectedWebMediaMetadataIdentityPolicy {
    fun identity(
        mediaUrl: String,
        referrer: String,
        cookie: String?,
    ): String = MediaUrlCacheIdentity.remoteSessionKey(mediaUrl, referrer, cookie)

    fun matches(
        metadata: SelectedWebMediaMetadata,
        mediaUrl: String,
        referrer: String,
        cookie: String?,
    ): Boolean {
        val suppliedIdentity = metadata.sourceIdentity.trim()
        if (suppliedIdentity.isBlank() || mediaUrl.isBlank()) return false
        return suppliedIdentity == identity(mediaUrl, referrer, cookie)
    }
}
