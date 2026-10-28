package io.translatetube.local.player

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable cache identity for signed media URLs.
 *
 * Authentication parameters are removed from the cache identity only, never from the URL used to
 * perform a request. Meaningful query parameters (such as a rendition/format id) remain part of
 * the identity, preventing different streams at the same path from being mixed.
 */
internal object MediaUrlCacheIdentity {
    private val authParameterNames = setOf(
        "access_token",
        "auth",
        "auth_key",
        "authorization",
        "expires",
        "expiry",
        "exp",
        "token",
        "signature",
        "sig",
        "policy",
        "key-pair-id",
        "hdnea",
        "hdnts",
        "jwt",
        "wmsauthsign",
        "__token__",
    )

    private val hlsSignedParameterNames = setOf("validfrom", "validto", "ipa", "hdl", "hash")

    // Generic credentials can sometimes double as the only media identifier (for example,
    // /stream?token=asset-id). Strip them only when another stable identity is available.
    private val genericCredentialParameterNames = setOf(
        "access_token", "auth", "auth_key", "authorization", "token", "jwt", "__token__",
    )
    private val stableResourceIdNames = setOf("id", "video_id", "media_id", "asset_id")
    private val expiryParameterNames = setOf("expires", "expiry", "exp")
    private val explicitSignatureNames = setOf(
        "signature", "sig", "policy", "key-pair-id", "hdnea", "hdnts", "wmsauthsign",
    )
    private val mediaFilePath = Regex(
        """\.(?:mp4|m4v|mov|webm|mkv|mp3|m4a|aac|ogg|wav|flac|m3u8|m4s|ts|mpd)$""",
        RegexOption.IGNORE_CASE,
    )

    private val azureSasParameterNames = setOf(
        "se", "sp", "sr", "st", "sip", "spr", "sv", "srt", "ss",
        "skoid", "sktid", "skt", "ske", "sks", "skv",
        "rscc", "rscd", "rsce", "rscl", "rsct",
    )

    private fun hasValidHttpAuthority(uri: URI, scheme: String): Boolean {
        val port = uri.port
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            !uri.rawAuthority.orEmpty().endsWith(":") &&
            (port == -1 || port in 1..65535)
    }

    fun canonicalUrl(value: String): String {
        val rawValue = value.trim()
        val uri = runCatching { URI(rawValue) }.getOrNull()
            ?: return "invalid-url-sha256:${sha256(rawValue)}"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme == null || !hasValidHttpAuthority(uri, scheme) || host.isNullOrBlank()) {
            return "invalid-url-sha256:${sha256(rawValue)}"
        }

        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        val hostForUrl = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val authority = hostForUrl + if (port >= 0) ":$port" else ""
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val params = uri.rawQuery?.split('&').orEmpty()
        val decodedNames = params.map { part ->
            runCatching {
                URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
                    .lowercase(Locale.ROOT)
            }.getOrDefault(part.substringBefore('=').lowercase(Locale.ROOT))
        }
        val hasAwsSignature = decodedNames.any { it == "x-amz-signature" }
        val hasGoogleSignature = decodedNames.any { it == "x-goog-signature" }
        val hasAzureSignature = decodedNames.any { it == "sig" } &&
            decodedNames.any { it == "sv" || it == "se" || it == "sr" }
        val hasExplicitSignature = decodedNames.any { it in explicitSignatureNames } ||
            hasAwsSignature || hasGoogleSignature || hasAzureSignature
        val hasExpiryWithCredential =
            decodedNames.any { it in expiryParameterNames } &&
                decodedNames.any { it in genericCredentialParameterNames || it in explicitSignatureNames }
        // Rendition selectors such as quality/itag alone do not identify the underlying asset.
        // Only a resource ID, recognizable media filename, or signature evidence allows us to
        // ignore a generic token while preserving cache correctness for opaque endpoint URLs.
        val hasStableResourceId = decodedNames.any { it in stableResourceIdNames }
        val hasMediaFilePath = mediaFilePath.containsMatchIn(rawPath)
        val canStripGenericCredentials =
            hasExplicitSignature || hasExpiryWithCredential || hasStableResourceId || hasMediaFilePath

        // Keep generic tokens distinct on opaque endpoints that expose no other stable media
        // identity. Hash their values instead of retaining raw credentials in the canonical URL.
        val hasHlsSignedQuery = hasMediaFilePath &&
            decodedNames.any { it == "validfrom" } &&
            decodedNames.any { it == "validto" } &&
            decodedNames.any { it == "hash" }
        val stableQuery = params.mapIndexedNotNull { index, part ->
            val name = decodedNames[index]
            when {
                name in genericCredentialParameterNames && canStripGenericCredentials -> null
                name in genericCredentialParameterNames -> {
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', missingDelimiterValue = "")
                    "$key=${sha256(value)}"
                }
                name in hlsSignedParameterNames && hasHlsSignedQuery -> null
                name in authParameterNames -> null
                hasAwsSignature && name.startsWith("x-amz-") -> null
                hasGoogleSignature && name.startsWith("x-goog-") -> null
                hasAzureSignature && name in azureSasParameterNames -> null
                else -> part
            }
        }.sorted()
        val querySuffix = stableQuery.takeIf { it.isNotEmpty() }
            ?.joinToString("&", prefix = "?")
            .orEmpty()
        return "$scheme://$authority$rawPath$querySuffix"
    }

    /** Identity for subtitle/translation caches; no credentials or URL fragments are retained. */
    fun key(url: String): String = "remote-sha256:${sha256(canonicalUrl(url))}"

    /**
     * Identity for downloaded audio additionally scopes reuse to the current page origin and a
     * one-way digest of its WebView cookie. Cookie bytes are never written into a cache key.
     */
    /** Identity for subtitle/translation caches scoped to the page origin and cookie session. */
    fun remoteSessionKey(url: String, referrer: String, cookie: String?): String =
        "remote-session-sha256:${sha256(sessionScopeMaterial(url, referrer, cookie))}"

    /**
     * Persistent success snapshots must use the same isolation as source/translation caches.
     * Keep the public video key separate because restored PlayerUiState still belongs to that key.
     */
    fun readySnapshotIdentityKey(
        videoKey: String,
        remoteMediaUrl: String?,
        referrer: String,
        cookie: String?,
        localMediaIdentityKey: String? = null,
    ): String = remoteMediaUrl?.takeIf { it.isNotBlank() }?.let {
        "remote-snapshot:${remoteSessionKey(it, referrer, cookie)}"
    } ?: localMediaIdentityKey?.takeIf { it.isNotBlank() }?.let {
        "local-snapshot:$it"
    } ?: videoKey

    /** Downloaded-audio identity uses the same scope but a separate namespace. */
    fun audioKey(url: String, referrer: String, cookie: String?): String =
        "remote-audio-sha256:${sha256(sessionScopeMaterial(url, referrer, cookie))}"

    private fun sessionScopeMaterial(url: String, referrer: String, cookie: String?): String = listOf(
        canonicalUrl(url),
        "referrer-origin=${origin(referrer)}",
        "cookie-sha256=${sha256(cookie.orEmpty())}",
    ).joinToString("\n")

    private fun origin(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: return "invalid-referrer-sha256:${sha256(value.trim())}"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme == null || !hasValidHttpAuthority(uri, scheme) || host.isNullOrBlank()) {
            // Keep malformed referrers isolated rather than assigning every invalid origin the same
            // empty scope, while still storing only a digest of the supplied string.
            return "invalid-referrer-sha256:${sha256(value.trim())}"
        }
        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        val hostForUrl = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$hostForUrl" + if (port >= 0) ":$port" else ""
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
