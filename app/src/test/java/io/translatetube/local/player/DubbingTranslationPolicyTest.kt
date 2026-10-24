package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DubbingTranslationPolicyTest {
    @Test
    fun neverTurnsProgressPlaceholdersIntoSpokenAudio() {
        assertNull(DubbingTranslationPolicy.usableText("This is a line", "正在翻译"))
        assertNull(DubbingTranslationPolicy.usableText("This is a line", "翻译中 2/5 行"))
    }

    @Test
    fun rejectsAnUntranslatedSourceEchoBeforeBuildingThePcmCache() {
        assertNull(DubbingTranslationPolicy.usableText("Hello world.", "Hello world"))
        assertEquals("你好世界。", DubbingTranslationPolicy.usableText("Hello world", "你好世界。"))
    }

    @Test
    fun preservesLiteralChineseSubtitlesThatResembleProgressLabels() {
        assertEquals("正在翻译", DubbingTranslationPolicy.usableText("正在翻译", "正在翻译"))
    }

    @Test
    fun blankTranslationProvidesSpecificDiagnosticReason() {
        assertNull(DubbingTranslationPolicy.usableText("Hello world", null))
        assertNull(DubbingTranslationPolicy.usableText("Hello world", "   "))
        assertEquals(
            "翻译结果为空或只有空白字符",
            DubbingTranslationPolicy.resolveText("Hello world", null).reason,
        )
    }

    @Test
    fun sourceEchoProvidesSpecificDiagnosticReason() {
        val result = DubbingTranslationPolicy.resolveText("Hello world.", "Hello world")
        assertNull(result.text)
        assertEquals("译文与原文相同，命中原文回显过滤规则", result.reason)
    }

    @Test
    fun placeholderTranslationProvidesSpecificDiagnosticReason() {
        val result = DubbingTranslationPolicy.resolveText("This is a line", "正在翻译")
        assertNull(result.text)
        assertEquals("原始译文非空，但清理占位内容/非字幕文本后为空", result.reason)
    }
    @Test
    fun knownJapaneseLanguageKeepsKanjiOnlyCueFromBeingMisclassifiedAsChinese() {
        assertEquals("东京", DubbingTranslationPolicy.usableText("東京", "东京", "ja"))
    }

    @Test
    fun recognizesMandarinAndCantoneseBcp47Tags() {
        assertEquals("你好 OK", DubbingTranslationPolicy.usableText("你好 OK", null, "cmn-Hans-CN"))
        assertEquals("你好世界", DubbingTranslationPolicy.usableText("你好世界", null, "yue-Hant-HK"))
        assertNull(DubbingTranslationPolicy.usableText("東京", "東京", "ja-JP"))
    }

    @Test
    fun knownChineseLanguagePreservesMixedHanAndLatinCaption() {
        assertEquals("你好 OK", DubbingTranslationPolicy.usableText("你好 OK", null, "zh-Hans"))
    }

    @Test
    fun autoLanguageStillUsesPerCueDetectionForImportedBilingualSubtitle() {
        assertEquals("正在翻译", DubbingTranslationPolicy.usableText("正在翻译", null, "auto"))
    }

    @Test
    fun knownForeignLanguageStillRejectsAnEchoEvenWhenCueLooksLikeKanjiOnly() {
        assertNull(DubbingTranslationPolicy.usableText("東京", "東京", "ja"))
    }

}
