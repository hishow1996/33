package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun knownSpeakerSlotsAddressDifferentAlternativesWithoutWrappingUnavailableIds() {
        assertEquals(1, SystemTtsSpeakerVoicePolicy.indexForSpeaker(0, 3))
        assertEquals(2, SystemTtsSpeakerVoicePolicy.indexForSpeaker(1, 3))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.indexForSpeaker(2, 3))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.indexForSpeaker(53, 3))
    }

    @Test
    fun systemSpeakerMappingIgnoresKokoroPreferenceAndUsesAvailableSlotsFirst() {
        assertEquals(0, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("system", 1, 3, 14))
        assertEquals(1, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("system", 2, 3, 14))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("system", 3, 3, 1))
        assertEquals(-1, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("system", 1, 1, 14))
    }

    @Test
    fun kokoroMappingStillSkipsTheConfiguredDefaultTone() {
        assertEquals(-1, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("kokoro", 0, 0, 14))
        assertEquals(0, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("kokoro", 1, 0, 14))
        assertEquals(15, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("kokoro", 15, 0, 14))
        assertEquals(-1, SystemTtsSpeakerVoicePolicy.voiceForCanonicalSpeaker("piper", 2, 0, 14))
    }

    @Test
    fun voiceCatalogRoundTripPreservesOrderingAndRemovesDuplicates() {
        val encoded = SystemTtsSpeakerVoicePolicy.encodeVoiceCatalog(
            listOf("zh-default", "zh-alt-a", "zh-alt-a", " zh-alt-b "),
        )
        assertEquals(
            listOf("zh-default", "zh-alt-a", "zh-alt-b"),
            SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(encoded),
        )
    }

    @Test
    fun invalidOversizedAndEmptyCatalogsAreSafe() {
        assertTrue(SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(null).isEmpty())
        assertTrue(SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog("").isEmpty())
        assertTrue(
            SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog("x".repeat(8_193)).isEmpty(),
        )
    }

    @Test
    fun systemLabelsResolveToNamesOrWarnAboutStaleIds() {
        val catalog = listOf("默认中文", "中文女声", "中文男声")
        assertEquals("系统默认 · 默认中文", SystemTtsSpeakerVoicePolicy.displayLabel(-1, "system", catalog))
        assertEquals("中文女声", SystemTtsSpeakerVoicePolicy.displayLabel(0, "system", catalog))
        assertEquals("系统音色编号 9（当前不可用）", SystemTtsSpeakerVoicePolicy.displayLabel(9, "system", catalog))
    }
}
