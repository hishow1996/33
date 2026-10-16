package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProcessingFlowPolicyTest {
    @Test
    fun detectsSourceFromPlaybackSessionKey() {
        assertEquals(VideoSourceKind.YOUTUBE, VideoProcessingFlowPolicy.sourceKind("youtube:abc123"))
        assertEquals(VideoSourceKind.WEB, VideoProcessingFlowPolicy.sourceKind("remote:https://example.com/video.m3u8"))
        assertEquals(VideoSourceKind.LOCAL, VideoProcessingFlowPolicy.sourceKind("local:content://media/video/1"))
        assertEquals(VideoSourceKind.UNKNOWN, VideoProcessingFlowPolicy.sourceKind(""))
    }

    @Test
    fun eachSourceGetsItsOwnStepLabels() {
        val youtube = VideoProcessingFlowPolicy.steps(VideoSourceKind.YOUTUBE)
        val web = VideoProcessingFlowPolicy.steps(VideoSourceKind.WEB)
        val local = VideoProcessingFlowPolicy.steps(VideoSourceKind.LOCAL)

        assertEquals(5, youtube.size)
        assertEquals(5, web.size)
        assertEquals(5, local.size)
        assertTrue(youtube[1].contains("YouTube"))
        assertTrue(youtube[2].contains("原文字幕"))
        assertEquals("提取视频音轨", web[1])
        assertTrue(web[2].contains("语音转写"))
        assertEquals("提取本地视频音轨", local[1])
        assertTrue(local[0].contains("本地视频"))
        assertFalse(web.any { it.contains("下载或读取字幕") })
        assertFalse(local.any { it.contains("下载或读取字幕") })
    }

    @Test
    fun parsingAndTranscriptionTitlesReflectSource() {
        assertEquals("正在解析 YouTube 视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.YOUTUBE, Phase.PARSING))
        assertEquals("正在识别网页视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.WEB, Phase.PARSING))
        assertEquals("正在读取本地视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.LOCAL, Phase.PARSING))
        assertEquals("正在提取网页视频音轨",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.WEB, Phase.DOWNLOADING_AUDIO))
        assertEquals("正在转写本地视频语音",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.LOCAL, Phase.TRANSCRIBING))
    }

    @Test
    fun processingPageTitleDistinguishesSourceAndFailures() {
        assertEquals("YouTube 视频处理", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.YOUTUBE))
        assertEquals("网页视频处理", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.WEB))
        assertEquals("本地视频处理", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.LOCAL))
        assertEquals("网页视频 处理失败", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.WEB, failed = true))
    }
}
