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
        // -1 means the configured preferred voice (14), so compare resolved model IDs.
        val actualVoiceIds = mapped.map { if (it == -1) 14 else it }

        assertEquals(103, actualVoiceIds.size)
        assertEquals(103, actualVoiceIds.distinct().size)
        assertEquals(14, actualVoiceIds.first())
        assertEquals(15, KokoroVoiceRange.voiceForDiarizedSpeaker(15, 14))
        assertEquals(102, KokoroVoiceRange.voiceForDiarizedSpeaker(102, 14))
        assertEquals(null, KokoroVoiceRange.voiceForDiarizedSpeaker(103, 14))
    }

}
