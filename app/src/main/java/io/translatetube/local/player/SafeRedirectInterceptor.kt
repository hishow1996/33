package io.translatetube.local.player

import java.io.IOException
import java.util.Locale
import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Explicit HTTP(S) redirects for Media3's OkHttp data source.
 *
 * OkHttp's automatic redirects are disabled on the client using this interceptor. On a redirect
 * to a different origin, captured session credentials are stripped before the next request. Normal
 * media headers (Range, User-Agent, Referer, Accept-Language) survive redirects. HTTPS-to-HTTP
 * downgrades and non-HTTP redirect targets are rejected.
 */
internal class SafeRedirectInterceptor(
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) : Interceptor {
    init {
        require(maxRedirects in 0..MAX_ALLOWED_REDIRECTS) { "媒体重定向上限无效" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var redirects = 0

        while (true) {
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response

            val location = response.header("Location")?.takeIf { it.isNotBlank() }
                ?: return response
            if (redirects >= maxRedirects) {
                response.close()
                throw IOException("网页媒体重定向次数超过限制")
            }

            val nextUrl = request.url.resolve(location)
            if (nextUrl == null ||
                (!nextUrl.scheme.equals("http", true) && !nextUrl.scheme.equals("https", true))
            ) {
                response.close()
                throw IOException("网页媒体返回了无效的 HTTP/HTTPS 重定向地址")
            }
            if (request.url.isHttps && !nextUrl.isHttps) {
                response.close()
                throw IOException("已阻止网页媒体从 HTTPS 降级重定向到 HTTP")
            }

            val crossOrigin = origin(request) != origin(nextUrl.scheme, nextUrl.host, nextUrl.port)
            val builder = request.newBuilder().url(nextUrl)
            if (crossOrigin) {
                CREDENTIAL_HEADERS.forEach { name -> builder.removeHeader(name) }
            }
            // Cookie scope also includes path, so never replay the previous URL's Cookie header.
            // Re-read the cookie jar for the exact redirected URL instead.
            builder.removeHeader("Cookie")
            runCatching { CookieManager.getInstance().getCookie(nextUrl.toString()) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.header("Cookie", it) }
            response.close()
            request = builder.build()
            redirects++
        }
    }

    private fun origin(request: Request): String =
        origin(request.url.scheme, request.url.host, request.url.port)

    private fun origin(scheme: String, host: String, port: Int): String =
        "${scheme.lowercase(Locale.ROOT)}://${host.lowercase(Locale.ROOT)}:$port"

    companion object {
        private const val DEFAULT_MAX_REDIRECTS = 8
        private const val MAX_ALLOWED_REDIRECTS = 16
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CREDENTIAL_HEADERS = setOf("Authorization", "Cookie", "Origin", "Proxy-Authorization")
    }
}
