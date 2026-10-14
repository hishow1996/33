package io.translatetube.local.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRedirectInterceptorTest {
    @Test
    fun stripsCredentialsWhenRedirectChangesOriginButKeepsMediaHeaders() {
        val source = MockWebServer()
        val cdn = MockWebServer()
        source.start()
        cdn.start()
        try {
            source.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", cdn.url("/media.bin").toString())
            )
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("media-bytes"))

            val client = safeClient()
            val request = Request.Builder()
                .url(source.url("/watch/media.bin"))
                .header("Authorization", "Bearer secret-token")
                .header("Cookie", "session=secret")
                .header("Origin", source.url("/").toString().trimEnd('/'))
                .header("Referer", source.url("/watch").toString())
                .header("User-Agent", "TestPlayer/1.0")
                .header("Range", "bytes=0-99")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("media-bytes", response.body?.string())
            }

            val first = source.takeRequest()
            val redirected = cdn.takeRequest()
            assertEquals("Bearer secret-token", first.getHeader("Authorization"))
            assertEquals("session=secret", first.getHeader("Cookie"))
            assertFalse(redirected.headers.names().any { it.equals("Authorization", true) })
            assertFalse(redirected.headers.names().any { it.equals("Cookie", true) })
            assertFalse(redirected.headers.names().any { it.equals("Origin", true) })
            assertEquals(source.url("/watch").toString(), redirected.getHeader("Referer"))
            assertEquals("TestPlayer/1.0", redirected.getHeader("User-Agent"))
            assertEquals("bytes=0-99", redirected.getHeader("Range"))
        } finally {
            source.shutdown()
            cdn.shutdown()
        }
    }

    @Test
    fun keepsCredentialsAcrossSameOriginRedirects() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/final")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            safeClient().newCall(
                Request.Builder()
                    .url(server.url("/start"))
                    .header("Authorization", "Bearer same-origin")
                    .header("Cookie", "session=same-origin")
                    .header("Origin", server.url("/").toString().trimEnd('/'))
                    .build()
            ).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("ok", response.body?.string())
            }

            server.takeRequest()
            val redirected = server.takeRequest()
            assertEquals("Bearer same-origin", redirected.getHeader("Authorization"))
            assertEquals("session=same-origin", redirected.getHeader("Cookie"))
            assertTrue(redirected.headers.names().any { it.equals("Origin", true) })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun enforcesRedirectLimit() {
        val server = MockWebServer()
        server.start()
        try {
            repeat(3) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/loop-${it + 1}")
                )
            }
            val limited = runCatching {
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addInterceptor(SafeRedirectInterceptor(maxRedirects = 1))
                    .build()
                    .newCall(Request.Builder().url(server.url("/loop")).build())
                    .execute()
                    .use { it.code }
            }.exceptionOrNull()
            assertTrue(limited is java.io.IOException)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun safeClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(SafeRedirectInterceptor())
        .build()
}
