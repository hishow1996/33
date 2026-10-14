package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatedSubtitleImportPolicyTest {
    @Test
    fun prefersChineseTranslationAndPreservesUploadedTimestamps() {
        val result = TranslatedSubtitleImportPolicy.prepare(
            listOf(
                SubLine(8, 2_000, 3_000, "English source\n中文译文"),
                SubLine(4, 0, 1_200, "Original line\n对应译文"),
            )
        )

        assertEquals(2, result.size)
        assertEquals(0, result[0].startMs)
        assertEquals(1_200, result[0].endMs)
        assertEquals("Original line", result[0].text)
        assertEquals("对应译文", result[0].zh)
        assertEquals("English source", result[1].text)
        assertEquals("中文译文", result[1].zh)
    }

    @Test
    fun discardsBlankInvalidAndProgressPlaceholderRows() {
        val result = TranslatedSubtitleImportPolicy.prepare(
            listOf(
                SubLine(0, 0, 1_000, "   "),
                SubLine(1, 2_000, 2_000, "无有效时长"),
                SubLine(2, 3_000, 4_000, "正在翻译"),
                SubLine(3, 4_000, 5_000, "Source\n有效译文"),
            )
        )

        assertEquals(1, result.size)
        assertEquals(0, result.single().index)
        assertEquals("有效译文", result.single().zh)
        assertTrue(result.single().startMs == 4_000L)
    }
    @Test
    fun rejectsEnglishOnlySourceSubtitlesInsteadOfTreatingThemAsChineseTranslations() {
        val result = TranslatedSubtitleImportPolicy.prepare(
            listOf(
                SubLine(0, 0, 1_000, "Hello, how are you?"),
                SubLine(1, 1_000, 2_000, "This row has no translated Chinese text."),
                SubLine(2, 2_000, 3_000, "Hello\\n你好"),
            )
        )
        assertEquals(1, result.size)
        assertEquals("你好", result.single().zh)
        assertEquals("Hello", result.single().text)
    }

}
