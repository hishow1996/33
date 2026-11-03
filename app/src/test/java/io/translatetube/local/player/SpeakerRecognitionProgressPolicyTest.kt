package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRecognitionProgressPolicyTest {
    @Test
    fun singleChunkUsesIndeterminateProgressWhileRunning() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkStarted(1L, 1L)
        assertEquals(SpeakerRecognitionStage.ANALYZING_AUDIO, update.stage)
        assertNull(update.progressPercent)
        assertEquals(0L, update.completedUnits)
        assertEquals(1L, update.totalUnits)
    }

    @Test
    fun multiChunkProgressUsesOnlyCompletedChunks() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkStarted(2L, 5L)
        assertEquals(20, update.progressPercent)
        assertEquals(1L, update.completedUnits)
        assertEquals(5L, update.totalUnits)
    }

    @Test
    fun completedChunkAdvancesMeasuredProgress() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkCompleted(2L, 5L)
        assertEquals(40, update.progressPercent)
        assertEquals("已完成音频分析", update.message)
    }

    @Test
    fun completedSingleChunkReachesOneHundredPercent() {
        assertEquals(100, SpeakerRecognitionProgressPolicy.analysisChunkCompleted(1L, 1L).progressPercent)
    }

    @Test
    fun nativeInferenceProgressUsesCompletedAudioChunksAndCurrentWorkFraction() {
        val update = SpeakerRecognitionProgressPolicy.analysisInferenceProgress(
            completedChunks = 1L,
            totalChunks = 4L,
            processedInferenceUnits = 2L,
            totalInferenceUnits = 4L,
        )
        assertEquals(37, update.progressPercent)
        assertEquals(1L, update.completedUnits)
        assertEquals(4L, update.totalUnits)
        assertEquals("模型内部分析", update.progressItem)
    }

    @Test
    fun nativeInferenceProgressClampsInvalidCounts() {
        val update = SpeakerRecognitionProgressPolicy.analysisInferenceProgress(
            completedChunks = 8L,
            totalChunks = 4L,
            processedInferenceUnits = 10L,
            totalInferenceUnits = 2L,
        )
        assertEquals(100, update.progressPercent)
        assertEquals(4L, update.completedUnits)
        assertEquals(4L, update.totalUnits)
    }

    @Test
    fun percentForCountIsBoundedAndRejectsUnknownTotal() {
        assertEquals(0, SpeakerRecognitionProgressPolicy.percentForCount(0L, 20L))
        assertEquals(55, SpeakerRecognitionProgressPolicy.percentForCount(11L, 20L))
        assertEquals(100, SpeakerRecognitionProgressPolicy.percentForCount(24L, 20L))
        assertNull(SpeakerRecognitionProgressPolicy.percentForCount(0L, 0L))
    }
}