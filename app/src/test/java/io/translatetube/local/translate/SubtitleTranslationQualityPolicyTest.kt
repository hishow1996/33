package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationQualityPolicyTest {
    @Test
    fun detectsSourceEchoesEvenWhenOnlyTrailingPunctuationDiffers() {
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", " hello   WORLD. ", "en"))
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Bonjour à tous!", "bonjour à tous", "fr"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", "你好世界。", "en"))
    }

    @Test
    fun doesNotMarkChineseSourceCaptionsAsMissingTranslations() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("正在翻译", "正在翻译", "zh-Hans"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("你好", "你好", "cmn"))
    }

    @Test
    fun blanksAreHandledAsMissingByTheCallerRatherThanEchoes() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("", "hello", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("hello", " ", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho(null, "hello", "en"))
    }
    @Test
    fun acceptsNumbersUrlsAndEmailAddressesWhenTheyRemainUnchanged() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("2026", "2026", "en"))
        assertFalse(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "https://example.com/watch?v=42",
                "https://example.com/watch?v=42",
                "en",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "info@example.com",
                "info@example.com",
                "en",
            ),
        )
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("— …", "— …", "en"))
    }

    @Test
    fun ignoresInternalPunctuationDifferencesWhenDetectingAnUntranslatedEcho() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "Hello, world!",
                "hello world",
                "en",
            ),
        )
    }



    @Test
    fun shortTermsAcronymsAndNamesMayStayUnchanged() {
        listOf("H-1B", "NASA", "COVID-19", "iPhone 15", "Elon Musk", "Silicon Valley", "OK", "J. K. Rowling", "YouTube").forEach {
            assertFalse("'$it'", SubtitleTranslationQualityPolicy.isSourceEcho(it, it, "en"))
        }
    }

    @Test
    fun ordinaryWordsAndPhrasesReturnedUnchangedAreStillFailedTranslations() {
        listOf("Hello", "Thank you very much", "I think so.", "Good morning everyone").forEach {
            assertTrue("'$it'", SubtitleTranslationQualityPolicy.isSourceEcho(it, it, "en"))
        }
    }
}
