package io.translatetube.local.ui.screens

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
            server.enqueue(MockResponse().setResponseCode(410).setBody("expired"))

            val hint = WebCandidateVerifier.inspectAudioHint(
                client = OkHttpClient(),
                url = server.url("/master.m3u8").toString(),
                referer = "https://example.com/watch",
                cookie = null,
                userAgent = null,
            )

            assertEquals(WebCandidateAudioHintKind.FAILED, hint.kind)
            assertTrue(hint.message.contains("HTTP 410"))
            assertTrue(hint.failure is java.io.IOException)
            assertTrue(hint.failure?.message.orEmpty().contains("HTTP 410"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun invalidManifestPayloadRetainsCauseForPersistentAppLogging() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login required</html>"))

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
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun directVideoDoesNotGetAnUnverifiedBitrateLabel() {
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/movie.mp4")

        assertEquals(WebCandidateAudioHintKind.UNKNOWN, hint.kind)
        assertTrue(hint.message.contains("待确认"))
    }
}
