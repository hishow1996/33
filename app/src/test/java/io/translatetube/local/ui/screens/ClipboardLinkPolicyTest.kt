package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkPolicyTest {
    @Test
    fun youtubeLinkIsSuggested() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(url, ClipboardLinkPolicy.candidate(url, null))
    }

    @Test
    fun linkInsideSharedTextIsExtracted() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            ClipboardLinkPolicy.candidate("看这个视频 https://youtu.be/dQw4w9WgXcQ 很有意思", null),
        )
    }

    @Test
    fun plainTextEmptyAndOversizedClipboardAreIgnored() {
        assertNull(ClipboardLinkPolicy.candidate(null, null))
        assertNull(ClipboardLinkPolicy.candidate("   ", null))
        assertNull(ClipboardLinkPolicy.candidate("今天天气不错", null))
        val big = "https://example.com/" + "a".repeat(ClipboardLinkPolicy.MAX_CLIP_CHARS)
        assertNull(ClipboardLinkPolicy.candidate(big, null))
    }

    @Test
    fun youtubeVariantsAreAllRecognized() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=30",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id",
            "youtu.be/$id",
        ).forEach { text ->
            val url = ClipboardLinkPolicy.candidate(text, null)
            assertNotNull(text, url)
            assertEquals(text, ClipboardLinkPolicy.Kind.YOUTUBE, ClipboardLinkPolicy.kind(url!!))
        }
    }

    @Test
    fun webPageAndDirectMediaLinksAreRecognizedWithTheirOwnKind() {
        val page = ClipboardLinkPolicy.candidate("【标题】 https://www.bilibili.com/video/BV1xx411c7mD", null)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", page)
        assertEquals(ClipboardLinkPolicy.Kind.WEB_PAGE, ClipboardLinkPolicy.kind(page!!))

        val media = ClipboardLinkPolicy.candidate("https://cdn.example.com/video/movie.mp4", null)
        assertEquals("https://cdn.example.com/video/movie.mp4", media)
        assertEquals(ClipboardLinkPolicy.Kind.DIRECT_MEDIA, ClipboardLinkPolicy.kind(media!!))

        val hls = ClipboardLinkPolicy.candidate("https://cdn.example.com/live/index.m3u8?token=1", null)
        assertEquals(ClipboardLinkPolicy.Kind.DIRECT_MEDIA, ClipboardLinkPolicy.kind(hls!!))
    }

    @Test
    fun alreadyHandledLinkIsNotSuggestedAgain() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertNull(ClipboardLinkPolicy.candidate(url, url))
        assertEquals(url, ClipboardLinkPolicy.candidate(url, "https://youtu.be/other"))
    }

    @Test
    fun labelDropsSchemeAndWwwAndTruncatesLongLinks() {
        assertEquals("youtube.com/watch?v=abc", ClipboardLinkPolicy.label("https://www.youtube.com/watch?v=abc"))
        val label = ClipboardLinkPolicy.label("https://example.com/" + "x".repeat(200))
        assertEquals(48, label.length)
        assertEquals('…', label.last())
    }
}
