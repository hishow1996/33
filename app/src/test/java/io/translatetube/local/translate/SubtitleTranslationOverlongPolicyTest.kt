package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTranslationOverlongPolicyTest {
    @Test
    fun unitsCountLettersDigitsAndIdeographsButNotPunctuationOrSpaces() {
        assertEquals(8, SubtitleTranslationOverlongPolicy.units("你好，世界！ ab 12"))
        assertEquals(0, SubtitleTranslationOverlongPolicy.units("。。。 ！"))
        assertEquals(0, SubtitleTranslationOverlongPolicy.units(null))
    }

    @Test
    fun defaultRatioDependsOnWhetherTheSourceIsAlphabetic() {
        assertEquals(0.7, SubtitleTranslationOverlongPolicy.defaultRatio("そうなんですね"), 1e-9)
        assertEquals(0.35, SubtitleTranslationOverlongPolicy.defaultRatio("Hello everyone"), 1e-9)
    }

    @Test
    fun baselineNeedsEnoughSamplesAndUsesTheMedian() {
        val source = "あ".repeat(10)
        val few = (1..7).map { source to "中".repeat(7) }
        assertNull(SubtitleTranslationOverlongPolicy.baselineRatio(few.asSequence()))

        // Eight normal rows plus one swallowed row: the median stays at the normal ratio.
        val enough = (1..8).map { source to "中".repeat(7) } + (source to "中".repeat(40))
        assertEquals(0.7, SubtitleTranslationOverlongPolicy.baselineRatio(enough.asSequence())!!, 1e-9)
    }

    @Test
    fun rowsFarLongerThanTheTypicalRatioAreOverlong() {
        val source = "あ".repeat(17)
        // expected length = 0.68 * 17 = 11.56 units; the limit is 1.8 times that (about 20.8).
        assertNotNull(SubtitleTranslationOverlongPolicy.overlongMultiple(source, "中".repeat(32), 0.68))
        assertNotNull(SubtitleTranslationOverlongPolicy.overlongMultiple(source, "中".repeat(22), 0.68))
        assertNull(SubtitleTranslationOverlongPolicy.overlongMultiple(source, "中".repeat(20), 0.68))
    }

    @Test
    fun shortTranslationsAreNeverOverlongEvenForTinySources() {
        assertNull(SubtitleTranslationOverlongPolicy.overlongMultiple("2", "中".repeat(15), 0.68))
        assertNull(SubtitleTranslationOverlongPolicy.overlongMultiple("", "中".repeat(30), 0.68))
        assertNull(SubtitleTranslationOverlongPolicy.overlongMultiple("あ".repeat(10), null, 0.68))
    }

    @Test
    fun aOneUnitSourceWithALongTranslationIsOverlong() {
        assertNotNull(SubtitleTranslationOverlongPolicy.overlongMultiple("2", "中".repeat(30), 0.68))
    }
}
