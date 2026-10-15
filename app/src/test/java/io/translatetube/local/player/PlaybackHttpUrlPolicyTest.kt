package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHttpUrlPolicyTest {
    @Test
    fun acceptsOrdinaryHttpMediaUrls() {
        assertTrue(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example/video.mp4?token=abc"))
        assertTrue(PlaybackHttpUrlPolicy.isValidMediaUrl("http://cdn.example:8080/audio.m3u8"))
    }

    @Test
    fun rejectsEmbeddedCredentialsAndMalformedAuthorities() {
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://user:pass@cdn.example/video.mp4"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example:0/video.mp4"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example:99999/video.mp4"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example:/video.mp4"))
    }

    @Test
    fun rejectsNonHttpSchemesAndControlCharacters() {
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("file:///tmp/video.mp4"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("javascript:alert(1)"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example/video.mp4\r\nX: y"))
        assertFalse(PlaybackHttpUrlPolicy.isValidMediaUrl("https://cdn.example/a video.mp4"))
    }

    @Test
    fun rejectsC1ControlCharactersInHeaders() {
        val sanitized = PlaybackHttpUrlPolicy.sanitizeRequestHeaders(
            mapOf("Cookie" to "session=ok\\u0085Injected: yes"),
        )
        assertTrue(sanitized.isEmpty())
    }

    @Test
    fun removesStaleRangeAndHopByHopHeaders() {
        val sanitized = PlaybackHttpUrlPolicy.sanitizeRequestHeaders(
            mapOf(
                "Range" to "bytes=1024-2048",
                "Host" to "cdn.example",
                "Connection" to "keep-alive",
                "Content-Length" to "123",
                "Cookie" to "session=ok",
                "Referer" to "https://site.example/watch",
            ),
        )
        assertEquals(mapOf("Cookie" to "session=ok", "Referer" to "https://site.example/watch"), sanitized)
    }

    @Test
    fun rejectsHeaderInjectionAndOversizedValues() {
        val sanitized = PlaybackHttpUrlPolicy.sanitizeRequestHeaders(
            mapOf(
                "Cookie" to "session=ok\r\nInjected: yes",
                "Referer" to "javascript:alert(1)",
                "User-Agent" to "x".repeat(8 * 1024 + 1),
                "X-Unknown" to "anything",
            ),
        )
        assertTrue(sanitized.isEmpty())
    }
}
