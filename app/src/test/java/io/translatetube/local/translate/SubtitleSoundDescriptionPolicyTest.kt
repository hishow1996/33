package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSoundDescriptionPolicyTest {
    @Test
    fun recognizesCommonBracketedEnglishAndChineseSoundCaptions() {
        listOf(
            "[music ]",
            "[MUSIC]",
            "[background music]",
            "[applause]",
            "[laughter]",
            "[door opens]",
            "(crowd cheering)",
            "【音乐】",
            "[背景音乐]",
            "（掌声）",
        ).forEach { caption ->
            assertTrue(caption, SubtitleSoundDescriptionPolicy.isSoundDescription(caption))
        }
    }

    @Test
    fun doesNotTreatOrdinaryBracketedDialogueOrUnbracketedTextAsSoundCaptions() {
        listOf(
            "[Hello there]",
            "[music theory]",
            "[what is this?]",
            "music",
            "The music is beautiful.",
        ).forEach { caption ->
            assertFalse(caption, SubtitleSoundDescriptionPolicy.isSoundDescription(caption))
        }
    }

    @Test
    fun knownSoundCaptionsTranslateDeterministicallyAndPreserveBracketStyle() {
        assertEquals("[音乐]", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[music ]"))
        assertEquals("[音乐]", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[MUSIC]"))
        assertEquals("(观众欢呼声)", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("(crowd cheering)"))
        assertEquals("【开门声】", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("【door opens】"))
        assertEquals("[音乐渐弱]！", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[music fades out]！"))
        assertNull(SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[music theory]"))
    }

    @Test
    fun translatedSoundCaptionStillIdentifiesTheCueAsNonSpoken() {
        assertTrue(SubtitleSoundDescriptionPolicy.isSoundDescription("[音乐继续]"))
        assertTrue(SubtitleSoundDescriptionPolicy.isSoundDescription("[观众欢呼声]"))
    }
}
