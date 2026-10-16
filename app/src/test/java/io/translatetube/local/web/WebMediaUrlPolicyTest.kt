package io.translatetube.local.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMediaUrlPolicyTest {
    @Test
    fun normalizesWebsiteUrlWithoutAcceptingOtherSchemes() {
        assertEquals("https://example.com/watch", WebMediaUrlPolicy.normalizePageUrl("example.com/watch"))
        assertEquals("http://example.com/video", WebMediaUrlPolicy.normalizePageUrl("http://example.com/video"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("javascript:alert(1)"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("file:///etc/passwd"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("https://user:pass@example.com/video"))
        // Common bare host:port input must not be misread as an unsupported URI scheme.
        assertEquals("https://example.com:8080/watch", WebMediaUrlPolicy.normalizePageUrl("example.com:8080/watch"))
        assertEquals("https://localhost:3000", WebMediaUrlPolicy.normalizePageUrl("localhost:3000"))
        assertEquals("https://[::1]:8080/watch", WebMediaUrlPolicy.normalizePageUrl("[::1]:8080/watch"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("javascript:8080/alert(1)"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("file:8080/etc/passwd"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("https://example.com:99999/watch"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("https://example.com:0/watch"))
    }

    @Test
    fun acceptsDirectHttpAndHlsMediaButRejectsTemporaryAndDashStreams() {
        assertEquals("https://cdn.example/video.mp4?token=abc",
            WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/video.mp4?token=abc"))
        assertEquals("https://cdn.example/master.m3u8?token=abc",
            WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/master.m3u8?token=abc"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("blob:https://example.com/abc"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/stream.mpd"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("data:video/mp4;base64,abc"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example:99999/video.mp4"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example:0/video.mp4"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://m.yfsp.tv/static/media/test.mp4"))
        assertEquals(
            true,
            WebMediaUrlPolicy.unsupportedReason("https://m.yfsp.tv/static/media/test.mp4")!!.contains("占位视频"),
        )
        assertEquals(
            "https://cdn.example/static/media/test.mp4",
            WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/static/media/test.mp4"),
        )
    }

    @Test
    fun recognizesDirectMediaRequestsButIgnoresOrdinaryPageResources() {
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/video.mp4?token=1"))
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/master.m3u8"))
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/stream.mpd"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("https://example.com/watch"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("blob:https://example.com/id"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example:99999/video.mp4"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("https://user:pass@cdn.example/video.mp4"))
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason("https://cdn.example:99999/video.mp4") != null)
    }

    @Test
    fun rejectsControlCharactersBeforeTrimmingPageAndMediaUrls() {
        val page = "https://example.com/watch"
        val media = "https://cdn.example/video.mp4"
        assertNull(WebMediaUrlPolicy.normalizePageUrl("\n$page"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl("$page\n"))
        assertNull(WebMediaUrlPolicy.normalizePageUrl(page + 0x85.toChar()))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("\n$media"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("$media\n"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl(media + 0x85.toChar()))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest(media + "\n"))
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason(media + "\n")!!.contains("控制字符"))
    }

    @Test
    fun onlyExplicitAudioExtensionsCanUseAudioFileFallback() {
        assertEquals(true, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/track.m4a?token=abc"))
        assertEquals(true, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/track.MP3"))
        assertEquals(true, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/track.opus?expires=123"))
        assertEquals(false, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/movie.mp4"))
        assertEquals(false, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/master.m3u8"))
        assertEquals(false, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/media?format=mp3"))
        assertEquals(false, WebMediaUrlPolicy.isExplicitAudioOnlyResource("https://cdn.example/track.mp3\n"))
    }

    @Test
    fun uppercaseMediaExtensionsRemainRecognizedAcrossLocales() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/VIDEO.MP4"))
            assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/INDEX.M3U8"))
            assertEquals(true, WebMediaUrlPolicy.unsupportedReason("https://cdn.example/INDEX.MPD")!!.contains("DASH"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun givesSpecificReasonForUnsupportedMediaSources() {
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason("blob:https://example.com/1")!!.contains("blob"))
        assertNull(WebMediaUrlPolicy.unsupportedReason("https://cdn.example/index.m3u8"))
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason("https://cdn.example/index.mpd")!!.contains("DASH"))
        assertNull(WebMediaUrlPolicy.unsupportedReason("https://cdn.example/video.mp4"))
    }
}
