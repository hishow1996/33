package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingErrorDisplayPolicyTest {
    @Test
    fun parseFailureHasSourceSpecificMessageAndPreservesRetryActionLabel() {
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "youtube:video-id",
            phase = Phase.PARSING,
            progress = "正在获取 YouTube 视频信息",
            error = "ReCaptchaException: HTTP 429 too many requests at www.youtube.com",
        )

        assertTrue(message.startsWith("YouTube 视频解析失败："))
        assertTrue(message.contains("请求过于频繁"))
        assertFalse(message.contains("ReCaptchaException"))
        assertFalse(message.contains("www.youtube.com"))
        assertEquals("重新解析", ProcessingErrorDisplayPolicy.retryLabel(Phase.PARSING))
    }

    @Test
    fun webParsingExplainsEmbeddedMediaSource() {
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "remote:https://example.test/watch",
            phase = Phase.PARSING,
            progress = "网页视频解析",
            error = "blob URL MSE media source is not directly playable",
        )
        assertTrue(message.startsWith("网页视频解析失败："))
        assertTrue(message.contains("内嵌媒体流"))
    }

    @Test
    fun localParsingExplainsMissingFileWithoutExposingPath() {
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "local:content://provider/item",
            phase = Phase.PARSING,
            progress = "读取本地视频",
            error = "java.io.FileNotFoundException: /private/path/video.mp4 (No such file)",
        )
        assertTrue(message.startsWith("本地视频解析失败："))
        assertTrue(message.contains("不存在或已移动"))
        assertFalse(message.contains("/private/path"))
    }

    @Test
    fun translationMessageKeepsSubtitleIndexButHidesTechnicalCause() {
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "local:video",
            phase = Phase.TRANSLATING,
            progress = "第 35 条字幕翻译请求",
            error = "SocketTimeoutException: connection timed out to api.example.test",
        )
        assertTrue(message.contains("第 35 条字幕"))
        assertTrue(message.contains("请求中断或超时"))
        assertFalse(message.contains("SocketTimeoutException"))
        assertFalse(message.contains("api.example.test"))
    }

    @Test
    fun synthesisMessageIdentifiesFailedSubtitle() {
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "youtube:video-id",
            phase = Phase.SYNTHESIZING,
            progress = "第 12 条字幕配音合成",
            error = "audio output failed",
        )
        assertTrue(message.contains("配音合成失败"))
        assertTrue(message.contains("第 12 条字幕"))
    }

    @Test
    fun unknownFailureDoesNotExposeRawExceptionText() {
        val raw = "InternalParserException: private-host-token-123"
        val message = ProcessingErrorDisplayPolicy.message(
            sessionKey = "youtube:video-id",
            phase = Phase.PARSING,
            progress = "",
            error = raw,
        )
        assertFalse(message.contains("InternalParserException"))
        assertFalse(message.contains("private-host-token-123"))
        assertEquals("视频加载失败", ProcessingErrorDisplayPolicy.title(Phase.ERROR))
    }

    @Test
    fun stageTitlesAndRetryLabelsAreSpecific() {
        assertEquals("视频解析失败", ProcessingErrorDisplayPolicy.title(Phase.PARSING))
        assertEquals("音频提取失败", ProcessingErrorDisplayPolicy.title(Phase.DOWNLOADING_AUDIO))
        assertEquals("语音转写失败", ProcessingErrorDisplayPolicy.title(Phase.TRANSCRIBING))
        assertEquals("字幕翻译失败", ProcessingErrorDisplayPolicy.title(Phase.TRANSLATING))
        assertEquals("配音合成失败", ProcessingErrorDisplayPolicy.title(Phase.SYNTHESIZING))
        assertEquals("重试当前处理", ProcessingErrorDisplayPolicy.retryLabel(Phase.TRANSLATING))
    }
}
