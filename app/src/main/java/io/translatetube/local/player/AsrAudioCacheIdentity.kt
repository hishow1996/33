package io.translatetube.local.player

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable, non-reversible cache identity for media URLs that may rotate signed query parameters.
 *
 * Keep content selectors (for example videoId, id, format, and type) in the identity; remove only
 * known expiring signatures/session credentials. The canonical URL itself is never persisted to
 * cache filenames or downloader metadata.
 */
internal object AsrAudioCacheIdentity {
    private val sourcePrefixes = setOf("webmedia", "webpage", "local")
    // Explicit signatures and expiry fields are safe to ignore when identifying the resource.
    private val signedQueryParameters = setOf(
        "signature", "sig", "lsig", "policy", "key-pair-id",
        "expires", "expiry", "expire", "exp", "expires_at", "hdnea", "hdnts", "__gda__",
        "hmac", "si",
        "x-amz-signature", "x-amz-credential", "x-amz-date", "x-amz-expires",
        "x-amz-security-token", "x-goog-signature", "x-goog-credential",
        "x-goog-date", "x-goog-expires", "x-goog-security-token",
        "se", "sp", "sv", "st", "sr", "spr", "sip", "skoid", "sktid",
        "skt", "ske", "sks", "skv",
    )
    // Generic names like "token" can also be actual resource IDs on opaque endpoints. Remove
    // them only when the URL path or another query parameter independently identifies the media.
    private val genericCredentialParameters = setOf(
        "token", "access_token", "access-token", "auth", "auth_token", "x-auth-token",
        "authorization", "auth_key", "session", "sessionid", "session_token", "jwt", "__token",
        "nonce",
    )
    private val stableContentSelectors = setOf(
        "id", "videoid", "video_id", "video", "v", "file", "asset", "assetid",
        "mediaid", "media_id", "contentid", "content_id", "manifestid",
        "manifest", "streamid", "stream_id", "uuid", "objectid", "object_id",
        "resource_id", "documentid",
    )
    private val mediaFileExtensions = setOf(
        "m3u8", "mpd", "ts", "m4s", "mp4", "m4a", "m4v", "mp3", "aac", "webm",
        "mov", "mkv", "ogg", "oga", "opus", "flac", "wav", "vtt", "srt",
    )

    fun canonicalSource(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        val colon = trimmed.indexOf(':')
        val prefix = trimmed.substringBefore(':').takeIf {
            colon > 0 && it.lowercase(Locale.ROOT) in sourcePrefixes
        }
        val rawUrl = if (prefix != null) trimmed.substring(colon + 1) else trimmed
        val uri = runCatching { URI(rawUrl).normalize() }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return trimmed
        val host = uri.host?.lowercase(Locale.ROOT) ?: return trimmed
        if ((scheme != "http" && scheme != "https") || uri.userInfo != null) return trimmed

        val port = when {
            uri.port == 80 && scheme == "http" -> -1
            uri.port == 443 && scheme == "https" -> -1
            else -> uri.port
        }
        val authority = if (port < 0) host else "$host:$port"
        val rawPath = uri.rawPath.orEmpty().ifBlank { "/" }
        val queryItems = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }
        fun decodedName(item: String): String {
            val rawName = item.substringBefore('=')
            return runCatching {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawName).lowercase(Locale.ROOT)
        }
        val queryNames = queryItems.map { item -> decodedName(item) }.toSet()
        val extension = rawPath.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        val hasStableMediaIdentity = extension in mediaFileExtensions ||
            queryNames.any { it in stableContentSelectors }
        val retainedQuery = queryItems
            .filterNot { item ->
                val name = decodedName(item)
                name in signedQueryParameters ||
                    (hasStableMediaIdentity && name in genericCredentialParameters)
            }
            .sorted()
        val query = retainedQuery.takeIf { it.isNotEmpty() }
            ?.joinToString("&", prefix = "?").orEmpty()
        val canonicalUrl = "$scheme://$authority$rawPath$query"
        return if (prefix != null) "$prefix:$canonicalUrl" else canonicalUrl
    }

    /** SHA-256 of the canonical source, truncated for compact filesystem/database identities. */
    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalSource(value).toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
