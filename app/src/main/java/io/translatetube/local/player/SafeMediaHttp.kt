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
        allowedCookieHost: String? = null,
        userAgent: String = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36",
    ): Response {
        val initial = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("媒体 URL 不是有效的 HTTP/HTTPS 地址。")
        if (initial.scheme !in setOf("http", "https") || initial.username.isNotEmpty() || initial.password.isNotEmpty()) {
            throw IllegalArgumentException("媒体 URL 不是有效的 HTTP/HTTPS 地址。")
        }
        val cookieHost = (allowedCookieHost ?: initial.host).lowercase(Locale.ROOT)
        var current: HttpUrl = initial

        for (redirectCount in 0..MAX_REDIRECTS) {
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
            requestHeaders.forEach { (name, value) ->
                if (value.isBlank()) return@forEach
                when {
                    name.equals("Referer", ignoreCase = true) -> {
                        if (value.toHttpUrlOrNull()?.scheme in setOf("http", "https")) {
                            builder.header("Referer", value)
                        }
                    }
                    name.equals("Cookie", ignoreCase = true) &&
                        current.host.lowercase(Locale.ROOT) == cookieHost -> {
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
            current = next
        }
        throw IOException("媒体地址重定向失败。")
    }
}
