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
    private val volatileQueryParameters = setOf(
        "token", "access_token", "auth", "authorization", "auth_key", "session",
        "sessionid", "jwt", "signature", "sig", "lsig", "policy", "key-pair-id",
        "expires", "expiry", "expire", "hdnea", "__token", "hmac", "nonce",
        "x-amz-signature", "x-amz-credential", "x-amz-date", "x-amz-expires",
        "x-amz-security-token", "x-goog-signature", "x-goog-credential",
        "x-goog-date", "x-goog-expires", "x-goog-security-token",
        "se", "sp", "sv", "st", "sr", "spr", "sip", "skoid", "sktid",
        "skt", "ske", "sks", "skv",
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
        val retainedQuery = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { item ->
                val rawName = item.substringBefore('=')
                val decodedName = runCatching {
                    URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                }.getOrDefault(rawName).lowercase(Locale.ROOT)
                decodedName in volatileQueryParameters
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
