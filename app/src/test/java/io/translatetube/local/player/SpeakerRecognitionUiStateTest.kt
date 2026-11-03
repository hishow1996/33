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
    fun stageTitleUsesTypedStageInsteadOfMessageText() {
        assertEquals(
            "解压人物模型",
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.PREPARING,
                stage = SpeakerRecognitionStage.EXTRACTING_MODELS,
                message = "正在解压人物分离模型…",
            ).stageTitle,
        )
        assertEquals(
            "初始化识别引擎",
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.PREPARING,
                stage = SpeakerRecognitionStage.INITIALIZING_MODEL,
                message = "正在初始化人物识别模型…",
            ).stageTitle,
        )
        assertEquals(
            "应用人物音色",
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.APPLYING,
                stage = SpeakerRecognitionStage.APPLYING_VOICES,
                message = "更新配音",
            ).stageTitle,
        )
        assertEquals(
            "区分完成",
            SpeakerRecognitionUiState(phase = SpeakerRecognitionPhase.SUCCEEDED).stageTitle,
        )
    }
}
