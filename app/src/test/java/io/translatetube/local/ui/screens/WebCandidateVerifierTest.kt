package io.translatetube.local.ui.screens

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
    fun directVideoDoesNotGetAnUnverifiedBitrateLabel() {
        val hint = WebCandidateVerifier.initialAudioHint("https://cdn.example/movie.mp4")

        assertEquals(WebCandidateAudioHintKind.UNKNOWN, hint.kind)
        assertTrue(hint.message.contains("待确认"))
    }
}
