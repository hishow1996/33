package io.translatetube.local.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMediaCandidatePolicyTest {
    @Test
    fun classifiesDirectFilesHlsAndDashUrls() {
        assertEquals(
            WebMediaKind.VIDEO_FILE,
            WebMediaCandidatePolicy.classify("https://media.example.test/clip.mp4?token=abc")?.kind
        )
        assertEquals(
            WebMediaKind.AUDIO_FILE,
            WebMediaCandidatePolicy.classify("https://media.example.test/audio.m4a")?.kind
        )
        assertEquals(
            WebMediaKind.HLS,
            WebMediaCandidatePolicy.classify("https://media.example.test/master.m3u8?sig=abc")?.kind
        )
        assertEquals(
            WebMediaKind.DASH,
            WebMediaCandidatePolicy.classify("https://media.example.test/manifest.mpd")?.kind
        )
    }

    @Test
    fun recognizesMimeTypeAndPlayerElementWhenUrlHasNoUsefulExtension() {
        assertEquals(
            WebMediaKind.VIDEO_FILE,
            WebMediaCandidatePolicy.classify(
                "https://cdn.example.test/play?id=1",
                mimeType = "video/mp4; codecs=avc1",
            )?.kind
        )
        val opaqueVideo = WebMediaCandidatePolicy.classify(
            "https://cdn.example.test/play?id=1",
            mediaElement = "video",
        )
        assertEquals(WebMediaKind.MEDIA_ELEMENT, opaqueVideo?.kind)
        assertNull(opaqueVideo?.mimeType)

        val opaqueAudio = WebMediaCandidatePolicy.classify(
            "https://cdn.example.test/sound?id=1",
            mediaElement = "audio",
        )
        assertEquals(WebMediaKind.MEDIA_ELEMENT, opaqueAudio?.kind)
        assertNull(opaqueAudio?.mimeType)
    }

    @Test
    fun recognizesManifestFormatsDeclaredInQueryParameters() {
        val hls = WebMediaCandidatePolicy.classify(
            "https://cdn.example.test/play?id=1&format=m3u8",
            mimeType = "video/mp4",
            mediaElement = "video",
        )
        assertEquals(WebMediaKind.HLS, hls?.kind)
        assertEquals("application/x-mpegURL", hls?.mimeType)

        val dash = WebMediaCandidatePolicy.classify(
            "https://cdn.example.test/play?id=1&format=mpd",
            mediaElement = "video",
        )
        assertEquals(WebMediaKind.DASH, dash?.kind)
        assertEquals("application/dash+xml", dash?.mimeType)
    }

    @Test
    fun rejectsNonHttpSchemesAndUnsafeUserInfoUrls() {
        assertNull(WebMediaCandidatePolicy.classify("blob:https://example.test/id"))
        assertNull(WebMediaCandidatePolicy.classify("file:///sdcard/video.mp4"))
        assertNull(WebMediaCandidatePolicy.classify("javascript:alert(1)"))
        assertNull(WebMediaCandidatePolicy.classify("https://user:pass@example.test/movie.mp4"))
    }

    @Test
    fun onlyAllowsUsefulSafeRequestHeaders() {
        val result = WebMediaCandidatePolicy.safeHeaders(
            mapOf(
                "Referer" to "https://page.example.test/watch",
                "Cookie" to "sid=abc",
                "Range" to "bytes=0-99",
                "Host" to "evil.example.test",
                "Authorization" to "Bearer abc",
                "X-Bad" to "line1\nline2",
                "Accept-Language" to "en-US,en;q=0.8",
            )
        )

        assertEquals("https://page.example.test/watch", result["Referer"])
        assertEquals("sid=abc", result["Cookie"])
        assertEquals("Bearer abc", result["Authorization"])
        assertEquals("en-US,en;q=0.8", result["Accept-Language"])
        assertFalse(result.containsKey("Range"))
        assertFalse(result.containsKey("Host"))
        assertFalse(result.containsKey("X-Bad"))
    }

    @Test
    fun normalizesSimpleWebAddressesButRejectsDangerousSchemes() {
        assertEquals(
            "https://example.test/watch?v=1",
            WebMediaCandidatePolicy.normalizePageUrl("example.test/watch?v=1")
        )
        assertTrue(WebMediaCandidatePolicy.normalizePageUrl("http://example.test") != null)
        assertNull(WebMediaCandidatePolicy.normalizePageUrl("file:///sdcard/video.mp4"))
        assertNull(WebMediaCandidatePolicy.normalizePageUrl("javascript:alert(1)"))
    }
}
