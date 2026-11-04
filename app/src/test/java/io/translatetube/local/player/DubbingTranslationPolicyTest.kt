package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun unchangedTermsAndNamesAreSpokenInsteadOfFallingBackToTheSourceAudio() {
        assertEquals("H-1B", DubbingTranslationPolicy.usableText("H-1B", "H-1B", "en"))
        assertEquals("NASA", DubbingTranslationPolicy.usableText("NASA", "NASA", "en"))
        assertEquals("Elon Musk", DubbingTranslationPolicy.usableText("Elon Musk", "Elon Musk", "en"))
    }

    @Test
    fun marksUnusableTranslationsAsExplicitOriginalAudioFallbackBeforeDubbing() {
        val sourceLines = listOf(
            io.translatetube.local.subs.SubLine(0, 0, 900, "Hello world", "你好世界"),
            io.translatetube.local.subs.SubLine(1, 900, 1800, "Hello there", "Hello there"),
            io.translatetube.local.subs.SubLine(2, 1800, 2700, "Where are you?", null),
            io.translatetube.local.subs.SubLine(3, 2700, 3600, "How are you?", "こんにちは"),
            io.translatetube.local.subs.SubLine(4, 3600, 4500, "See you soon", "。"),
            io.translatetube.local.subs.SubLine(
                5, 4500, 5400, "Already on original audio", "stale text",
                translationFallbackOriginalAudio = true,
            ),
        )

        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(sourceLines, "en")

        assertEquals(listOf(1, 2, 3, 4, 5), prepared.originalAudioFallbackIndices)
        assertEquals("你好世界", prepared.lines[0].zh)
        assertFalse(prepared.lines[0].translationFallbackOriginalAudio)
        prepared.originalAudioFallbackIndices.forEach { index ->
            assertTrue(prepared.lines[index].translationFallbackOriginalAudio)
            assertNull(prepared.lines[index].zh)
        }
        assertEquals("译文与原文相同，命中原文回显过滤规则", prepared.fallbackReasons[1])
        assertEquals("翻译结果为空或只有空白字符", prepared.fallbackReasons[2])
        assertEquals("译文未达到中文目标语言", prepared.fallbackReasons[3])
        assertEquals("已由翻译阶段明确采用原声兜底", prepared.fallbackReasons[5])
    }

    @Test
    fun doesNotMarkBlankSourceCuesAsTranslationFailures() {
        val source = io.translatetube.local.subs.SubLine(0, 0, 500, "", null)
        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(listOf(source), "en")

        assertTrue(prepared.originalAudioFallbackIndices.isEmpty())
        assertFalse(prepared.lines.single().translationFallbackOriginalAudio)
    }
}
