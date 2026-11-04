package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextPolicyTest {
    @Test
    fun acceptsTextAtTheConfiguredCodePointLimit() {
        TtsTextPolicy.validate("中".repeat(TtsTextPolicy.MAX_CODE_POINTS))
    }

    @Test
    fun rejectsOversizedTextBeforeCallingAProvider() {
        val error = runCatching {
            TtsTextPolicy.validate("中".repeat(TtsTextPolicy.MAX_CODE_POINTS + 1))
        }.exceptionOrNull()

        assertTrue(error is TtsInputLimitException)
        assertTrue(error?.message?.contains("拆分") == true)
    }

    @Test
    fun rejectsEmptyOrWhitespaceOnlyTextBeforeCallingAProvider() {
        listOf("", "   ", "\n\t").forEach { text ->
            val error = runCatching { TtsTextPolicy.validate(text) }.exceptionOrNull()
            assertTrue(error is TtsInputLimitException)
            assertTrue(error?.message?.contains("为空") == true)
        }
    }

    @Test
    fun countsSupplementaryCharactersAsOneCodePoint() {
        TtsTextPolicy.validate("😀".repeat(TtsTextPolicy.MAX_CODE_POINTS))
    }

    @Test
    fun punctuationSymbolAndEmojiOnlyTextHasNothingToSpeak() {
        listOf("……", "♪♪", "。。。", "😀🎵", "— —", "（）").forEach {
            assertFalse("'$it'", TtsTextPolicy.hasSpeakableContent(it))
        }
        listOf("好", "ok", "第3章", "……是的……").forEach {
            assertTrue("'$it'", TtsTextPolicy.hasSpeakableContent(it))
        }
    }

    @Test
    fun sanitizingRemovesControlFormatAndEmojiButKeepsSpeakableSymbols() {
        assertEquals("你好 世界", TtsTextPolicy.sanitizeForSpeech("你好\u200B 世界\u0007"))
        assertEquals("太棒了", TtsTextPolicy.sanitizeForSpeech("太棒了\uD83D\uDE00\uFE0F"))
        assertEquals("今天25℃，下雨。", TtsTextPolicy.sanitizeForSpeech("今天25℃，下雨。"))
        assertEquals("a b", TtsTextPolicy.sanitizeForSpeech("a  \u3000 b"))
        assertEquals("第一行\n第二行", TtsTextPolicy.sanitizeForSpeech("第一行\r\n第二行"))
        // 落单的代理项会让 XML/服务端出错，必须被去掉
        assertEquals("好", TtsTextPolicy.sanitizeForSpeech("好\uD83D"))
    }

    @Test
    fun piecesSplitAtPunctuationAndShortTailsMergeBack() {
        val pieces = TtsTextPolicy.splitIntoPieces("这是第一段比较长的话，这是第二段也比较长的话。好")
        assertEquals(listOf("这是第一段比较长的话，", "这是第二段也比较长的话。好"), pieces)
        assertEquals(listOf("没有标点的一句话"), TtsTextPolicy.splitIntoPieces("没有标点的一句话"))
        assertTrue(TtsTextPolicy.splitIntoPieces("").isEmpty())
    }
}
