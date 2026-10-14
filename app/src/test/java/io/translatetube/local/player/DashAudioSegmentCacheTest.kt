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
            server.enqueue(manifestResponse(manifestXml()))
            server.enqueue(segmentResponse("INIT"))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))

            val destination = File(directory, "source-audio.bin")
            val manifestUrl = server.url("/video/manifest.mpd").toString()
            val result = DashAudioSegmentCache(
                cacheDir = directory,
                maxOutputBytes = 100L,
                maxSegmentBytes = 100L,
            ).download(
                manifestUrl = manifestUrl,
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
    fun resolvesRelativeSegmentsAgainstFinalMpDUrlAfterRedirect() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "dash-redirect-base-test")
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/actual/manifest.mpd"),
            )
            server.enqueue(manifestResponse(manifestXml()))
            server.enqueue(segmentResponse("INIT"))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))

            val destination = File(directory, "redirected-audio.bin")
            val result = DashAudioSegmentCache(directory, maxOutputBytes = 100L, maxSegmentBytes = 100L)
                .download(
                    server.url("/redirect/manifest.mpd").toString(),
                    destination,
                    "redirected-dash-source",
                )

            assertEquals("INITSEGMENT-ONESEGMENT-TWO", result.readText())
            assertEquals(5, server.requestCount)
            assertEquals("/redirect/manifest.mpd", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/manifest.mpd", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/init.mp4", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/seg-1.m4s", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/actual/seg-2.m4s", server.takeRequest().requestUrl?.encodedPath)
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
            server.enqueue(manifestResponse(manifestXml()))
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

            server.enqueue(manifestResponse(manifestXml()))
            DashAudioSegmentCache(directory, maxOutputBytes = 100L, maxSegmentBytes = 100L)
                .download(manifestUrl, destination, "webmedia:test-dash-retry")

            assertArrayEquals("INITSEGMENT-ONESEGMENT-TWO".toByteArray(), destination.readBytes())
            assertEquals(
                "Retry should fetch the MPD but reuse completed media segments",
                5,
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
        val directory = createTempDir(prefix = "dash-signed-query-reuse-test")
        server.start()
        try {
            server.enqueue(manifestResponse(manifestXml(token = "old")))
            server.enqueue(segmentResponse("INITDATA"))
            server.enqueue(segmentResponse("SEGMENT-ONE"))
            server.enqueue(segmentResponse("SEGMENT-TWO"))

            val destination = File(directory, "source-audio.bin")
            val manifestUrl = server.url("/video/manifest.mpd").toString()
            val incompleteAssembly = runCatching {
                DashAudioSegmentCache(directory, maxOutputBytes = 8L, maxSegmentBytes = 100L)
                    .download(manifestUrl, destination, "stable-dash-source")
            }.exceptionOrNull()
            assertTrue(incompleteAssembly is java.io.IOException)
            assertEquals(4, server.requestCount)

            server.enqueue(manifestResponse(manifestXml(token = "new")))
            val result = DashAudioSegmentCache(directory, maxOutputBytes = 100L, maxSegmentBytes = 100L)
                .download(manifestUrl, destination, "stable-dash-source")

            assertEquals("INITDATASEGMENT-ONESEGMENT-TWO", result.readText())
            assertEquals(
                "Refreshing auth parameters should fetch a new MPD, not re-download segments",
                5,
                server.requestCount,
            )
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesOrphanedFilesWhenSegmentPathsChangeBetweenRetries() {
        val server = MockWebServer()
        val directory = createTempDir(prefix = "dash-rotated-url-test")
        server.start()
        try {
            server.enqueue(manifestResponse(manifestXml(initPath = "old-init.mp4", segmentPrefix = "old")))
            server.enqueue(segmentResponse("OLD-INIT"))
            server.enqueue(segmentResponse("OLD-ONE"))
            server.enqueue(segmentResponse("OLD-TWO"))

            val destination = File(directory, "source-audio.bin")
            val manifestUrl = server.url("/video/manifest.mpd").toString()
            val firstFailure = runCatching {
                DashAudioSegmentCache(directory, maxOutputBytes = 8L, maxSegmentBytes = 100L)
                    .download(manifestUrl, destination, "rotating-dash-source")
            }.exceptionOrNull()
            assertTrue(firstFailure is java.io.IOException)

            val root = File(directory, "asr_dash_segments")
            val sourceFolder = root.listFiles().orEmpty().single()
            val orphan = File(sourceFolder, "expired-signed-url.seg")
            orphan.writeText("stale")

            server.enqueue(manifestResponse(manifestXml(
                initPath = "new-init.mp4?token=refreshed",
                segmentPrefix = "new",
            )))
            server.enqueue(segmentResponse("NEW-INIT"))
            server.enqueue(segmentResponse("NEW-ONE"))
            server.enqueue(segmentResponse("NEW-TWO"))

            val secondFailure = runCatching {
                DashAudioSegmentCache(directory, maxOutputBytes = 8L, maxSegmentBytes = 100L)
                    .download(manifestUrl, destination, "rotating-dash-source")
            }.exceptionOrNull()
            assertTrue(secondFailure is java.io.IOException)
            assertTrue("orphaned cache entry should be removed", !orphan.exists())
            assertEquals(
                "Only current init + media segments should remain after failed assembly",
                3,
                sourceFolder.listFiles().orEmpty().count { it.isFile },
            )
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun manifestXml(
        token: String? = null,
        initPath: String = "init.mp4",
        segmentPrefix: String = "seg",
    ): String {
        val dollar = 36.toChar().toString()
        val query = token?.let { "?token=$it" }.orEmpty()
        val media = "$segmentPrefix-" + dollar + "Number" + dollar + ".m4s" + query
        val initialization = initPath + if (token == null || initPath.contains('?')) "" else query
        return """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT8S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <SegmentTemplate timescale="1" duration="4"
                    initialization="$initialization" media="$media" />
                  <Representation id="audio" bandwidth="128000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
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
