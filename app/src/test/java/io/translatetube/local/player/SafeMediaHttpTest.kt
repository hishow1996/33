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

    @Test
    fun detectsTextErrorDocumentsMislabeledAsBinaryMedia() {
        assertTrue(
            isLikelyTextErrorPayload(
                "<!doctype html><html><body>Sign in</body></html>".toByteArray(Charsets.UTF_8),
                51,
            )
        )
        assertTrue(
            isLikelyTextErrorPayload(
                """{"error":"signature expired"}""".toByteArray(Charsets.UTF_8),
                29,
            )
        )
        assertTrue(
            isLikelyTextErrorPayload(
                "#EXTM3U\n#EXT-X-VERSION:3".toByteArray(Charsets.UTF_8),
                24,
            )
        )
        assertEquals(
            false,
            isLikelyTextErrorPayload(byteArrayOf(0, 0, 0, 24, 'f'.toByte(), 't'.toByte(), 'y'.toByte(), 'p'.toByte()), 8),
        )
        assertEquals(
            false,
            isLikelyTextErrorPayload(byteArrayOf(0x47, 0x40, 0x11, 0x10, 0x00), 5),
        )
    }

    @Test
    fun blocksHttpsDowngradeButAllowsSameSecurityLevelRedirects() {
        assertEquals(false, isAllowedRedirect("https", "http"))
        assertEquals(true, isAllowedRedirect("https", "https"))
        assertEquals(true, isAllowedRedirect("http", "http"))
        assertEquals(false, isAllowedRedirect("file", "https"))
    }


    @Test
    fun doesNotForwardCookieToDifferentPortOnTheSameHost() {
        MockWebServer().use { source ->
            MockWebServer().use { redirected ->
                source.start()
                redirected.start()
                source.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", redirected.url("/audio.bin").toString())
                )
                redirected.enqueue(MockResponse().setBody("audio"))

                SafeMediaHttp.execute(
                    url = source.url("/start").toString(),
                    requestHeaders = mapOf("Cookie" to "session=private"),
                ).use { response ->
                    assertTrue(response.isSuccessful)
                }

                assertEquals("session=private", source.takeRequest().getHeader("Cookie"))
                assertNull(redirected.takeRequest().getHeader("Cookie"))
            }
        }
    }

}
