package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroVoiceRangeTest {
    @Test
    fun usesTheDocumentedZeroBased103VoiceRange() {
        assertEquals(103, KokoroVoiceRange.VOICE_COUNT)
        assertEquals(102, KokoroVoiceRange.MAX_SPEAKER_ID)
        assertEquals(14, KokoroVoiceRange.DEFAULT_SPEAKER_ID)
    }

    @Test
    fun clampsDefaultVoiceIdsWithoutCuttingOffVoice102() {
        assertEquals(0, KokoroVoiceRange.clampSpeakerId(-1))
        assertEquals(53, KokoroVoiceRange.clampSpeakerId(53))
        assertEquals(102, KokoroVoiceRange.clampSpeakerId(102))
        assertEquals(102, KokoroVoiceRange.clampSpeakerId(103))
    }

    @Test
    fun selectionPreservesTheDefaultSentinelAndSupportsAllSpeakerIds() {
        assertEquals(-1, KokoroVoiceRange.clampSelectionId(-1))
        assertEquals(0, KokoroVoiceRange.clampSelectionId(-5))
        assertEquals(102, KokoroVoiceRange.clampSelectionId(102))
        assertEquals(102, KokoroVoiceRange.clampSelectionId(999))
    }
    @Test
    fun mapsAll103DiarizedPersonIdsToDistinctVoicesAroundPreferredDefault() {
        val mapped = (0..KokoroVoiceRange.MAX_SPEAKER_ID).mapNotNull { personId ->
            KokoroVoiceRange.voiceForDiarizedSpeaker(personId, preferredVoiceId = 14)
        }

        assertEquals(103, mapped.size)
        assertEquals(103, mapped.distinct().size)
        assertEquals(-1, mapped.first())
        assertEquals(14, KokoroVoiceRange.voiceForDiarizedSpeaker(15, 14))
        assertEquals(102, KokoroVoiceRange.voiceForDiarizedSpeaker(102, 14))
        assertEquals(null, KokoroVoiceRange.voiceForDiarizedSpeaker(103, 14))
    }

}
