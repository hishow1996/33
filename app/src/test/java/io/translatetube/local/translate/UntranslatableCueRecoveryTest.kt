package io.translatetube.local.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cues with nothing to translate (interjections, vocalizations, names, symbols) must never make a
 * whole subtitle translation fail, while ordinary sentences returned untranslated still must.
 */
class UntranslatableCueRecoveryTest {
    // ---- interjections / vocalizations: fixed word list + general rules ----

    @Test
    fun stretchedAndRepeatedVocalizationsGetChineseSubstitutes() {
        fun zh(text: String) = SubtitleInterjectionPolicy.chineseFor(text, "en", "zh")
        assertEquals("啊", zh("Aaaaah"))
        assertEquals("哦", zh("Ooooh!").orEmpty().trimEnd('！'))
        assertEquals("哈哈", zh("Ha-ha-ha"))
        assertEquals("哈哈", zh("Hehehe"))
        assertEquals("哈哈哈", zh("Mwahaha"))
        assertEquals("嘘", zh("Shhh"))
        assertEquals("呜", zh("Grrr"))
        assertEquals("噗", zh("Pfft"))
        assertEquals("啧", zh("Tsk"))
        assertEquals("啦啦啦", zh("La la la"))
        assertEquals("啦啦啦", zh("Na na na"))
        assertEquals("耶", zh("Woohoo"))
        assertEquals("是啊", zh("Yeaaaah"))
    }

    @Test
    fun ordinaryWordsAndAbbreviationsAreNeverTreatedAsInterjections() {
        fun zh(text: String) = SubtitleInterjectionPolicy.chineseFor(text, "en", "zh")
        assertNull(zh("He."))
        assertNull(zh("She left."))
        assertNull(zh("Mr. Smith"))
        assertNull(zh("TV"))
        assertNull(zh("NSFW"))
        assertNull(zh("Dr."))
        assertNull(zh("Where are you?"))
        assertNull(zh("Sarah"))
        assertNull(zh("Hello world"))
        // Wrong source language or target language never uses the English substitutes.
        assertNull(SubtitleInterjectionPolicy.chineseFor("Aaaaah", "ja", "zh"))
        assertNull(SubtitleInterjectionPolicy.chineseFor("Aaaaah", "en", "ja"))
    }

