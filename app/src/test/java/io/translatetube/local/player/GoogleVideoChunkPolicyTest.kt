package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleVideoChunkPolicyTest {
    @Test
    fun chunksOnlyGoogleVideoHosts() {
        assertTrue(GoogleVideoChunkPolicy.shouldChunk("https://rr1---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertTrue(GoogleVideoChunkPolicy.shouldChunk("https://googlevideo.com/videoplayback"))
        assertFalse(GoogleVideoChunkPolicy.shouldChunk("https://example.com/googlevideo.com/a.m4a"))
        assertFalse(GoogleVideoChunkPolicy.shouldChunk("https://evilgooglevideo.com/a.m4a"))
        assertFalse(GoogleVideoChunkPolicy.shouldChunk("not a url"))
    }

    @Test
    fun rangeHeaderCoversOneChunkAndClampsLastChunk() {
        val chunk = GoogleVideoChunkPolicy.CHUNK_BYTES
        assertEquals("bytes=0-${chunk - 1}", GoogleVideoChunkPolicy.rangeHeader(0L, -1L))
        assertEquals("bytes=$chunk-${2 * chunk - 1}", GoogleVideoChunkPolicy.rangeHeader(chunk, 5 * chunk))
        assertEquals("bytes=${chunk}-${chunk + 99}", GoogleVideoChunkPolicy.rangeHeader(chunk, chunk + 100))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(
            GoogleVideoChunkPolicy.ContentRange(0L, 99L, 1000L),
            GoogleVideoChunkPolicy.parseContentRange("bytes 0-99/1000"),
        )
        assertEquals(
            GoogleVideoChunkPolicy.ContentRange(100L, 199L, null),
            GoogleVideoChunkPolicy.parseContentRange(" bytes 100-199/* "),
        )
        assertNull(GoogleVideoChunkPolicy.parseContentRange(null))
        assertNull(GoogleVideoChunkPolicy.parseContentRange("bytes 200-100/1000"))
        assertNull(GoogleVideoChunkPolicy.parseContentRange("bytes 0-1000/1000"))
        assertNull(GoogleVideoChunkPolicy.parseContentRange("items 0-9/10"))
    }
}
