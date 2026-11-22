package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
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
