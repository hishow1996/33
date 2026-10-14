package io.translatetube.local.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * HTTP GET helper for media/cache downloads.
 *
 * Redirects are followed manually so a Cookie header captured from the current WebView is only
 * sent to the original media host. OkHttp's automatic redirect handling can preserve custom
 * headers across host changes; this helper intentionally does not.
 */
internal object SafeMediaHttp {
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun execute(
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
        allowedCookieOrigin: String? = null,
        userAgent: String = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36",
    ): Response {
        val initial = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("媒体 URL 不是有效的 HTTP/HTTPS 地址。")
        if (initial.scheme !in setOf("http", "https") || initial.username.isNotEmpty() || initial.password.isNotEmpty()) {
            throw IllegalArgumentException("媒体 URL 不是有效的 HTTP/HTTPS 地址。")
        }
        val cookieOrigin = allowedCookieOrigin?.toHttpUrlOrNull() ?: initial
        var current: HttpUrl = initial

        for (redirectCount in 0..MAX_REDIRECTS) {
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
            requestHeaders.forEach { (name, value) ->
                if (value.isBlank()) return@forEach
                when {
                    name.equals("Referer", ignoreCase = true) -> {
                        val refererScheme = value.toHttpUrlOrNull()?.scheme
                        if (refererScheme == "http" || refererScheme == "https") {
                            builder.header("Referer", value)
                        }
                    }
                    name.equals("Cookie", ignoreCase = true) &&
                        current.scheme == cookieOrigin.scheme &&
                        current.host.lowercase(Locale.ROOT) == cookieOrigin.host.lowercase(Locale.ROOT) &&
                        current.port == cookieOrigin.port -> {
                        builder.header("Cookie", value)
                    }
                }
            }

            val response = client.newCall(builder.build()).execute()
            val location = response.header("Location")
            if (response.code !in redirectCodes || location.isNullOrBlank()) return response
            if (redirectCount == MAX_REDIRECTS) {
                response.close()
                throw IOException("媒体地址重定向次数超过 $MAX_REDIRECTS 次。")
            }
            val next = response.request.url.resolve(location)
            response.close()
            if (next == null || next.scheme !in setOf("http", "https") ||
                next.host.isBlank() || next.username.isNotEmpty() || next.password.isNotEmpty()
            ) {
                throw IOException("媒体地址跳转到了无效或不允许的 URL。")
            }
            if (!isAllowedRedirect(current.scheme, next.scheme)) {
                throw IOException("媒体地址试图从 HTTPS 降级到 HTTP，已阻止跳转以保护会话数据。")
            }
            current = next
        }
        throw IOException("媒体地址重定向失败。")
    }
}

/** Refuse insecure redirects after a secure request; never downgrade session media URLs. */
internal fun isAllowedRedirect(fromScheme: String, toScheme: String): Boolean =
    fromScheme in setOf("http", "https") &&
        toScheme in setOf("http", "https") &&
        !(fromScheme == "https" && toScheme == "http")

/** Identifies common error pages/API payloads accidentally returned in place of media bytes. */
internal fun isHtmlOrJsonContentType(contentType: String?): Boolean {
    val value = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
    return value == "text/html" ||
        value == "application/json" ||
        value == "text/json" ||
        value == "application/xml" ||
        value == "text/xml" ||
        value.endsWith("+json")
}

/** Raised when a direct media URL actually responds with an HLS playlist. */
internal class HlsManifestPayloadException(val manifestUrl: String) :
    IllegalStateException("检测到 HLS 播放清单，将改用分段音轨下载。")

/** Detect HLS from its body when a playlist URL has no .m3u8 path suffix. */
internal fun isHlsManifestPayload(bytes: ByteArray, count: Int): Boolean {
    if (count <= 0 || bytes.isEmpty()) return false
    val sampleSize = minOf(count, bytes.size, 512)
    val text = bytes.copyOfRange(0, sampleSize)
        .toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")
        .trimStart()
    val firstLine = text.lineSequence().firstOrNull()?.trim()?.trimEnd('\r') ?: return false
    return firstLine.equals("#EXTM3U", ignoreCase = true)
}

/**
 * Detect common login/error documents when servers mislabel them as generic binary data.
 * Only inspect a small prefix and require it to look like readable text so binary media
 * headers/containers are not mistaken for an error page.
 */
internal fun isLikelyTextErrorPayload(bytes: ByteArray, count: Int): Boolean {
    if (count <= 0 || bytes.isEmpty()) return false
    val sampleSize = minOf(count, bytes.size, 512)
    val text = bytes.copyOfRange(0, sampleSize)
        .toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")
        .trimStart()
    if (text.isEmpty()) return false
    val printable = text.count { char ->
        char == '\n' || char == '\r' || char == '\t' || char.toInt() >= 0x20
    }
    if (printable.toFloat() / text.length < 0.85f) return false
    val normalized = text.lowercase(Locale.ROOT)
    return normalized.startsWith("<!doctype html") ||
        normalized.startsWith("<html") ||
        normalized.startsWith("<?xml") ||
        normalized.startsWith("{") ||
        normalized.startsWith("[") ||
        normalized.startsWith("#extm3u") ||
        normalized.startsWith("access denied") ||
        normalized.startsWith("unauthorized") ||
        normalized.startsWith("forbidden") ||
        normalized.startsWith("request has expired") ||
        normalized.startsWith("signature has expired") ||
        normalized.startsWith("invalid signature") ||
        normalized.startsWith("error:")
}
