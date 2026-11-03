package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRecognitionUiStateTest {
    @Test
    fun activePhasesAreBusy() {
        val active = listOf(
            SpeakerRecognitionPhase.PREPARING,
            SpeakerRecognitionPhase.DOWNLOADING_MODELS,
            SpeakerRecognitionPhase.ANALYZING,
            SpeakerRecognitionPhase.APPLYING,
            SpeakerRecognitionPhase.CANCELLING,
        )
        active.forEach { phase ->
            assertTrue(SpeakerRecognitionUiState(phase = phase).isActive)
        }
    }

    @Test
    fun terminalPhasesAreNotBusy() {
        val terminal = listOf(
            SpeakerRecognitionPhase.IDLE,
            SpeakerRecognitionPhase.SUCCEEDED,
            SpeakerRecognitionPhase.FAILED,
            SpeakerRecognitionPhase.CANCELLED,
        )
        terminal.forEach { phase ->
            assertFalse(SpeakerRecognitionUiState(phase = phase).isActive)
        }
    }

    @Test
    fun stageTitleShowsOnlyTheCurrentStage() {
        assertEquals("音频准备", SpeakerRecognitionUiState(phase = SpeakerRecognitionPhase.PREPARING).stageTitle)
        assertEquals("下载人物模型", SpeakerRecognitionUiState(phase = SpeakerRecognitionPhase.DOWNLOADING_MODELS).stageTitle)
        assertEquals("人物声音分析", SpeakerRecognitionUiState(phase = SpeakerRecognitionPhase.ANALYZING).stageTitle)
        assertEquals("关联字幕与人物", SpeakerRecognitionUiState(phase = SpeakerRecognitionPhase.APPLYING).stageTitle)
        assertEquals(
            "应用人物音色",
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.APPLYING,
                message = "已识别 3 位人物，正在更新配音…",
            ).stageTitle,
        )
    }
}
