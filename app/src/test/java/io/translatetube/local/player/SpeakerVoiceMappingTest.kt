package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVoiceMappingTest {
    @Test
    fun firstDiarizedSpeakerUsesConfiguredDefaultVoice() {
        assertEquals(-1, SpeakerVoiceMapping.voiceForSpeaker(0, 14))
        assertEquals(-1, SpeakerVoiceMapping.voiceForSpeaker(-1, 14))
    }

    @Test
    fun mappedPresetVoicesReserveTheConfiguredDefault() {
        val preferredDefault = 14
        val mapped = (1..5).map { SpeakerVoiceMapping.voiceForSpeaker(it, preferredDefault) }
        assertEquals(listOf(0, 1, 2, 3, 4), mapped)
        assertFalse(mapped.contains(preferredDefault))
        assertTrue(mapped.all { it in 0..53 })
    }

    @Test
    fun cueUsesTheSpeakerWithMostOverlappingAudio() {
        val segments = listOf(
            SpeakerVoiceMapping.Segment(0.0f, 1.1f, 1),
            SpeakerVoiceMapping.Segment(1.1f, 2.8f, 2),
        )
        assertEquals(1, SpeakerVoiceMapping.voiceForCue(1_200, 2_000, segments, 14))
        assertEquals(0, SpeakerVoiceMapping.voiceForCue(100, 800, segments, 14))
    }

    @Test
    fun cueWithoutEnoughOverlapUsesConfiguredDefault() {
        val segments = listOf(SpeakerVoiceMapping.Segment(1.0f, 1.1f, 3))
        assertEquals(-1, SpeakerVoiceMapping.voiceForCue(0, 1_000, segments, 14))
        assertEquals(-1, SpeakerVoiceMapping.voiceForCue(500, 300, segments, 14))
    }

    @Test
    fun lowConfidenceSegmentsDoNotOverrideDefaultVoice() {
        val segments = listOf(
            SpeakerVoiceMapping.Segment(0.0f, 1.0f, 4, confidence = 0.12f),
        )
        assertEquals(-1, SpeakerVoiceMapping.voiceForCue(100, 900, segments, 14))
    }

    @Test
    fun highConfidenceSpeakerWinsEvenWhenLowConfidenceSegmentOverlapsMore() {
        val segments = listOf(
            SpeakerVoiceMapping.Segment(0.0f, 0.9f, 2, confidence = 0.10f),
            SpeakerVoiceMapping.Segment(0.2f, 0.7f, 3, confidence = 0.91f),
        )
        assertEquals(2, SpeakerVoiceMapping.voiceForCue(100, 900, segments, 14))
    }

    @Test
    fun invalidConfidenceIsTreatedAsUnknownRatherThanCrashing() {
        val segments = listOf(
            SpeakerVoiceMapping.Segment(0.0f, 1.0f, 2, confidence = Float.NaN),
        )
        assertEquals(1, SpeakerVoiceMapping.voiceForCue(100, 900, segments, 14))
    }

    @Test
    fun voiceSelectionsAreRangeChecked() {
        assertTrue(SpeakerVoiceMapping.isValidVoiceSelection(-1))
        assertTrue(SpeakerVoiceMapping.isValidVoiceSelection(53))
        assertFalse(SpeakerVoiceMapping.isValidVoiceSelection(-2))
        assertFalse(SpeakerVoiceMapping.isValidVoiceSelection(54))
    }
}
