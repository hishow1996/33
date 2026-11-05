package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(message.startsWith("请求频率受限："))
        assertFalse(message.contains("错误分类"))
        assertFalse(message.contains("\n"))
        assertTrue(message.endsWith("。"))
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
        assertFalse(message.startsWith("网页媒体源不可直接访问："))
        assertFalse(message.contains("\n"))
        assertTrue(message.endsWith("。"))
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
        assertFalse(message.startsWith("视频文件不存在："))
        assertFalse(message.contains("\n"))
        assertTrue(message.endsWith("。"))
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
    fun transientHttpStatusesAreExplainedForEachRemoteProcessingStage() {
        val translation429 = ProcessingErrorDisplayPolicy.message(
            "local:video", Phase.TRANSLATING, "字幕翻译第 4 条", "HTTP 429 Too Many Requests",
        )
        val audio503 = ProcessingErrorDisplayPolicy.message(
            "remote:https://example.test", Phase.DOWNLOADING_AUDIO, "正在下载音轨", "HTTP/2 503",
        )
        val asr408 = ProcessingErrorDisplayPolicy.message(
            "local:video", Phase.TRANSCRIBING, "语音转写", "response statusCode=408",
        )
        val tts502 = ProcessingErrorDisplayPolicy.message(
            "youtube:video", Phase.SYNTHESIZING, "第 9 条配音合成", "Response{protocol=h2, code=502}",
        )

        assertTrue(translation429.contains("请求过于频繁"))
        assertTrue(audio503.contains("HTTP 503"))
        assertTrue(asr408.contains("超时"))
        assertTrue(tts502.contains("HTTP 502"))
    }

    @Test
    fun quotaAndModelFailuresHaveDedicatedUserMessages() {
        val quota = ProcessingErrorDisplayPolicy.message(
            "local:video", Phase.TRANSLATING, "字幕翻译", "insufficient quota",
        )
        val model = ProcessingErrorDisplayPolicy.message(
            "local:video", Phase.TRANSCRIBING, "语音转写", "model_unavailable",
        )
        assertTrue(quota.contains("额度或配额不足"))
        assertTrue(model.contains("模型未就绪"))
    }

    @Test
    fun missingFailedPhaseCanBeInferredFromProgressAndError() {
        assertEquals(
            Phase.PARSING,
            ProcessingErrorDisplayPolicy.resolvePhase(null, "正在解析 YouTube 视频", "HTTP 503"),
        )
        assertEquals(
            Phase.TRANSLATING,
            ProcessingErrorDisplayPolicy.resolvePhase(Phase.ERROR, "第 35 条字幕翻译请求", "HTTP 429"),
        )
        assertEquals(
            Phase.SYNTHESIZING,
            ProcessingErrorDisplayPolicy.resolvePhase(null, "", "TTS audio output failed"),
        )
        assertNull(ProcessingErrorDisplayPolicy.resolvePhase(null, "", "unexpected internal failure"))
    }

    @Test
    fun retryNeverReusesOldSubtitlesWhenFailureStageIsParseOrUnknown() {
        assertFalse(
            ProcessingErrorDisplayPolicy.canResumeExistingWork(
                Phase.ERROR, "正在解析视频信息", "extractor failed", true, "en",
            ),
        )
        assertFalse(
            ProcessingErrorDisplayPolicy.canResumeExistingWork(
                Phase.ERROR, "", "unknown internal failure", true, "en",
            ),
        )
        assertTrue(
            ProcessingErrorDisplayPolicy.canResumeExistingWork(
                Phase.ERROR, "第 35 条字幕翻译请求", "HTTP 429", true, "en",
            ),
        )
    }


    @Test
    fun allTransientHttpStatusesAreClassifiedAcrossRemoteProcessingStages() {
        val transientStatuses = listOf(408, 425, 429, 500, 502, 503, 504)
        val remoteStages = listOf(
            Phase.DOWNLOADING_AUDIO,
            Phase.TRANSCRIBING,
            Phase.TRANSLATING,
            Phase.SYNTHESIZING,
        )

        for (phase in remoteStages) {
            for (status in transientStatuses) {
                val message = ProcessingErrorDisplayPolicy.message(
                    sessionKey = "remote:https://example.test",
                    phase = phase,
                    progress = when (phase) {
                        Phase.DOWNLOADING_AUDIO -> "正在提取音轨"
                        Phase.TRANSCRIBING -> "正在进行语音转写"
                        Phase.TRANSLATING -> "正在翻译字幕"
                        else -> "正在合成配音"
                    },
                    error = "HTTP $status temporary provider failure",
                )
                when (status) {
                    408 -> assertTrue(message.contains("超时"))
                    425 -> assertTrue(message.contains("暂时无法处理"))
                    429 -> assertTrue(message.contains("请求过于频繁"))
                    else -> assertTrue(message.contains("HTTP $status"))
                }
                assertSingleSentence(message)
            }
        }
    }


    @Test
    fun categoriesAreIntegratedIntoOneSentenceWithoutCategoryHeading() {
        val cases = listOf(
            Triple(Phase.TRANSLATING, "字幕翻译", "SocketTimeoutException: timed out") to "网络连接异常",
            Triple(Phase.TRANSLATING, "字幕翻译", "HTTP 429 Too Many Requests") to "请求频率受限",
            Triple(Phase.TRANSCRIBING, "语音转写", "HTTP 503 Service Unavailable") to "服务端异常",
            Triple(Phase.TRANSLATING, "字幕翻译", "HTTP 401 unauthorized") to "授权失败",
            Triple(Phase.TRANSLATING, "字幕翻译", "insufficient quota") to "额度或配额不足",
            Triple(Phase.TRANSCRIBING, "语音转写", "model_unavailable") to "模型不可用",
            Triple(Phase.TRANSLATING, "字幕翻译", "invalid response: empty response") to "译文无效",
            Triple(Phase.SYNTHESIZING, "配音合成", "invalid audio data") to "音频数据无效",
        )

        for ((input, expectedCategory) in cases) {
            val (phase, progress, error) = input
            val actual = ProcessingErrorDisplayPolicy.errorCategory(
                sessionKey = "local:video",
                phase = phase,
                progress = progress,
                error = error,
            )
            assertEquals(expectedCategory, actual)
            val display = ProcessingErrorDisplayPolicy.message(
                "local:video", phase, progress, error,
            )
            assertFalse(display.startsWith("$expectedCategory："))
            val expectedStage = when (phase) {
                Phase.DOWNLOADING_AUDIO -> "音频提取失败："
                Phase.TRANSCRIBING -> "语音转写失败："
                Phase.TRANSLATING -> "字幕翻译失败："
                Phase.SYNTHESIZING -> "配音合成失败："
                else -> "视频解析失败："
            }
            assertTrue(display.startsWith(expectedStage))
            assertFalse(display.contains("错误分类"))
            assertFalse(display.contains("\n"))
            assertTrue(display.endsWith("。"))
        }
    }

    @Test
    fun displayedCategoryDoesNotExposeTechnicalExceptionOrHost() {
        val display = ProcessingErrorDisplayPolicy.message(
            sessionKey = "local:video",
            phase = Phase.TRANSLATING,
            progress = "第 35 条字幕翻译请求",
            error = "SocketTimeoutException: connection timed out to api.private.example",
        )
        assertTrue(display.startsWith("字幕翻译失败："))
        assertFalse(display.startsWith("网络连接异常："))
        assertFalse(display.contains("错误分类"))
        assertFalse(display.contains("\n"))
        assertTrue(display.endsWith("。"))
        assertFalse(display.contains("SocketTimeoutException"))
        assertFalse(display.contains("api.private.example"))
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
        assertTrue(message.startsWith("YouTube 视频解析失败：未能获取视频信息"))
        assertFalse(message.contains("错误分类"))
        assertFalse(message.contains("\n"))
        assertTrue(message.endsWith("。"))
        assertEquals("视频加载失败", ProcessingErrorDisplayPolicy.title(Phase.ERROR))
    }

    private fun assertSingleSentence(message: String) {
        assertFalse(message.contains("错误分类"))
        assertFalse(message.contains("\n"))
        assertEquals(1, message.count { it == '。' })
        assertTrue(message.endsWith("。"))
    }

    @Test
    fun stageTitlesAndRetryLabelsAreSpecific() {
        assertEquals("视频解析失败", ProcessingErrorDisplayPolicy.title(Phase.PARSING))
        assertEquals("音频提取失败", ProcessingErrorDisplayPolicy.title(Phase.DOWNLOADING_AUDIO))
        assertEquals("语音转写失败", ProcessingErrorDisplayPolicy.title(Phase.TRANSCRIBING))
        assertEquals("字幕翻译失败", ProcessingErrorDisplayPolicy.title(Phase.TRANSLATING))
        assertEquals("配音合成失败", ProcessingErrorDisplayPolicy.title(Phase.SYNTHESIZING))
        assertEquals("重试当前处理", ProcessingErrorDisplayPolicy.retryLabel(Phase.TRANSLATING))
        assertEquals(
            "重新解析",
            ProcessingErrorDisplayPolicy.retryLabel(Phase.ERROR, "正在解析视频", "parse failed"),
        )
    }
}
