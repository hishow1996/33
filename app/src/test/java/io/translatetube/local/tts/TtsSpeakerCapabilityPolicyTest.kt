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
    fun singleSpeakerPiperDoesNotClaimToSupportDistinctVoices() {
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("piper"))
        assertFalse(TtsSpeakerCapabilityPolicy.supportsDistinctVoices("unknown"))
    }
}
