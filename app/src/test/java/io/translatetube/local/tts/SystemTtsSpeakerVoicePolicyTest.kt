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
    fun prefersLocalVoicesButKeepsNetworkVoicesAsLastResort() {
        data class Candidate(val name: String, val network: Boolean)
        val candidates = listOf(
            Candidate("cloud-a", true),
            Candidate("local-a", false),
            Candidate("cloud-b", true),
        )
        assertEquals(
            listOf(Candidate("local-a", false)),
            SystemTtsSpeakerVoicePolicy.preferOfflineVoices(candidates) { it.network },
        )
        assertEquals(
            listOf(Candidate("cloud-a", true), Candidate("cloud-b", true)),
            SystemTtsSpeakerVoicePolicy.preferOfflineVoices(candidates.filter { it.network }) { it.network },
        )
    }

    @Test
    fun defaultVoiceSurvivesAnEmptyOrIncompleteOemCatalog() {
        data class Voice(val name: String)
        val current = Voice("rom-current-zh")

        assertEquals(
            current,
            SystemTtsSpeakerVoicePolicy.chooseDefaultVoice(current, emptyList()) { it.name },
        )
        assertEquals(
            Voice("local-a"),
            SystemTtsSpeakerVoicePolicy.chooseDefaultVoice(
                current,
                listOf(Voice("local-a"), Voice("local-b")),
            ) { it.name },
        )
        assertEquals(
            null,
            SystemTtsSpeakerVoicePolicy.chooseDefaultVoice<Voice>(null, emptyList()) { it.name },
        )
    }

    @Test
    fun knownSpeakersUseAlternativeVoiceCatalogEntries() {
        assertEquals(1, SystemTtsSpeakerVoicePolicy.indexForSpeaker(0, 3))
        assertEquals(2, SystemTtsSpeakerVoicePolicy.indexForSpeaker(1, 3))
        assertEquals(1, SystemTtsSpeakerVoicePolicy.indexForSpeaker(2, 3))
        assertEquals(2, SystemTtsSpeakerVoicePolicy.indexForSpeaker(53, 3))
    }
}
