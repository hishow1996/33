package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTitleResolverTest {
    @Test
    fun dubbingTitlesNameTheSpecificOperationWithoutRepeatingTheModule() {
        assertEquals(
            "配音缓存：PCM 预检完成",
            AppLogTitleResolver.title("DubbingEngine", "配音 PCM 缓存预检完成"),
        )
        assertEquals(
            "配音混流：写入缓冲失败",
            AppLogTitleResolver.title("DubbingMixer", "配音混流处理器写入缓冲失败"),
        )
        assertEquals(
            "系统 TTS：朗读失败",
            AppLogTitleResolver.title("DubbingEngine", "系统 TTS 朗读失败"),
        )
        assertEquals(
            "配音回退：第 7/20 句无可用配音，已恢复原声",
            AppLogTitleResolver.title(
                "DubbingFallback",
                "播放第 7/20 句时未检测到可播放中文配音，已恢复原声",
            ),
        )
    }

    @Test
    fun sourceDetectionTitlesIdentifyTheRecognizedResource() {
        assertEquals(
            "网页音轨：已识别独立音轨",
            AppLogTitleResolver.title("WebAudioSource", "检测到网页独立音轨"),
        )
    }

    @Test
    fun ytdlpUpdateTitleStatesTheExactFailure() {
        assertEquals(
            "yt-dlp 更新：下载失败",
            AppLogTitleResolver.title("YtDlpUpdater", "yt-dlp 更新下载失败"),
        )
    }

    @Test
    fun selfDescribingMessagesAreNotPrefixedTwice() {
        assertEquals(
            "字幕翻译失败：接口返回空内容",
            AppLogTitleResolver.title("Translation", "字幕翻译失败：接口返回空内容"),
        )
    }

    @Test
    fun alreadyLabelledGenericFailureDoesNotShowTheRootCause() {
        assertEquals(
            "字幕翻译失败",
            AppLogTitleResolver.title(
                "Translation",
                "字幕翻译失败 | HttpException: HTTP 429 Too Many Requests",
            ),
        )
    }

    @Test
    fun translationAndTranscriptionTitlesDoNotRepeatTheOperationName() {
        assertEquals(
            "字幕翻译：请求失败",
            AppLogTitleResolver.title(
                "Translation",
                "翻译请求失败 | HttpException: HTTP 503 Service Unavailable",
            ),
        )
        assertEquals(
            "字幕翻译：结果为空",
            AppLogTitleResolver.title("Translation", "翻译结果为空"),
        )
        assertEquals(
            "语音转写：任务失败",
            AppLogTitleResolver.title("Transcription", "转写任务失败"),
        )
    }

    @Test
    fun ordinaryPipeSeparatedEventDetailsArePreserved() {
        assertEquals(
            "Custom Parser：解析完成 | 发现 2 个音轨",
            AppLogTitleResolver.title("CustomParser", "解析完成 | 发现 2 个音轨"),
        )
    }

    @Test
    fun genericFailureTitleDoesNotIncludeTheRootCause() {
        assertEquals(
            "字幕翻译：接口请求失败",
            AppLogTitleResolver.title(
                "Translation",
                "接口请求失败 | IllegalStateException: 包装异常；根因=HttpException: HTTP 429 Too Many Requests",
            ),
        )
    }

    @Test
    fun titlesHaveALengthLimitAndDoNotShowTheExceptionChain() {
        val longMessage = "接口请求失败，服务器返回了非常长的诊断说明，用于验证列表标题不会持续增长或挤满整个屏幕".repeat(3) +
            " | IllegalStateException: wrapper；根因=IOException: hidden root cause"
        val title = AppLogTitleResolver.title("Translation", longMessage)
        assertTrue(title.codePointCount(0, title.length) <= 72)
        assertFalse(title.contains("hidden root cause"))
        assertFalse(title.contains("IllegalStateException"))
    }

    @Test
    fun unknownTagsRemainReadable() {
        assertEquals(
            "Custom Audio Manager：初始化完成",
            AppLogTitleResolver.title("CustomAudioManager", "初始化完成"),
        )
    }

    @Test
    fun blankMessageGetsAUsefulFallbackTitle() {
        assertEquals(
            "日志记录：未提供日志摘要",
            AppLogTitleResolver.title("AppLogStore", ""),
        )
    }

    @Test
    fun playerDiagnosticsUseTheSamePlaybackCategoryAndCompactTitles() {
        assertEquals("视频播放：播放出错", AppLogTitleResolver.title("PlayerManager", "播放器报告错误"))
        assertEquals("视频播放：首帧已渲染", AppLogTitleResolver.title("PlayerManager", "视频首帧已渲染"))
        assertEquals("视频播放：缓冲较慢", AppLogTitleResolver.title("PlayerManager", "视频缓冲较慢"))
        assertEquals("视频播放", AppLogTitleResolver.featureLabel("PlayerManager", "播放器报告错误"))
    }
}
