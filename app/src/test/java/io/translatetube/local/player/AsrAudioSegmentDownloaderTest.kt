package io.translatetube.local.player

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        etag: String = "\"audio-v1\"",
    ): MockResponse = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Type", "audio/mp4")
        .setHeader("Content-Range", "bytes $start-$end/$total")
        .setHeader("ETag", etag)
        .setBody(body)
}
