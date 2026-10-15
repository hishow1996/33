package io.translatetube.local.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpMediaPolicyTest {
    @Test
    fun acceptsOnlyWellFormedHttpUrlsWithoutEmbeddedCredentials() {
        assertTrue(YtDlpMediaPolicy.isSafeHttpUrl("https://media.example/video.mp4?token=x"))
        assertTrue(YtDlpMediaPolicy.isSafeHttpUrl("http://media.example:8080/watch"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("file:///tmp/video.mp4"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("https://user:pass@media.example/video.mp4"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("https://media.example:99999/video.mp4"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("https://media.example:/video.mp4"))
        assertFalse(YtDlpMediaPolicy.isSafeHttpUrl("not a url"))
    }

    @Test
    fun cookieHeaderRejectsLineBreaksButAllowsMissingOrOrdinaryCookies() {
        assertTrue(YtDlpMediaPolicy.isSafeCookieHeader(null))
        assertTrue(YtDlpMediaPolicy.isSafeCookieHeader(""))
        assertTrue(YtDlpMediaPolicy.isSafeCookieHeader("session=a; theme=dark"))
        assertFalse(YtDlpMediaPolicy.isSafeCookieHeader("session=a\r\nInjected: yes"))
        assertFalse(YtDlpMediaPolicy.isSafeCookieHeader("session=a\n"))
    }

    @Test
    fun prefersCombinedMp4OverHigherBitrateWebmForAndroidCompatibility() {
        val selected = YtDlpMediaPolicy.selectPlayableUrl(
            formats = listOf(
                YtDlpFormatCandidate("https://media.example/high.webm", "webm", true, true, 8_000.0),
                YtDlpFormatCandidate("https://media.example/compat.mp4", "mp4", true, true, 2_000.0),
            ),
            fallbackUrl = null,
        )
        assertEquals("https://media.example/compat.mp4", selected)
    }

    @Test
    fun ignoresMalformedUrlsAndNeverSelectsAudioOnlyAsVideo() {
        val selected = YtDlpMediaPolicy.selectPlayableUrl(
            formats = listOf(
                YtDlpFormatCandidate("file:///tmp/audio.m4a", "m4a", false, true, 300.0),
                YtDlpFormatCandidate("https://media.example/audio.m4a", "m4a", false, true, 500.0),
                YtDlpFormatCandidate("https://media.example/video.mp4", "mp4", true, false, 2_000.0),
                YtDlpFormatCandidate("https://media.example/not-video", null, false, true, 9_000.0),
            ),
            fallbackUrl = "https://media.example/audio.m4a",
        )
        assertNull(selected)
    }

    @Test
    fun recognizesHlsFromThePathRatherThanAnUnrelatedQueryParameter() {
        assertTrue(YtDlpMediaPolicy.isHlsCandidate("https://media.example/master.m3u8?token=x", null))
        assertFalse(YtDlpMediaPolicy.isHlsCandidate("https://media.example/watch?redirect=master.m3u8", null))
        assertFalse(YtDlpMediaPolicy.isHlsCandidate("file:///tmp/master.m3u8", "m3u8"))
        assertNull(
            YtDlpMediaPolicy.selectPlayableUrl(
                listOf(YtDlpFormatCandidate("https://media.example/audio.m3u8", "m3u8", false, true, 10.0)),
                null,
            )
        )
    }

    @Test
    fun directFallbackIsAllowedOnlyWhenNoFormatMetadataExists() {
        assertEquals(
            "https://media.example/direct.mp4",
            YtDlpMediaPolicy.selectPlayableUrl(emptyList(), "https://media.example/direct.mp4"),
        )
        assertNull(
            YtDlpMediaPolicy.selectPlayableUrl(
                listOf(YtDlpFormatCandidate("https://media.example/audio.m4a", "m4a", false, true, 10.0)),
                "https://media.example/direct.mp4",
            )
        )
        assertNull(YtDlpMediaPolicy.selectPlayableUrl(emptyList(), "javascript:alert(1)"))
    }
}
