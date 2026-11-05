package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTermContinuityPolicyTest {
    @Test
    fun extractsStableVisibleNamesAndProductTermsButNotCommonSentenceWords() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Sam met OpenAI in New York and later used iPhone.")
        )
        assertTrue(terms.contains("sam"))
        assertTrue(terms.contains("openai"))
        assertTrue(terms.contains("new"))
        assertTrue(terms.contains("york"))
        assertTrue(terms.contains("iphone"))
        assertFalse(terms.contains("the"))
        assertFalse(terms.contains("and"))
    }

    @Test
    fun matchesTermsCaseInsensitivelyWithoutGuessingTheirTranslations() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(listOf("Sam called OpenAI."))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("Yesterday SAM called.", terms))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("OpenAI replied.", terms))
        assertFalse(SubtitleTermContinuityPolicy.containsStableTerm("The meeting starts.", terms))
    }
    @Test
    fun filtersFrenchSentenceOpenersAndKeepsNamesAcrossLatinLanguageTracks() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Je parle avec Sam et OpenAI."),
            sourceLanguage = "fr",
        )
        assertFalse(terms.contains("je"))
        assertTrue(terms.contains("sam"))
        assertTrue(terms.contains("openai"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "Nous reparlons avec Sam.",
            terms,
            sourceLanguage = "fr",
        ))
    }

    @Test
    fun recognizesConservativeJapaneseAndKoreanNameMarkers() {
        val japaneseTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("田中さんはオープンAIを使います。"),
            sourceLanguage = "ja",
        )
        assertTrue(japaneseTerms.contains("田中"))
        assertTrue(japaneseTerms.contains("オープン"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "田中先生も来ます。",
            japaneseTerms,
            sourceLanguage = "ja",
        ))

        val koreanTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("김민수 씨가 OpenAI를 소개합니다."),
            sourceLanguage = "ko",
        )
        assertTrue(koreanTerms.contains("김민수"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "김민수님이 다시 말합니다.",
            koreanTerms,
            sourceLanguage = "ko",
        ))
    }

    @Test
    fun autoSourceLanguageRecognizesJapaneseAndKoreanTermScriptsConservatively() {
        assertEquals("ja", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("田中さんはオープンAIを使います。"),
            "auto",
        ))
        assertEquals("ko", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("김민수 씨가 다시 왔습니다."),
            "auto",
        ))
        assertEquals("auto", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("Sam arrived yesterday."),
            "auto",
        ))
    }

}
