package io.translatetube.local.player

import io.translatetube.local.tts.KokoroVoiceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVoiceOptionPolicyTest {
    @Test
    fun kokoroPickerExposesAll103VoicesPlusDefault() {
        val options = SpeakerVoiceOptionPolicy.options(false, emptyList())
        assertEquals(KokoroVoiceRange.VOICE_COUNT + 1, options.size)
        assertEquals(-1, options.first().speakerId)
        assertEquals(0, options[1].speakerId)
        assertEquals(102, options.last().speakerId)
        assertTrue(options.last().label.contains("102"))
    }

    @Test
    fun systemTtsPickerOnlyListsInstalledVoicesWithoutDuplicateModuloAliases() {
        val voices = listOf("zh-default", "zh-female-a", "zh-male-b")
        val options = SpeakerVoiceOptionPolicy.options(true, voices)

        assertEquals(3, options.size)
        assertEquals(-1, options[0].speakerId)
        assertEquals(0, options[1].speakerId)
        assertEquals("zh-female-a", options[1].label)
        assertEquals(1, options[2].speakerId)
        assertEquals("zh-male-b", options[2].label)
        assertEquals("zh-female-a", SpeakerVoiceOptionPolicy.labelFor(4, true, voices))
    }

    @Test
    fun systemWithOnlyOneChineseVoiceExposesOnlyItsDefaultOption() {
        assertEquals(
            listOf(SpeakerVoiceOption(-1, "系统默认音色 · zh-only")),
            SpeakerVoiceOptionPolicy.options(true, listOf("zh-only")),
        )
    }
}
