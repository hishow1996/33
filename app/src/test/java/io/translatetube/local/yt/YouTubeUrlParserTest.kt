package io.translatetube.local.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun extractsWatchUrlsWithOtherQueryParameters() {
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?list=PL123&v=$id&t=32"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://music.youtube.com/watch?v=$id"))
    }

    @Test
    fun supportsShortsLiveEmbedAndLegacyVideoPaths() {
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube-nocookie.com/embed/$id"))
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/v/$id"))
    }

    @Test
    fun supportsYoutuBeAndRawVideoIds() {
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://youtu.be/$id?si=123"))
        assertEquals(id, YouTubeUrlParser.extractVideoId(id))
        assertEquals(id, YouTubeUrlParser.extractVideoId("  $id  "))
    }

    @Test
    fun decodesPercentEncodedWatchIdsAndRejectsInvalidPorts() {
        assertEquals(id, YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=%64Qw4w9WgXcQ"))
        assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com:0/watch?v=$id"))
        assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com:99999/watch?v=$id"))
    }

    @Test
    fun rejectsNonYouTubeHostsAndInvalidIds() {
        assertNull(YouTubeUrlParser.extractVideoId("https://notyoutube.com/watch?v=$id"))
        assertNull(YouTubeUrlParser.extractVideoId("https://example.com/?v=$id"))
        assertNull(YouTubeUrlParser.extractVideoId("https://youtu.be/short"))
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com/watch?v=too-short"))
        assertNull(YouTubeUrlParser.extractVideoId(""))
    }
}
