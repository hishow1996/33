package io.translatetube.local.player

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashAudioSegmentCacheTest {
    @Test
    fun downloadsAndAssemblesInitializationAndAudioSegmentsInOrder() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "dash-audio-cache-test")
        server.start()
        try {
            server.enqueue(manifestResponse("""
                <MPD type="static" mediaPresentationDuration="PT8S">
                  <Period duration="PT8S">
                    <AdaptationSet contentType="audio" mimeType="audio/mp4">
                      <SegmentTemplate timescale="1" duration="4"
                        initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" />
                      <Representation id="audio" bandwidth="128000" />
                    </AdaptationSet>
                  </Period>
                </MPD>
            """.trimIndent()))
            server.enqueue(segmentResponse("INIT"))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))

            val destination = File(directory, "source-audio.bin")
            val result = DashAudioSegmentCache(
                cacheDir = directory,
                maxOutputBytes = 100L,
                maxSegmentBytes = 100L,
            ).download(
                manifestUrl = server.url("/video/manifest.mpd").toString(),
                destination = destination,
                sourceKey = "webmedia:test-dash-1",
            )

            assertEquals(destination.absolutePath, result.absolutePath)
            assertArrayEquals("INITSEGMENT-ONESEGMENT-TWO".toByteArray(), destination.readBytes())
            assertEquals(4, server.requestCount)
            assertEquals("/video/manifest.mpd", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/video/init.mp4", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/video/seg-1.m4s", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/video/seg-2.m4s", server.takeRequest().requestUrl?.encodedPath)
            assertTrue(File(directory, "asr_dash_segments").listFiles().orEmpty().isEmpty())
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun reusesDownloadedSegmentsAfterAssemblyLimitFailure() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "dash-audio-retry-test")
        server.start()
        try {
            val manifest = """
                <MPD type="static" mediaPresentationDuration="PT8S">
                  <Period duration="PT8S">
                    <AdaptationSet contentType="audio" mimeType="audio/mp4">
                      <SegmentTemplate timescale="1" duration="4"
                        initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" />
                      <Representation id="audio" bandwidth="128000" />
                    </AdaptationSet>
                  </Period>
                </MPD>
            """.trimIndent()
            server.enqueue(manifestResponse(manifest))
            server.enqueue(segmentResponse("INIT"))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))

            val destination = File(directory, "source-audio.bin")
            val manifestUrl = server.url("/video/manifest.mpd").toString()
            val failure = runCatching {
                DashAudioSegmentCache(directory, maxOutputBytes = 8L, maxSegmentBytes = 100L)
                    .download(manifestUrl, destination, "webmedia:test-dash-retry")
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(4, server.requestCount)

            server.enqueue(manifestResponse(manifest))
            DashAudioSegmentCache(directory, maxOutputBytes = 100L, maxSegmentBytes = 100L)
                .download(manifestUrl, destination, "webmedia:test-dash-retry")

            assertArrayEquals("INITSEGMENT-ONESEGMENT-TWO".toByteArray(), destination.readBytes())
            assertEquals(
                "Retry should read the manifest but reuse all completed media segments",
                5,
                server.requestCount,
            )
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun manifestResponse(xml: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/dash+xml")
        .setBody(xml)

    private fun segmentResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "video/mp4")
        .setBody(text)
}
