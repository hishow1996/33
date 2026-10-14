package io.translatetube.local.player

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Follows ordinary HTTP redirects while stripping origin-bound credentials on cross-origin hops.
 * Media manifests and segments often redirect to a CDN; captured Cookie/Authorization headers must
 * never be blindly replayed to a different host.
 */
internal object SafeMediaHttp {
    private const val MAX_REDIRECTS = 8
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val credentialHeaders = setOf("authorization", "cookie", "origin")

    @Throws(IOException::class)
    fun execute(client: OkHttpClient, request: Request): Response {
        val noAutoRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = request
        var redirects = 0

        while (true) {
            val response = noAutoRedirect.newCall(current).execute()
            if (response.code !in redirectCodes) return response
            val location = response.header("Location")?.takeIf { it.isNotBlank() }
                ?: return response
            if (redirects >= MAX_REDIRECTS) {
                response.close()
                throw IOException("媒体服务器重定向次数超过限制")
            }

            val nextUrl = current.url.resolve(location)
            if (nextUrl == null ||
                (!nextUrl.scheme.equals("http", true) && !nextUrl.scheme.equals("https", true))
            ) {
                response.close()
                throw IOException("媒体服务器返回了无效的重定向地址")
            }
            if (current.url.isHttps && !nextUrl.isHttps) {
                response.close()
                throw IOException("已阻止媒体下载从 HTTPS 降级重定向到 HTTP")
            }
            val oldOrigin = origin(current.url.scheme, current.url.host, current.url.port)
            val newOrigin = origin(nextUrl.scheme, nextUrl.host, nextUrl.port)
            val builder = current.newBuilder().url(nextUrl)
            if (oldOrigin != newOrigin) {
                credentialHeaders.forEach { builder.removeHeader(it) }
            }
            response.close()
            current = builder.build()
            redirects++
        }
    }

    private fun origin(scheme: String, host: String, port: Int): String =
        "${scheme.lowercase(java.util.Locale.ROOT)}://${host.lowercase(java.util.Locale.ROOT)}:$port"
}
