package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVideoProbePolicyTest {
    @Test
    fun rejectsEmptyAndOversizedBridgePayloads() {
        assertFalse(WebVideoProbePolicy.acceptsPayload(""))
        assertFalse(WebVideoProbePolicy.acceptsPayload("x".repeat(WebVideoProbePolicy.MAX_PAYLOAD_CHARS + 1)))
        assertTrue(WebVideoProbePolicy.acceptsPayload("[]"))
    }

    @Test
    fun boundsMediaUrlsAndMetadataLengths() {
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl(""))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("null"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("x".repeat(WebVideoProbePolicy.MAX_MEDIA_URL_CHARS + 1)))
        assertTrue(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/video.m3u8"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://m.yfsp.tv/static/media/scan_qr.mp3"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://m.yfsp.tv/static/media/test.mp4"))
        assertTrue(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/test.mp4"))
        assertTrue(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/episode-audio.mp3"))
        assertEquals(WebVideoProbePolicy.MAX_TITLE_CHARS, WebVideoProbePolicy.normalizeTitle("t".repeat(2000)).length)
        assertEquals("", WebVideoProbePolicy.normalizePoster("p".repeat(5000)))
    }

    @Test
    fun rejectsUnsafeSchemesCredentialsAndInvalidPortsFromPageBridge() {
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("javascript:alert(1)"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("data:video/mp4;base64,abc"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("file:///sdcard/private.mp4"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://user:pass@cdn.example/video.mp4"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example:99999/video.mp4"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/video.mp4" + 13.toChar() + 10.toChar() + "X: yes"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("\nhttps://cdn.example/video.mp4"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/video.mp4\n"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("https://cdn.example/video.mp4" + 0x85.toChar()))
    }

    @Test
    fun keepsBlobForAnInformativeUnsupportedReasonButRejectsUnsafePosterUrls() {
        assertTrue(WebVideoProbePolicy.acceptsMediaUrl("blob:https://site.example/0123"))
        assertFalse(WebVideoProbePolicy.acceptsMediaUrl("blob:javascript:alert(1)"))
        assertEquals("", WebVideoProbePolicy.normalizePoster("file:///sdcard/private.png"))
        assertEquals("", WebVideoProbePolicy.normalizePoster("javascript:alert(1)"))
        assertEquals("", WebVideoProbePolicy.normalizePoster("\nhttps://cdn.example/poster.jpg"))
        assertEquals("", WebVideoProbePolicy.normalizePoster("https://cdn.example/poster.jpg" + 0x85.toChar()))
        assertEquals("https://cdn.example/poster.jpg", WebVideoProbePolicy.normalizePoster("https://cdn.example/poster.jpg"))
    }

    @Test
    fun mergesDuplicateUrlsAndCapsTheRetainedCandidateList() {
        val base = WebVideoCandidate("https://cdn.example/video.mp4", "", "", 0.0, false)
        val update = WebVideoCandidate("https://cdn.example/video.mp4", "Video", "poster", 12.0, true)
        val merged = WebVideoProbePolicy.mergeCandidates(
            existing = listOf(base),
            incoming = listOf(update),
        )
        assertEquals(1, merged.size)
        assertEquals("Video", merged.single().title)
        assertEquals("poster", merged.single().poster)
        assertEquals(12.0, merged.single().durationSeconds, 0.0)
        assertTrue(merged.single().drmProtected)

        val refreshed = WebVideoProbePolicy.mergeCandidates(
            existing = listOf(
                WebVideoCandidate("https://cdn.example/same.mp4", "Old title", "old-poster", 90.0, false)
            ),
            incoming = listOf(
                WebVideoCandidate("https://cdn.example/same.mp4", "Updated title", "new-poster", 12.0, false)
            ),
        )
        assertEquals("Updated title", refreshed.single().title)
        assertEquals("new-poster", refreshed.single().poster)
        assertEquals(12.0, refreshed.single().durationSeconds, 0.0)

        val many = (0 until (WebVideoProbePolicy.MAX_RETAINED_CANDIDATES + 50)).map {
            WebVideoCandidate("https://cdn.example/$it.mp4", "V$it", "", 0.0, false)
        }
        assertEquals(
            WebVideoProbePolicy.MAX_RETAINED_CANDIDATES,
            WebVideoProbePolicy.mergeCandidates(emptyList(), many).size,
        )

        val fullList = many.take(WebVideoProbePolicy.MAX_RETAINED_CANDIDATES)
        val fresh = WebVideoCandidate("https://cdn.example/newly-discovered.mp4", "Fresh", "", 1.0, false)
        val afterDiscovery = WebVideoProbePolicy.mergeCandidates(fullList, listOf(fresh))
        assertEquals(WebVideoProbePolicy.MAX_RETAINED_CANDIDATES, afterDiscovery.size)
        assertTrue(afterDiscovery.any { it.url == fresh.url })
        assertFalse(afterDiscovery.any { it.url == fullList.last().url })
    }
}
