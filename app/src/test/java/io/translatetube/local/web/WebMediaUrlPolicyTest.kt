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
    }

    @Test
    fun acceptsDirectHttpMediaButRejectsTemporaryAndManifestStreams() {
        assertEquals("https://cdn.example/video.mp4?token=abc",
            WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/video.mp4?token=abc"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("blob:https://example.com/abc"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/master.m3u8"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("https://cdn.example/stream.mpd"))
        assertNull(WebMediaUrlPolicy.normalizeMediaUrl("data:video/mp4;base64,abc"))
    }

    @Test
    fun recognizesDirectMediaRequestsButIgnoresOrdinaryPageResources() {
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/video.mp4?token=1"))
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/master.m3u8"))
        assertEquals(true, WebMediaUrlPolicy.looksLikeMediaRequest("https://cdn.example/stream.mpd"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("https://example.com/watch"))
        assertEquals(false, WebMediaUrlPolicy.looksLikeMediaRequest("blob:https://example.com/id"))
    }

    @Test
    fun givesSpecificReasonForUnsupportedMediaSources() {
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason("blob:https://example.com/1")!!.contains("blob"))
        assertEquals(true, WebMediaUrlPolicy.unsupportedReason("https://cdn.example/index.m3u8")!!.contains("HLS/DASH"))
        assertNull(WebMediaUrlPolicy.unsupportedReason("https://cdn.example/video.mp4"))
    }
}
