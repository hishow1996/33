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
            assertEquals("/media/list.m3u8", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/media/seg-1.ts", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/media/seg-2.ts", server.takeRequest().requestUrl?.encodedPath)
            assertTrue(File(directory, "asr_hls_segments").listFiles().orEmpty().isEmpty())
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resolvesRelativeSegmentsAgainstFinalPlaylistUrlAfterRedirect() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "hls-redirect-base-test")
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/actual/list.m3u8"),
            )
            server.enqueue(
                playlistResponse(
                    """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:4
                        #EXTINF:4.0,
                        seg-1.ts
                        #EXTINF:4.0,
                        seg-2.ts
                        #EXT-X-ENDLIST
                    """.trimIndent()
                )
            )
            server.enqueue(segmentResponse("AUDIO-ONE"))
            server.enqueue(segmentResponse("AUDIO-TWO"))

            val destination = File(directory, "redirected-audio.bin")
            val result = HlsAudioSegmentCache(directory, maxOutputBytes = 100L, maxSegmentBytes = 100L)
                .download(
                    server.url("/redirect/list.m3u8").toString(),
                    destination,
                    "redirected-hls-source",
                )

            assertEquals("AUDIO-ONEAUDIO-TWO", result.readText())
            assertEquals(4, server.requestCount)
            assertEquals("/redirect/list.m3u8", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/list.m3u8", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/seg-1.ts", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/seg-2.ts", server.takeRequest().requestUrl?.encodedPath)
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

    @Test
    fun reusesCompletedSegmentsWhenOnlyTheirSigningTokensRotate() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "hls-signed-query-reuse-test")
        server.start()
        try {
            fun playlist(token: String) = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                seg-1.ts?token=$token
                #EXTINF:4.0,
                seg-2.ts?token=$token
                #EXT-X-ENDLIST
            """.trimIndent()

            server.enqueue(playlistResponse(playlist("old")))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))
            val destination = File(directory, "audio.bin")
            val incompleteAssembly = runCatching {
                HlsAudioSegmentCache(directory, maxOutputBytes = 4, maxSegmentBytes = 100)
                    .download(
                        server.url("/media/list.m3u8").toString(),
                        destination,
                        "stable-hls-source",
                    )
            }.exceptionOrNull()
            assertTrue(incompleteAssembly is java.io.IOException)
            assertEquals(3, server.requestCount)

            // The playlist refreshes expiring tokens, but the segment paths and source are
            // unchanged. Complete cached segments are safe to reuse without another network fetch.
            server.enqueue(playlistResponse(playlist("new")))
            val result = HlsAudioSegmentCache(directory, maxOutputBytes = 100, maxSegmentBytes = 100)
                .download(
                    server.url("/media/list.m3u8").toString(),
                    destination,
                    "stable-hls-source",
                )

            assertEquals("SEGMENT-ONESEGMENT-TWO", result.readText())
            assertEquals(
                "refreshing only auth parameters should fetch a new playlist, not re-download segments",
                4,
                server.requestCount,
            )
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesOrphanedFilesWhenSignedSegmentUrlsChangeBetweenRetries() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "hls-rotated-url-test")
        server.start()
        try {
            val oldPlaylist = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                old-1.ts
                #EXTINF:4.0,
                old-2.ts
                #EXT-X-ENDLIST
            """.trimIndent()
            server.enqueue(playlistResponse(oldPlaylist))
            server.enqueue(segmentResponse("OLD-ONE"))
            server.enqueue(segmentResponse("OLD-TWO"))

            val destination = File(directory, "audio.bin")
            val firstFailure = runCatching {
                HlsAudioSegmentCache(directory, maxOutputBytes = 4, maxSegmentBytes = 100)
                    .download(server.url("/media/list.m3u8").toString(), destination, "rotating-signed-source")
            }.exceptionOrNull()
            assertTrue(firstFailure is java.io.IOException)

            val root = File(directory, "asr_hls_segments")
            val sourceFolder = root.listFiles().orEmpty().single()
            val orphan = File(sourceFolder, "expired-signed-url.seg")
            orphan.writeText("stale")

            val refreshedPlaylist = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                new-1.ts?token=refreshed
                #EXTINF:4.0,
                new-2.ts?token=refreshed
                #EXT-X-ENDLIST
            """.trimIndent()
            server.enqueue(playlistResponse(refreshedPlaylist))
            server.enqueue(segmentResponse("NEW-ONE"))
            server.enqueue(segmentResponse("NEW-TWO"))

            val secondFailure = runCatching {
                HlsAudioSegmentCache(directory, maxOutputBytes = 4, maxSegmentBytes = 100)
                    .download(server.url("/media/list.m3u8").toString(), destination, "rotating-signed-source")
            }.exceptionOrNull()
            assertTrue(secondFailure is java.io.IOException)
            assertTrue("orphaned cache entry should be removed", !orphan.exists())
            assertEquals(
                "only the two current playlist segments should remain after failed assembly",
                2,
                sourceFolder.listFiles().orEmpty().count { it.isFile },
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
