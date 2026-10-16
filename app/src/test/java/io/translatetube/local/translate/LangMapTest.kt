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
}
