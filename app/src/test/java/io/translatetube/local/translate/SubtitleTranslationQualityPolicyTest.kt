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
    fun rejectsResponsesThatRemainInTheSourceLanguageInsteadOfChinese() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I think we should leave now.",
                "I believe we need to go now.",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "こんにちは、元気ですか。",
                "こんにちは、元気ですか。",
                "ja",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "안녕하세요, 만나서 반가워요.",
                "안녕하세요, 만나서 반가워요.",
                "ko",
                "zh-CN",
            ),
        )
    }

    @Test
    fun reportsSpecificReasonsForChineseTargetLanguageMismatch() {
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "I think we should leave now.",
                "I think we should leave now.",
                "en",
                "zh-CN",
            ).orEmpty().contains("未检测到汉字"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "今日はいい天気ですね。",
                "こんにちは、元気ですか。",
                "ja",
                "zh-CN",
            ).orEmpty().contains("日文假名占主导"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "오늘은 날씨가 좋네요.",
                "안녕하세요, 만나서 반가워요.",
                "ko",
                "zh-CN",
            ).orEmpty().contains("韩文音节占主导"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "I think we should leave now.",
                "当然，I think we should leave now.",
                "en",
                "zh-CN",
            ).orEmpty().contains("外语文本"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "Hello",
                "……",
                "en",
                "zh-CN",
            ).orEmpty().contains("标点/符号"),
        )
    }

    @Test
    fun acceptsChineseOutputAndLegitimateUntranslatedNamesAndNumbers() {
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Hello there.",
                "你好。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "J. K. Rowling",
                "J. K. Rowling",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "2026",
                "2026",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "你好",
                "你好",
                "zh-Hans",
                "zh-CN",
            ),
        )
    }

    @Test
    fun rejectsChinesePrefacesThatLeaveMostOfTheSentenceInEnglish() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I think we should leave now.",
                "当然，I think we should leave now.",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "We should probably wait a little longer.",
                "好的，我们 should probably wait a little longer.",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun acceptsChineseOutputThatRetainsSourceProperNamesAndModelNames() {
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Google Gemini is useful, and Spider-Man is popular.",
                "Google Gemini 很实用，Spider-Man 也很受欢迎。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The new iPhone model is available.",
                "新款 iPhone 已经上市。",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun onlyAnUnchangedProperNameIsExemptFromChineseTargetLanguageChecks() {
        assertFalse(SubtitleTranslationQualityPolicy.isTargetLanguageMismatch("Elon Musk", "Elon Musk", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isTargetLanguageMismatch("Elon Musk", "David Beckham", "en", "zh-CN"))
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
    fun diagnosticEvidenceExplainsHanScriptEchoAndSpeakabilityChecks() {
        val evidence = SubtitleTranslationQualityPolicy.diagnosticEvidence(
            sourceText = "Hello world",
            translatedText = "こんにちは",
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )
        assertTrue(evidence.contains("sourceLanguage=en"))
        assertTrue(evidence.contains("targetLanguage=zh-CN"))
        assertTrue(evidence.contains("han=0"))
        assertTrue(evidence.contains("kana=5"))
        assertTrue(evidence.contains("sourceEcho=false"))
        assertTrue(evidence.contains("targetLanguageMismatch=true"))
        assertTrue(evidence.contains("speakable=true"))
    }

    @Test
    fun acceptsConfirmedShortNamesOnlyWhenWordCountAndSpellingStillMatch() {
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John", "John", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah", "Sara", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah Smith", "Sara Smith", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("J. K. Rowling", "J. K. Rowling", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("(John):", "“John”", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("[Sarah]", "(Sara)", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Good Morning", "Good Morning", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("THANK YOU", "THANK YOU", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John", "David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah", "David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John Smith", "John David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John Smith", "John", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Goodbye", "Goodbye", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Wait", "Wait", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Hello", "Hello", "en", "zh-CN"))
    }

    @Test
    fun ordinaryWordsAndPhrasesReturnedUnchangedAreStillFailedTranslations() {
        listOf("Hello", "Thank you very much", "I think so.", "Good morning everyone", "Good Morning", "THANK YOU").forEach {
            assertTrue("'$it'", SubtitleTranslationQualityPolicy.isSourceEcho(it, it, "en"))
            assertFalse(
                "'$it' must not be accepted as an untranslated name",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    sourceText = it,
                    translatedText = it,
                    sourceLanguage = "en",
                    targetLanguage = "zh-CN",
                    acceptShortUnchanged = true,
                ),
            )
        }
    }
}
