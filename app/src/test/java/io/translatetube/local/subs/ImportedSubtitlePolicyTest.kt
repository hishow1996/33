package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedSubtitlePolicyTest {
    @Test
    fun preservesRepeatedChineseWordingInAnImportedTranslation() {
        val cue = ImportedSubtitlePolicy.splitBilingualCue("Hello there\\n哈哈哈哈")
        assertEquals("Hello there", cue.originalText)
        assertEquals("哈哈哈哈", cue.translationText)
    }

    @Test
    fun preservesTextThatLooksLikeAnInternalProgressLabel() {
        val cue = ImportedSubtitlePolicy.splitBilingualCue("We are speaking\\n正在翻译")
        assertEquals("We are speaking", cue.originalText)
        assertEquals("正在翻译", cue.translationText)
    }

    @Test
    fun removesOnlyTheSelectedTranslationRowWhenRowsHaveEqualText() {
        val cue = ImportedSubtitlePolicy.splitBilingualCue("OK\\nOK")
        assertEquals("OK", cue.originalText)
        assertEquals("OK", cue.translationText)
    }

    @Test
    fun fallsBackToTheRawCueWhenItHasNoNonBlankLines() {
        val cue = ImportedSubtitlePolicy.splitBilingualCue("  \\n ")
        assertEquals("", cue.originalText)
        assertEquals("", cue.translationText)
    }
}
