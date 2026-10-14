package io.translatetube.local.player

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAudioSegmentCacheTest {
    @Test
    fun downloadsClearVodSegmentsAndAssemblesThemInPlaylistOrder() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "hls-audio-cache-test")
        server.start()
        try {
            server.enqueue(playlistResponse("""
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                seg-1.ts
                #EXTINF:4.0,
                seg-2.ts
                #EXT-X-ENDLIST
            """.trimIndent()))
            server.enqueue(segmentResponse("AUDIO-ONE"))
            server.enqueue(segmentResponse("AUDIO-TWO"))

            val destination = File(directory, "audio.bin")
            val result = HlsAudioSegmentCache(
                cacheDir = directory,
                maxOutputBytes = 100,
                maxSegmentBytes = 100,
            ).download(
                playlistUrl = server.url("/media/list.m3u8").toString(),
                destination = destination,
                sourceKey = "web-video-1",
            )

            assertEquals(destination.absolutePath, result.absolutePath)
            assertArrayEquals("AUDIO-ONEAUDIO-TWO".toByteArray(), result.readBytes())
            assertEquals(3, server.requestCount)
            assertEquals("/media/seg-1.ts", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/media/seg-2.ts", server.takeRequest().requestUrl?.encodedPath)
            assertTrue(File(directory, "asr_hls_segments").listFiles().orEmpty().isEmpty())
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsCompletedSegmentsAfterAssemblyFailureAndReusesThemOnRetry() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "hls-audio-retry-test")
        server.start()
        try {
            val playlist = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                seg-1.ts
                #EXTINF:4.0,
                seg-2.ts
                #EXT-X-ENDLIST
            """.trimIndent()
            server.enqueue(playlistResponse(playlist))
            server.enqueue(segmentResponse("AUDIO-ONE"))
            server.enqueue(segmentResponse("AUDIO-TWO"))

            val destination = File(directory, "audio.bin")
            val playlistUrl = server.url("/media/list.m3u8").toString()
            val tooSmall = runCatching {
                HlsAudioSegmentCache(directory, maxOutputBytes = 4, maxSegmentBytes = 100)
                    .download(playlistUrl, destination, "web-video-retry")
            }.exceptionOrNull()
            assertTrue(tooSmall is java.io.IOException)
            assertEquals(3, server.requestCount)

            server.enqueue(playlistResponse(playlist))
            HlsAudioSegmentCache(directory, maxOutputBytes = 100, maxSegmentBytes = 100)
                .download(playlistUrl, destination, "web-video-retry")

            assertArrayEquals("AUDIO-ONEAUDIO-TWO".toByteArray(), destination.readBytes())
            assertEquals(
                "second run should fetch only the manifest, not the already-cached segments",
                4,
                server.requestCount,
            )
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun playlistResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
        .setBody(text)

    private fun segmentResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "video/mp2t")
        .setBody(text)
}
