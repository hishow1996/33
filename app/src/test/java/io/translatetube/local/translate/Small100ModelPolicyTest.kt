package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Small100ModelPolicyTest {
    @Test
    fun exposesAllHundredLanguagesFromThePinnedModel() {
        assertEquals(100, Small100LanguagePolicy.languages.size)
        assertEquals(100, Small100LanguagePolicy.expectedTokenMap().size)
        assertTrue(Small100LanguagePolicy.supportsSource("ja-JP"))
        assertTrue(Small100LanguagePolicy.supportsSource("af"))
        assertTrue(Small100LanguagePolicy.supportsSource("zh-Hans-CN"))
        assertFalse(Small100LanguagePolicy.supportsSource("xx"))
    }

    @Test
    fun canonicalizesCommonLanguageAliases() {
        assertEquals("he", Small100LanguagePolicy.canonicalCode("iw"))
        assertEquals("id", Small100LanguagePolicy.canonicalCode("in-ID"))
        assertEquals("no", Small100LanguagePolicy.canonicalCode("nb-NO"))
        assertEquals("zh", Small100LanguagePolicy.canonicalCode("zh-Hant-TW"))
    }
}
