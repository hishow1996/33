package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsProviderSpeedPolicyTest {
    @Test
    fun googleNativeAndPcmRatesRespectRequestedSpeed() {
        assertEquals(0.5f, TtsProviderSpeedPolicy.googleNativeSpeed(0.5f), 0.001f)
        assertEquals(1.0f, TtsProviderSpeedPolicy.googleNativeSpeed(2.0f), 0.001f)
        assertEquals(1.0f, TtsProviderSpeedPolicy.googlePostStretchRatio(0.75f), 0.001f)
        assertEquals(0.5f, TtsProviderSpeedPolicy.googlePostStretchRatio(2.0f), 0.001f)
        assertEquals(1.0f, TtsProviderSpeedPolicy.normalize(Float.NaN), 0.001f)
    }

    @Test
    fun geminiPaceInstructionReflectsRequestedRate() {
        assertTrue(TtsProviderSpeedPolicy.geminiPaceInstruction(0.7f).contains("0.70"))
        assertTrue(TtsProviderSpeedPolicy.geminiPaceInstruction(1.5f).contains("1.50"))
        assertTrue(TtsProviderSpeedPolicy.geminiPaceInstruction(1.5f).contains("较快"))
    }

    @Test
    fun firstGeminiSpeakerUsesSelectedDefaultVoice() {
        assertEquals("Fenrir", GeminiTtsVoices.voiceForSpeaker(0, "Fenrir"))
        assertEquals("Fenrir", GeminiTtsVoices.voiceForSpeaker(-1, "Fenrir"))
        assertTrue(GeminiTtsVoices.voiceForSpeaker(1, "Fenrir") != "Fenrir")
    }
}
