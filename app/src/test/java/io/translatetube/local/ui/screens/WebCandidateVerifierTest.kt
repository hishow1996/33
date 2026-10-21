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
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/movie.mp4")

        assertEquals(WebCandidateAudioHintKind.UNKNOWN, hint.kind)
        assertTrue(hint.message.contains("待确认"))
    }
}