    @Test
    fun unknownVocalizationReturnedAsEchoOrPunctuationIsFilledInsteadOfFailing() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = listOf("Aaaaah!", "。", ""),
            sourceTexts = mapOf(0 to "Aaaaah!", 1 to "Shhh...", 2 to "La la la"),
            sourceLanguage = "en",
            targetLanguage = "zh",
        )

        assertTrue(result.missingIndices.isEmpty())
        assertEquals(3, result.translations.size)
        assertTrue(result.translations.values.all { it.any { c -> c in '一'..'鿿' } })
    }

    // ---- short names / loanwords that legitimately stay unchanged ----

    @Test
    fun shortNameEchoIsRejectedFirstButAcceptedAfterRetry() {
        val strict = TranslationBatchResult.from(
            indices = listOf(5),
            rawTranslations = listOf("Sarah."),
            sourceTexts = mapOf(5 to "Sarah."),
            sourceLanguage = "en",
            targetLanguage = "zh",
        )
        assertEquals(listOf(5), strict.missingIndices)

        val afterRetry = TranslationBatchResult.from(
            indices = listOf(5),
            rawTranslations = listOf("Sarah."),
            sourceTexts = mapOf(5 to "Sarah."),
            sourceLanguage = "en",
            targetLanguage = "zh",
            acceptShortUnchanged = true,
        )
        assertTrue(afterRetry.missingIndices.isEmpty())
        assertEquals(mapOf(5 to "Sarah."), afterRetry.translations)
    }

    @Test
    fun longSentenceEchoStillFailsEvenAfterRetry() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1),
            rawTranslations = listOf("I will see you tomorrow.", "Where are you going?"),
            sourceTexts = mapOf(0 to "I will see you tomorrow.", 1 to "Where are you going?"),
            sourceLanguage = "en",
            targetLanguage = "zh",
            acceptShortUnchanged = true,
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(0, 1), result.missingIndices)
    }

    @Test
    fun shortUntranslatedRuleIsNarrow() {
        fun ok(source: String, out: String, src: String = "en", tgt: String = "zh") =
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(source, out, src, tgt)

        assertTrue(ok("Sarah?", "Sarah?"))
        assertTrue(ok("Taxi", "Taxi"))
        assertTrue(ok("Tom Hanks?", "Tom Hanks?"))
        assertTrue(ok("Sarah", "Sara"))
        // Multi-word lowercase-led cues are sentences, not names.
        assertFalse(ok("Thank you.", "Thank you."))
        assertFalse(ok("I know.", "I know."))
        // More than three words, non-Latin scripts, digits and Chinese sources are excluded.
        assertFalse(ok("Tom Hanks Is Here Now", "Tom Hanks Is Here Now"))
        assertFalse(ok("Привет", "Привет"))
        assertFalse(ok("東京", "東京", src = "ja"))
        assertFalse(ok("Sarah", "Sarah", src = "zh"))
        assertFalse(ok("Sarah", "Sarah", tgt = "ja"))
        assertFalse(ok("Sarah", "。"))
        assertFalse(ok("Sarah", ""))
    }

    @Test
    fun symbolOnlySourceHasNothingToSpeak() {
        assertTrue(SubtitleTranslationQualityPolicy.hasNothingToSpeak("♪♪"))
        assertTrue(SubtitleTranslationQualityPolicy.hasNothingToSpeak("..."))
        assertFalse(SubtitleTranslationQualityPolicy.hasNothingToSpeak("Hello"))
        assertFalse(SubtitleTranslationQualityPolicy.hasNothingToSpeak("2026"))
        assertFalse(SubtitleTranslationQualityPolicy.hasNothingToSpeak(""))
    }

    // ---- model-engine batch recovery uses the same rules ----

    @Test
    fun batchRecoveryDoesNotSpendRetriesOnInterjections() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Hello there my friend", "Mm-hmm.", "Aaaaah!"),
            isAcceptableTranslation = subtitleTranslationAcceptor("en", "zh"),
            acceptAfterRecovery = subtitleShortUntranslatedAcceptor("en", "zh"),
        ) { rows, _ ->
            calls += rows
            listOf("你好，我的朋友", "Mm-hmm.", "Aaaaah!")
        }

        // One request only: the interjections are repaired later by TranslationBatchResult.
        assertEquals(1, calls.size)
        assertEquals(listOf("你好，我的朋友", "Mm-hmm.", "Aaaaah!"), result)
    }

    @Test
    fun batchRecoveryAcceptsStubbornShortNamesInsteadOfAbortingTheBatch() = runBlocking {
        val names = listOf("Sarah.", "Michael.", "Jennifer.", "Robert.")
        val result = translateBatchWithAdaptiveRecovery(
            lines = names,
            isAcceptableTranslation = subtitleTranslationAcceptor("en", "zh"),
            retrySingle = { source, _ -> source },
            acceptAfterRecovery = subtitleShortUntranslatedAcceptor("en", "zh"),
        ) { rows, _ -> rows }

        // More names than the isolated-failure budget allows; none of them may fail the batch.
        assertEquals(names, result)
    }

    @Test
    fun batchRecoveryStillFailsWhenOrdinarySentencesKeepComingBackUntranslated() {
        val sentences = listOf(
            "I will see you tomorrow.",
            "Where are you going now?",
            "Please close the door.",
            "That was a great meal.",
        )
        val error = runCatching {
            runBlocking {
                translateBatchWithAdaptiveRecovery(
                    lines = sentences,
                    isAcceptableTranslation = subtitleTranslationAcceptor("en", "zh"),
                    retrySingle = { source, _ -> source },
                    acceptAfterRecovery = subtitleShortUntranslatedAcceptor("en", "zh"),
                ) { rows, _ -> rows }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun acceptorRejectsPunctuationOnlyAndWrongLanguageOutput() {
        val accept = subtitleTranslationAcceptor("en", "zh")
        assertFalse(accept("Where are you?", "。"))
        assertFalse(accept("Where are you?", "Where are you?"))
        assertFalse(accept("Where are you?", "Where is it"))
        assertFalse(accept("Where are you?", ""))
        assertTrue(accept("Where are you?", "你在哪里？"))
    }
}
