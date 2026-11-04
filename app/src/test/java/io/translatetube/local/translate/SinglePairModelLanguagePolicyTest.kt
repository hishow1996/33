package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SinglePairModelLanguagePolicyTest {
    @Test
    fun supportsJapaneseKoreanAndRussianForDirectChineseTranslation() {
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ja"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ja-JP"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ko"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ko-KR"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ru"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ru-RU"))
    }

    @Test
    fun rejectsLanguagesOutsideTheSinglePairEntry() {
        assertFalse(SinglePairModelLanguagePolicy.supportsSource("en"))
        assertFalse(SinglePairModelLanguagePolicy.supportsSource("de"))
        assertFalse(SinglePairModelLanguagePolicy.supportsSource("zh"))
    }
}
