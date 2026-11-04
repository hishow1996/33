package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationOnlyTranslationTest {
    @Test
    fun punctuationOnlyTranslationOfRealWordsIsRejected() {
        assertTrue(SubtitleTranslationQualityPolicy.isPunctuationOnlyTranslation("Yes, I know.", "。"))
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Yes, I know.", "……", "en"))
    }

    @Test
    fun realTranslationsAndSymbolOnlySourcesAreAccepted() {
        assertFalse(SubtitleTranslationQualityPolicy.isPunctuationOnlyTranslation("Mm.", "嗯。"))
        assertFalse(SubtitleTranslationQualityPolicy.isPunctuationOnlyTranslation("...", "。"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("Yes.", "是的。", "en"))
    }

    @Test
    fun chineseSourceIsNeverTreatedAsFailed() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("你好", "。", "zh"))
    }
}
