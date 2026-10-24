package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTitleResolverTest {
    @Test
    fun dubbingTitleNamesTheSpecificOutputStage() {
        assertEquals(
            "配音 / PCM 合成与缓存：配音 PCM 缓存预检完成",
            AppLogTitleResolver.title("DubbingEngine", "配音 PCM 缓存预检完成"),
        )
        assertEquals(
            "配音 / 音频混流：配音混流处理器写入缓冲失败",
            AppLogTitleResolver.title("DubbingMixer", "配音混流处理器写入缓冲失败"),
        )
        assertEquals(
            "配音 / 系统 TTS 朗读：系统 TTS 朗读失败",
            AppLogTitleResolver.title("DubbingEngine", "系统 TTS 朗读失败"),
        )
        assertEquals(
            "配音 / 译文缺失与原声回退：播放第 7/20 句时未检测到可播放中文配音，已恢复原声",
            AppLogTitleResolver.title(
                "DubbingFallback",
                "播放第 7/20 句时未检测到可播放中文配音，已恢复原声",
            ),
        )
    }

    @Test
    fun sourceDetectionTitlesDistinguishAudioTrackRecognition() {
        assertEquals(
            "视频源检测 / 音轨识别：检测到网页独立音轨",
            AppLogTitleResolver.title("WebAudioSource", "检测到网页独立音轨"),
        )
    }

    @Test
    fun ytdlpUpdateGetsItsOwnFeatureTitle() {
        assertEquals(
            "yt-dlp / 版本检查与更新：yt-dlp 更新下载失败",
            AppLogTitleResolver.title("YtDlpUpdater", "yt-dlp 更新下载失败"),
        )
    }

    @Test
    fun unknownTagsStillHaveAReadableModuleTitle() {
        val title = AppLogTitleResolver.title("CustomAudioManager", "初始化完成")
        assertTrue(title.startsWith("功能日志 / Custom Audio Manager："))
    }

    @Test
    fun blankMessageFallsBackToOriginalTag() {
        assertEquals(
            "诊断日志 / 记录与写入：AppLogStore",
            AppLogTitleResolver.title("AppLogStore", ""),
        )
    }
}
