package io.translatetube.local.ui.screens

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCandidateVerifierTest {

    @Test
    fun hlsHintStartsInAnExplicitDetectingState() {
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/master.m3u8")

        assertEquals(WebCandidateAudioHintKind.DETECTING, hint.kind)
        assertTrue(hint.message.contains("正在检测"))
    }

    @Test
    fun timeoutProducesAnActionableTerminalFailureInsteadOfAnInfiniteSpinner() {
        val hint = WebCandidateVerifier.failedAudioHint(
            "https://cdn.example/master.m3u8",
            java.net.SocketTimeoutException("read timed out"),
        )

        assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
        assertTrue(hint.message.contains("HLS 检测失败"))
        assertTrue(hint.message.contains("请求超时"))
    }

    @Test
    fun audioDetectionFailureRetainsTheOriginalExceptionForDiagnostics() {
        val original = java.net.SocketTimeoutException("read timed out")

        val hint = WebCandidateVerifier.failedAudioHint(
            "https://cdn.example/master.m3u8",
            original,
        )

        assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
        assertTrue(hint.failure === original)
        assertEquals("read timed out", hint.failure?.message)
    }

    @Test
    fun httpFailureRetainsStatusAndCauseForPersistentAppLogging() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(410)
                    .setHeader("Content-Type", "text/plain")
                    .setBody("expired; token=do-not-save")
            )

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/master.m3u8?token=do-not-log&quality=low").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
            assertTrue(hint.message.contains("HTTP 410"))
            assertTrue(hint.failure is java.io.IOException)
            assertTrue(hint.failure?.message.orEmpty().contains("HTTP 410"))
            assertEquals("410", hint.diagnosticContext["HTTP 状态码"])
            assertEquals("text/plain", hint.diagnosticContext["响应 Content-Type"])
            assertTrue(hint.diagnosticContext["HTTP 错误响应体摘要"].orEmpty().contains("expired"))
            assertTrue(!hint.diagnosticContext.values.any { it.contains("do-not-save") })
            assertTrue(hint.diagnosticContext["请求地址查询参数名（不含值）"].orEmpty().contains("token"))
            assertTrue(!hint.diagnosticContext.values.any { it.contains("do-not-log") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun invalidManifestPayloadRetainsCauseForPersistentAppLogging() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html>login required</html>")
            )

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/master.m3u8").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
            assertTrue(hint.message.contains("不是有效的 M3U8"))
            assertTrue(hint.failure is IllegalStateException)
            assertTrue(hint.failure?.message.orEmpty().contains("#EXTM3U"))
            assertEquals("200", hint.diagnosticContext["HTTP 状态码"])
            assertEquals("text/html", hint.diagnosticContext["响应 Content-Type"])
            assertEquals("HTML 网页（常见于登录页/防护拦截页）", hint.diagnosticContext["响应内容特征"])
            assertEquals("false", hint.diagnosticContext["是否包含 #EXTM3U"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directVideoDoesNotGetAnUnverifiedBitrateLabel() {
        // WebM is not parsed, so it keeps the "to be confirmed" hint and no invented bitrate.
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/movie.webm")

        assertEquals(WebCandidateAudioHintKind.UNKNOWN, hint.kind)
        assertTrue(hint.message.contains("待确认"))
    }

    @Test
    fun directMp4StartsInAnExplicitDetectingStateWithoutAnyTrackClaim() {
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/movie.mp4?sig=abc")

        assertEquals(WebCandidateAudioHintKind.DETECTING, hint.kind)
        assertTrue(hint.message.contains("正在读取文件头"))
        assertEquals(null, hint.mediaInfo)
    }

    @Test
    fun directMp4WithMoovAtTheEndReportsAudioTrackAndVideoBitrateOverHttp() {
        val server = rangeServer(Mp4TestFiles.typicalMovie(moovFirst = false, mdatBytes = 3_000_000))
        server.start()
        try {
            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/clip.mp4?token=secret-value").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.MUXED_DIRECT, hint.kind)
            assertTrue(hint.message.contains("音轨 AAC-LC 立体声"))
            assertTrue(hint.message.contains("视频 1080p H.264 800 kbps"))
            assertEquals(800_000L, hint.mediaInfo?.videoBitrateBps)
            assertEquals(true, hint.mediaInfo?.hasAudio)
            assertTrue(!hint.diagnosticContext.values.any { it.contains("secret-value") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directMp4OnAServerWithoutRangeSupportExplainsWhyItCannotBeChecked() {
        val server = rangeServer(
            Mp4TestFiles.typicalMovie(moovFirst = false, mdatBytes = 3_000_000),
            honorRange = false,
        )
        server.start()
        try {
            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/clip.mp4").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.UNKNOWN, hint.kind)
            assertTrue(hint.message.contains("Range"))
            assertTrue(hint.message.contains("待确认"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directMp4HttpErrorBecomesATerminalFailure() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/clip.mp4").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
            assertTrue(hint.message.contains("HTTP 403"))
            assertTrue(hint.failure is java.io.IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun hlsMasterWithSeparateAudioReportsAudioTypeAndVideoBitrateRange() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="中文",LANGUAGE="zh",DEFAULT=YES,CHANNELS="2",URI="audio/zh.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=600000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
                    v360/index.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=4200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
                    v1080/index.m3u8
                    """.trimIndent()
                )
            )

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/master.m3u8").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.INDEPENDENT, hint.kind)
            assertTrue(hint.message.contains("检测到独立音轨 AAC-LC 立体声"))
            assertTrue(hint.message.contains("不需要下载视频画面"))
            assertTrue(hint.message.contains("视频 360p–1080p H.264 声明 600 kbps–4.2 Mbps"))
            assertEquals(4_200_000L, hint.mediaInfo?.videoBitrateMaxBps)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun hlsMasterWithMuxedAudioKeepsTheSelectedRenditionAndAddsDetails() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=4500000,AVERAGE-BANDWIDTH=3900000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
                    video/1080.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=650000,AVERAGE-BANDWIDTH=430000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
                    video/360.m3u8
                    """.trimIndent()
                )
            )

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/master.m3u8").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.LOW_BITRATE_MUXED, hint.kind)
            assertTrue(hint.message.contains("混合音视频 · 已选择低码率版本"))
            assertTrue(hint.message.contains("360p H.264 声明 430 kbps"))
            assertTrue(hint.message.contains("音轨 AAC-LC"))
            assertTrue(hint.renditionUrl.orEmpty().endsWith("/video/360.m3u8"))
        } finally {
            server.shutdown()
        }
    }

    /** Serves [data] with real HTTP Range semantics (206 + Content-Range), or ignores Range. */
    private fun rangeServer(data: ByteArray, honorRange: Boolean = true): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val match = request.getHeader("Range")?.let { RANGE.matchEntire(it) }
                if (!honorRange || match == null) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "video/mp4")
                        .setBody(Buffer().write(data))
                }
                val start = match.groupValues[1].toLong()
                val end = minOf(match.groupValues[2].toLong(), data.size - 1L)
                if (start >= data.size) {
                    return MockResponse().setResponseCode(416)
                        .setHeader("Content-Range", "bytes */${data.size}")
                }
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
            }
        }
        return server
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d+)""")
    }
}
