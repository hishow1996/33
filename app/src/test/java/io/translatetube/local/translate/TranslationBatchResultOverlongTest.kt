package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchResultOverlongTest {
    private val sources = mapOf(
        10 to "Good morning.",
        11 to "Hello everyone and good morning",
        12 to "Okay, let's go.",
        13 to "Thank you all.",
    )
    private val swallowing = "大家早上好，今天我们要去机场，然后坐飞机去中国出差工作"
    private val raw = listOf("早上好。", swallowing, "好的，我们出发。", "谢谢大家。")

    private fun parse(rejectOverlong: Boolean) = TranslationBatchResult.from(
        indices = listOf(10, 11, 12, 13),
        rawTranslations = raw,
        sourceTexts = sources,
        sourceLanguage = "en",
        targetLanguage = "zh-CN",
        rejectOverlong = rejectOverlong,
        overlongBaselineRatio = 0.35,
    )

    @Test
    fun overlongRowAndEveryRowAfterItAreSentBackForRetry() {
        val result = parse(rejectOverlong = true)

        assertEquals(mapOf(10 to "早上好。"), result.translations)
        assertEquals(listOf(11, 12, 13), result.missingIndices)
        assertEquals(
            listOf("TRANSLATION_OVERLONG", "AFTER_OVERLONG_ROW", "AFTER_OVERLONG_ROW"),
            result.rejectedRows.map { it.reasonCode },
        )
    }

    @Test
    fun heldBackTranslationsKeepTheOriginalValidResultsAsALastResort() {
        val result = parse(rejectOverlong = true)

        assertEquals(setOf(11, 12, 13), result.heldBackTranslations.keys)
        assertEquals(swallowing, result.heldBackTranslations[11])
        assertEquals("好的，我们出发。", result.heldBackTranslations[12])
    }

    @Test
    fun theFinalAttemptAcceptsEverythingSoOverlongRowsNeverFailTheTask() {
        val result = parse(rejectOverlong = false)

        assertEquals(setOf(10, 11, 12, 13), result.translations.keys)
        assertEquals(swallowing, result.translations[11])
        assertTrue(result.missingIndices.isEmpty())
        assertTrue(result.heldBackTranslations.isEmpty())
    }

    @Test
    fun normalRowsAreUntouchedWhenNothingIsOverlong() {
        val result = TranslationBatchResult.from(
            indices = listOf(10, 12, 13),
            rawTranslations = listOf("早上好。", "好的，我们出发。", "谢谢大家。"),
            sourceTexts = sources,
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            rejectOverlong = true,
            overlongBaselineRatio = 0.35,
        )

        assertEquals(setOf(10, 12, 13), result.translations.keys)
        assertTrue(result.missingIndices.isEmpty())
        assertTrue(result.heldBackTranslations.isEmpty())
    }
}
