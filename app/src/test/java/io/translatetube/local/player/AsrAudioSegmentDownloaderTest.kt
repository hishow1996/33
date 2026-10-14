package io.translatetube.local.player

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AsrAudioSegmentDownloaderTest {
    @Test
    fun downloadsSequentialRangesAndRetriesAnInvalidSegmentResponse() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "asr-range-test")
        server.start()
        try {
            server.enqueue(rangeResponse(0, 3, 10, "abcd"))
            // Content-Range promises four bytes but the body only contains two. The downloader
            // must reject this response and retry the same byte range, never append it as valid.
            server.enqueue(rangeResponse(4, 7, 10, "ef"))
            server.enqueue(rangeResponse(4, 7, 10, "efgh"))
            server.enqueue(rangeResponse(8, 9, 10, "ij"))

            val destination = File(directory, "audio.bin")
            val result = AsrAudioSegmentDownloader(
                client = okhttp3.OkHttpClient(),
                maxBytes = 100L,
                chunkBytes = 4L,
            ).download(
                url = server.url("/audio").toString(),
                destination = destination,
                sourceKey = "video-1",
            )

            assertEquals(destination.absolutePath, result.absolutePath)
            assertArrayEquals("abcdefghij".toByteArray(), result.readBytes())
            assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=8-9", server.takeRequest().getHeader("Range"))
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rollsBackAnInterruptedRangeAndRetriesFromTheLastVerifiedOffset() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "asr-range-disconnect-test")
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mp4")
                    .setHeader("Content-Range", "bytes 0-3/8")
                    .setHeader("ETag", "\"audio-v1\"")
                    .setChunkedBody("ab", 1)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
            server.enqueue(rangeResponse(0, 3, 8, "abcd"))
            server.enqueue(rangeResponse(4, 7, 8, "efgh"))

            val destination = File(directory, "audio.bin")
            AsrAudioSegmentDownloader(
                // Disable OkHttp's transparent retry so this test exercises the downloader's
                // own rollback of an incomplete range and explicit same-offset retry.
                client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                maxBytes = 100L,
                chunkBytes = 4L,
            ).download(
                url = server.url("/audio").toString(),
                destination = destination,
                sourceKey = "video-interrupted-range",
            )

            assertArrayEquals("abcdefgh".toByteArray(), destination.readBytes())
            assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun restartsFromZeroWhenRedirectTargetChangesWithoutAValidator() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "asr-redirect-target-test")
        server.start()
        try {
            fun redirect(to: String) = MockResponse()
                .setResponseCode(302)
                .setHeader("Location", to)

            server.enqueue(redirect("/asset-v1/audio"))
            server.enqueue(rangeResponse(0, 3, 8, "ABCD", etag = null))
            server.enqueue(redirect("/asset-v2/audio"))
            server.enqueue(rangeResponse(4, 7, 8, "EFGH", etag = null))
            // The downloader must discard the old prefix and start the new representation at zero.
            server.enqueue(redirect("/asset-v2/audio"))
            server.enqueue(rangeResponse(0, 3, 8, "WXYZ", etag = null))
            server.enqueue(redirect("/asset-v2/audio"))
            server.enqueue(rangeResponse(4, 7, 8, "QRST", etag = null))

            val destination = File(directory, "audio.bin")
            AsrAudioSegmentDownloader(
                client = okhttp3.OkHttpClient(),
                maxBytes = 100L,
                chunkBytes = 4L,
            ).download(
                url = server.url("/redirect/audio").toString(),
                destination = destination,
                sourceKey = "redirected-audio-resource",
            )

            assertArrayEquals("WXYZQRST".toByteArray(), destination.readBytes())
            assertEquals(8, server.requestCount)
            val expected = listOf(
                "/redirect/audio" to "bytes=0-3",
                "/asset-v1/audio" to "bytes=0-3",
                "/redirect/audio" to "bytes=4-7",
                "/asset-v2/audio" to "bytes=4-7",
                "/redirect/audio" to "bytes=0-3",
                "/asset-v2/audio" to "bytes=0-3",
                "/redirect/audio" to "bytes=4-7",
                "/asset-v2/audio" to "bytes=4-7",
            )
            expected.forEach { (path, range) ->
                val request = server.takeRequest()
                assertEquals(path, request.requestUrl?.encodedPath)
                assertEquals(range, request.getHeader("Range"))
            }
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resumesSavedPartialOnlyWithMatchingSourceAndValidator() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "asr-resume-test")
        server.start()
        try {
            val url = server.url("/audio").toString()
            val destination = File(directory, "audio.bin")
            val partial = File(directory, "audio.bin.part")
            val metadataFile = File(directory, "audio.bin.part.meta")
            partial.writeBytes("abcd".toByteArray())

            val urlHash = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val metadata = Properties().apply {
                setProperty("sourceKey", "video-2")
                setProperty("urlHash", urlHash)
                setProperty("etag", "\"audio-v1\"")
                setProperty("totalBytes", "10")
                setProperty("rangeSupported", "true")
            }
            metadataFile.outputStream().use { metadata.store(it, "test sidecar") }

            server.enqueue(rangeResponse(4, 7, 10, "efgh", "\"audio-v1\""))
            server.enqueue(rangeResponse(8, 9, 10, "ij", "\"audio-v1\""))

            AsrAudioSegmentDownloader(
                client = okhttp3.OkHttpClient(),
                maxBytes = 100L,
                chunkBytes = 4L,
            ).download(url, destination, "video-2")

            assertArrayEquals("abcdefghij".toByteArray(), destination.readBytes())
            val firstRequest = server.takeRequest()
            assertEquals("bytes=4-7", firstRequest.getHeader("Range"))
            assertEquals("\"audio-v1\"", firstRequest.getHeader("If-Range"))
            assertFalse(metadataFile.exists())
            assertEquals("bytes=8-9", server.takeRequest().getHeader("Range"))
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun rangeResponse(
        start: Int,
        end: Int,
        total: Int,
        body: String,
        etag: String? = "\"audio-v1\"",
    ): MockResponse = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Type", "audio/mp4")
        .setHeader("Content-Range", "bytes $start-$end/$total")
        .apply { if (etag != null) setHeader("ETag", etag) }
        .setBody(body)
}
