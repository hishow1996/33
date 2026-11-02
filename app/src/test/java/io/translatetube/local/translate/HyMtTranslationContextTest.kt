package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class HyMtTranslationContextTest {
    @Test
    fun appendsCurrentCueAndKeepsOnlyTheThreeMostRecentPairs() {
        val initial = listOf("a", "b", "c") to listOf("甲", "乙", "丙")

        val updated = appendHyMtTranslationContext(initial, "d", "丁")

        assertEquals(listOf("b", "c", "d"), updated?.first)
        assertEquals(listOf("乙", "丙", "丁"), updated?.second)
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
