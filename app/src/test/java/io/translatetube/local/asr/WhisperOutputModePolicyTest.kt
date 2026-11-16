package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperOutputModePolicyTest {
    @Test
    fun originalLanguageIsTheDefaultAndInvalidValuesFallBackSafely() {
        assertEquals(WhisperOutputModePolicy.TRANSCRIBE, WhisperOutputModePolicy.normalize(null))
        assertEquals(WhisperOutputModePolicy.TRANSCRIBE, WhisperOutputModePolicy.normalize("unknown"))
        assertEquals(WhisperOutputModePolicy.TRANSCRIBE, WhisperOutputModePolicy.normalize("transcribe"))
    }

    @Test
    fun englishTranslationModeIsNormalized() {
        assertEquals(WhisperOutputModePolicy.TRANSLATE_TO_ENGLISH, WhisperOutputModePolicy.normalize("translate"))
        assertEquals(WhisperOutputModePolicy.TRANSLATE_TO_ENGLISH, WhisperOutputModePolicy.normalize("english"))
    }

    @Test
    fun translatedTextIsReportedAsEnglishToTheFollowingTranslationStage() {
        assertEquals("en", WhisperOutputModePolicy.outputLanguage(WhisperOutputModePolicy.TRANSLATE_TO_ENGLISH, "ru"))
        assertEquals("ru", WhisperOutputModePolicy.outputLanguage("transcribe", "ru"))
    }
}
