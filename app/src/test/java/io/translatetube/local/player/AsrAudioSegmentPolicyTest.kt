package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioSegmentPolicyTest {
    @Test
    fun rangeEndClampsToKnownTotalAndAvoidsOverflow() {
        assertEquals(99L, AsrAudioSegmentPolicy.rangeEnd(0L, 100L, 100L))
        assertEquals(199L, AsrAudioSegmentPolicy.rangeEnd(100L, 100L, 200L))
        assertEquals(499L, AsrAudioSegmentPolicy.rangeEnd(400L, 100L, 500L))
        assertEquals(Long.MAX_VALUE, AsrAudioSegmentPolicy.rangeEnd(Long.MAX_VALUE - 3L, 100L, null))
    }

    @Test
    fun parsesSatisfiedUnsatisfiedAndUnknownTotalRanges() {
        assertEquals(
            AsrAudioSegmentPolicy.ContentRange(0L, 4194303L, 10485760L),
            AsrAudioSegmentPolicy.parseContentRange("bytes 0-4194303/10485760")
        )
        assertEquals(
            AsrAudioSegmentPolicy.ContentRange(100L, 199L, null),
            AsrAudioSegmentPolicy.parseContentRange("bytes 100-199/*")
        )
        assertEquals(
            AsrAudioSegmentPolicy.ContentRange(null, null, 10485760L),
            AsrAudioSegmentPolicy.parseContentRange("bytes */10485760")
        )
        assertNull(AsrAudioSegmentPolicy.parseContentRange("bytes 100-99/200"))
        assertNull(AsrAudioSegmentPolicy.parseContentRange("bytes 0-200/200"))
        assertNull(AsrAudioSegmentPolicy.parseContentRange("garbage"))
    }

    @Test
    fun partialResumeRequiresSameSourceAndEitherSameUrlOrValidator() {
        fun canResume(
            source: String = "video-1",
            urlHash: String = "url-a",
            savedSource: String? = "video-1",
            savedUrl: String? = "url-a",
            etag: String? = null,
            lastModified: String? = null,
            rangeSupported: Boolean? = true,
            bytes: Long = 1024L,
        ) = AsrAudioSegmentPolicy.canResumePartial(
            partialBytes = bytes,
            maxBytes = 10_000L,
            requestedSourceKey = source,
            currentUrlHash = urlHash,
            savedSourceKey = savedSource,
            savedUrlHash = savedUrl,
            etag = etag,
            lastModified = lastModified,
            rangeSupported = rangeSupported,
        )

        assertTrue(canResume())
        assertTrue(canResume(urlHash = "url-b", etag = "\"v1\""))
        assertTrue(canResume(urlHash = "url-b", lastModified = "Wed, 01 Jan 2025 00:00:00 GMT"))
        assertFalse(canResume(urlHash = "url-b"))
        assertFalse(canResume(source = "video-2"))
        assertFalse(canResume(rangeSupported = false))
        assertFalse(canResume(bytes = 0L))
        assertFalse(canResume(bytes = 10_001L))
    }

    @Test
    fun detectsErrorPagesAndManifestsButAllowsBinaryMedia() {
        assertTrue(AsrAudioSegmentPolicy.isClearlyNotAudio("text/html; charset=utf-8", byteArrayOf()))
        assertTrue(AsrAudioSegmentPolicy.isClearlyNotAudio("application/json", byteArrayOf()))
        assertTrue(AsrAudioSegmentPolicy.isClearlyNotAudio(null, "#EXTM3U\n#EXTINF:4".toByteArray()))
        assertTrue(AsrAudioSegmentPolicy.isClearlyNotAudio(null, "<!doctype html><html>".toByteArray()))
        assertTrue(AsrAudioSegmentPolicy.isDashManifest("application/dash+xml", byteArrayOf()))
        assertTrue(AsrAudioSegmentPolicy.isDashManifest("application/xml", "<MPD type=\"static\">".toByteArray()))
        assertTrue(AsrAudioSegmentPolicy.isDashManifest("text/xml", "<?xml version=\"1.0\"?><MPD>".toByteArray()))
        assertFalse(AsrAudioSegmentPolicy.isDashManifest("application/xml", "<html><body>Login</body>".toByteArray()))
        assertFalse(AsrAudioSegmentPolicy.isClearlyNotAudio("audio/mp4", byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
        assertFalse(AsrAudioSegmentPolicy.isClearlyNotAudio("application/octet-stream", byteArrayOf(0, 1, 2, 3)))
    }
}
