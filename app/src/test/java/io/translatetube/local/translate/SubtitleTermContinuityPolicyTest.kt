package io.translatetube.local.translate

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
}
