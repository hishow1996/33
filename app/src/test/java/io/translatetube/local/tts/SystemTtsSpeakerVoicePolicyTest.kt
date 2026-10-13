package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemTtsSpeakerVoicePolicyTest {
    @Test
    fun unknownSpeakerAndSingleVoiceUseDefault() {
        assertEquals(0, SystemTtsSpeakerVoicePolicy.indexForSpeaker(-1, 4))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.indexForSpeaker(12, 1))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.indexForSpeaker(12, 0))
    }

    @Test
    fun knownSpeakersUseAlternativeVoiceCatalogEntries() {
        assertEquals(1, SystemTtsSpeakerVoicePolicy.indexForSpeaker(0, 3))
        assertEquals(2, SystemTtsSpeakerVoicePolicy.indexForSpeaker(1, 3))
        assertEquals(1, SystemTtsSpeakerVoicePolicy.indexForSpeaker(2, 3))
        assertEquals(2, SystemTtsSpeakerVoicePolicy.indexForSpeaker(53, 3))
    }
}
