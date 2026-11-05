package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSoundDescriptionPolicyTest {
    @Test
    fun recognizesCommonBracketedEnglishAndChineseSoundCaptions() {
        listOf(
            "[music ]",
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
    fun translatedSoundCaptionStillIdentifiesTheCueAsNonSpoken() {
        assertTrue(SubtitleSoundDescriptionPolicy.isSoundDescription("[音乐继续]"))
        assertTrue(SubtitleSoundDescriptionPolicy.isSoundDescription("[观众欢呼声]"))
    }
}
