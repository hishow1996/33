package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRecognitionProgressPolicyTest {
    @Test
    fun parsesMeasuredModelDownloadProgress() {
        assertEquals(37, SpeakerRecognitionProgressPolicy.fromMessage("下载人物声纹模型 · 37%"))
    }

    @Test
    fun activeChunkCountsOnlyEarlierCompletedChunks() {
        assertEquals(12, SpeakerRecognitionProgressPolicy.fromMessage("正在分析音频分段 2/8…"))
    }

    @Test
    fun completedChunkAdvancesProgress() {
        assertEquals(25, SpeakerRecognitionProgressPolicy.fromMessage("已分析音频分段 2/8"))
        assertEquals(100, SpeakerRecognitionProgressPolicy.fromMessage("已分析音频分段 8/8"))
    }

    @Test
    fun unknownWorkDoesNotInventPercentage() {
        assertNull(SpeakerRecognitionProgressPolicy.fromMessage("正在初始化人物识别模型…"))
    }

    @Test
    fun cueMappingProgressIsBoundedByTotal() {
        assertEquals(0, SpeakerRecognitionProgressPolicy.percentForCount(0, 20))
        assertEquals(55, SpeakerRecognitionProgressPolicy.percentForCount(11, 20))
        assertEquals(100, SpeakerRecognitionProgressPolicy.percentForCount(24, 20))
        assertNull(SpeakerRecognitionProgressPolicy.percentForCount(0, 0))
    }
}