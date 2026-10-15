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
        assertEquals(WebVideoProbePolicy.MAX_TITLE_CHARS, WebVideoProbePolicy.normalizeTitle("t".repeat(2000)).length)
        assertEquals(WebVideoProbePolicy.MAX_POSTER_CHARS, WebVideoProbePolicy.normalizePoster("p".repeat(5000)).length)
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

        val many = (0 until (WebVideoProbePolicy.MAX_RETAINED_CANDIDATES + 50)).map {
            WebVideoCandidate("https://cdn.example/$it.mp4", "V$it", "", 0.0, false)
        }
        assertEquals(
            WebVideoProbePolicy.MAX_RETAINED_CANDIDATES,
            WebVideoProbePolicy.mergeCandidates(emptyList(), many).size,
        )
    }
}
