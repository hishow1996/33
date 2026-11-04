package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class LangMapTest {
    @Test
    fun mlkitSourceTreatsScriptAndRegionTaggedSentinelsAsUnknown() {
        assertEquals("en", LangMap.mlkitSource("und-Latn"))
        assertEquals("en", LangMap.mlkitSource("unknown-US"))
        assertEquals("en", LangMap.mlkitSource("auto-US"))
        assertEquals("ja", LangMap.mlkitSource("ja-JP"))
    }

    @Test
    fun opusModelAcceptsOnlyEnglishSourceLanguage() {
        assertEquals(true, LocalTranslationEnginePolicy.supportsOpusSource("en"))
        assertEquals(true, LocalTranslationEnginePolicy.supportsOpusSource("en-US"))
        assertEquals(true, LocalTranslationEnginePolicy.supportsOpusSource("eng"))
        assertEquals(false, LocalTranslationEnginePolicy.supportsOpusSource("fr"))
        assertEquals(false, LocalTranslationEnginePolicy.supportsOpusSource("auto"))
        assertEquals(false, LocalTranslationEnginePolicy.supportsOpusSource("und-Latn"))
    }

    @Test
    fun googleLanguageTreatsTaggedSentinelsAsAutomaticDetection() {
        assertEquals("auto", LangMap.google("und-Latn"))
        assertEquals("auto", LangMap.google("auto-US"))
        assertEquals("zh-CN", LangMap.google("und-Latn", target = true))
        assertEquals("zh-TW", LangMap.google("zh-Hant-HK", target = true))
    }

    @Test
    fun displaySupportsScriptAndRegionTaggedCantoneseAndMandarin() {
        assertEquals("粤语", LangMap.display("yue-Hant-HK"))
        assertEquals("中文", LangMap.display("cmn-Hans-CN"))
    }
    @Test
    fun mlkitSupportIncludesExpandedTranslationLanguages() {
        listOf("af", "be", "bg", "ca", "cy", "eo", "et", "fa", "ga", "gl", "gu", "hr", "ht", "is", "ka", "kn", "lt", "lv", "mk", "mr", "mt", "sk", "sl", "sq", "sw", "ta", "te", "tl", "ur")
            .forEach { assertEquals(true, LangMap.isMlkitSupported(it)) }
    }

    @Test
    fun mlkitCanonicalizesHebrewAndNorwegianAliases() {
        assertEquals("he", LangMap.mlkit("iw"))
        assertEquals("no", LangMap.mlkit("nb"))
        assertEquals("no", LangMap.mlkit("nn"))
    }
}
