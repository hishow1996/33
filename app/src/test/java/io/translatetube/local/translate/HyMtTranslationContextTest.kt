package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtTranslationContextTest {
    @Test
    fun appendsCurrentCueAndKeepsOnlyTheFiveMostRecentPairs() {
        val initial = listOf("a", "b", "c", "d", "e") to listOf("甲", "乙", "丙", "丁", "戊")

        val updated = appendHyMtTranslationContext(initial, "f", "己")

        assertEquals(listOf("b", "c", "d", "e", "f"), updated?.first)
        assertEquals(listOf("乙", "丙", "丁", "戊", "己"), updated?.second)
    }

    @Test
    fun contextCharacterBudgetDropsOldestPairsButKeepsTheLatestOne() {
        val a = "a".repeat(200)
        val b = "b".repeat(200)
        val c = "c".repeat(200)
        val d = "d".repeat(200)
        val trimmed = HyMtPromptContext.trimContext(
            listOf(a, b, c, d) to listOf("译", "译", "译", "译"),
        )
        // 4 对共 804 字符，超过 700 的预算，只丢最旧的一对就够了。
        assertEquals(listOf(b, c, d), trimmed?.first)
        val single = HyMtPromptContext.trimContext(listOf("y".repeat(2_000)) to listOf("译"))
        assertEquals(1, single?.first?.size)
        assertNull(HyMtPromptContext.trimContext(null))
        assertNull(HyMtPromptContext.trimContext(listOf(" ") to listOf("译")))
    }

    @Test
    fun followingCuesUseTheRestOfTheBatchThenTheLookahead() {
        val lines = listOf("one", "two", "", "three")
        assertEquals(listOf("two", "three"), HyMtPromptContext.followingCues(lines, 0, listOf("after")))
        assertEquals(listOf("three", "after"), HyMtPromptContext.followingCues(lines, 2, listOf(" ", "after", "later")))
        assertEquals(listOf("after", "later"), HyMtPromptContext.followingCues(lines, 3, listOf("after", "later", "x")))
        assertEquals(emptyList<String>(), HyMtPromptContext.followingCues(lines, 3, emptyList()))
    }

    @Test
    fun promptCarriesTitleAndFollowingCuesOnlyWhenProvided() {
        val plain = HyMtPromptBuilder.build("Hello", "en", null)
        assertFalse(plain.contains("视频标题"))
        assertFalse(plain.contains("紧跟在当前字幕后面"))
        assertTrue(plain.endsWith("Hello"))

        val rich = HyMtPromptBuilder.build(
            "I sat on the bank", "en",
            listOf("Earlier") to listOf("之前"),
            following = listOf("of the river"),
            videoTitle = "Fishing trip",
        )
        assertTrue(rich.contains("视频标题（仅供理解主题和术语，不要翻译）：Fishing trip"))
        assertTrue(rich.contains("of the river"))
        assertTrue(rich.contains("原文：Earlier\n译文：之前"))
        assertTrue(rich.endsWith("I sat on the bank"))
    }

    @Test
    fun ignoresEmptyCuesAndKeepsSourceAndTranslationPairsAligned() {
        val initial = listOf("previous", "orphan") to listOf("此前译文")

        assertEquals(initial, appendHyMtTranslationContext(initial, " ", "ignored"))
        val updated = appendHyMtTranslationContext(initial, "current", "当前译文")
        assertEquals(listOf("previous", "current"), updated?.first)
        assertEquals(listOf("此前译文", "当前译文"), updated?.second)
    }
}
