package io.translatetube.local.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSpeakerCapabilityPolicyTest {
    @Test
    fun kokoroSupportsDistinctSpeakerVoices() {
        assertTrue(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("kokoro"))
    }

    @Test
    fun systemTtsRequiresAtLeastTwoChineseVoices() {
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("system", 0))
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("system", 1))
        assertTrue(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("system", 2))
    }

    @Test
    fun edgeAndGeminiSupportDistinctSpeakerVoices() {
        assertTrue(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("edge"))
        assertTrue(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("gemini"))
    }

    @Test
    fun singleSpeakerLocalModelsDoNotClaimToSupportDistinctVoices() {
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("piper"))
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("melo"))
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("matcha"))
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("unknown"))
    }
}
