package io.translatetube.local.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMediaHttpTest {
    @Test
    fun doesNotForwardWebViewCookieAcrossHostRedirect() {
        MockWebServer().use { source ->
            MockWebServer().use { redirected ->
                source.start()
                redirected.start()
                val redirectedUrl = redirected.url("/audio.bin").toString()
                    .replace("localhost", "127.0.0.1")
                source.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", redirectedUrl)
                )
                redirected.enqueue(MockResponse().setBody("audio"))

                SafeMediaHttp.execute(
                    url = source.url("/start").toString(),
                    requestHeaders = mapOf(
                        "Cookie" to "session=private",
                        "Referer" to "https://page.example/watch",
                    ),
                ).use { response ->
                    assertTrue(response.isSuccessful)
                    assertEquals("audio", response.body?.string())
                }

                assertEquals("session=private", source.takeRequest().getHeader("Cookie"))
                assertNull(redirected.takeRequest().getHeader("Cookie"))
            }
        }
    }

    @Test
    fun recognizesCommonHtmlAndJsonErrorPayloads() {
        assertTrue(isHtmlOrJsonContentType("text/html; charset=utf-8"))
        assertTrue(isHtmlOrJsonContentType("application/problem+json"))
        assertTrue(isHtmlOrJsonContentType("application/json"))
        assertEquals(false, isHtmlOrJsonContentType("application/octet-stream"))
        assertEquals(false, isHtmlOrJsonContentType("application/vnd.apple.mpegurl"))
    }
}
