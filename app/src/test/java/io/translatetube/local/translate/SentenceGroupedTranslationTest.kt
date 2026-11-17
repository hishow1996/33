package io.translatetube.local.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceGroupedTranslationTest {
    private fun groups(lines: List<String>, indices: List<Int> = lines.indices.toList()) =
        SentenceGroupingPolicy.plan(lines, indices).map { it.start to it.endExclusive }

    @Test
    fun joinsFragmentThatContinuesInLowercase() {
        val lines = listOf("I went to the bank", "of the river to fish.", "Then we left.")
        assertEquals(listOf(0 to 2, 2 to 3), groups(lines))
    }

    @Test
    fun doesNotJoinAcrossNonConsecutiveRows() {
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("I went to the bank", "of the river"), listOf(0, 2)))
    }

    @Test
    fun commaJoinsEvenBeforeACapital_butUnpunctuatedCapitalStartsANewSentence() {
        assertEquals(listOf(0 to 2), groups(listOf("Well,", "Sarah came.")))
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("I told him", "Sarah came later.")))
    }

    @Test
    fun trailingQuoteAfterAFullStopStillEndsTheSentence() {
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("He said \"wait.\"", "then left")))
    }

    @Test
    fun unspacedScriptsAndDialogueMarkersAreNeverJoined() {
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("我去了", "银行")))
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("Hello there", "- general kenobi")))
        assertEquals(listOf(0 to 1, 1 to 2), groups(listOf("[Music]", "and then")))
    }

    @Test
    fun groupsAreCappedByLineCountAndLength() {
        assertEquals(
            listOf(0 to 3, 3 to 4),
            groups(listOf("a b", "c d", "e f", "g h")),
        )
        assertEquals(
            listOf(0 to 1, 1 to 2),
            groups(listOf("x".repeat(150), "y".repeat(100))),
        )
    }

    @Test
    fun splitPrefersPunctuationNearTheProportionalPoint() {
        val pieces = SentenceGroupingPolicy.split(
            "我坐在河岸上，整天都在钓鱼",
            listOf("I sat on the bank", "of the river all day fishing"),
        )
        assertEquals(listOf("我坐在河岸上", "整天都在钓鱼"), pieces)
    }

    @Test
    fun splitNeverCutsInsideALatinWord() {
        val pieces = SentenceGroupingPolicy.split(
            "我们使用Kubernetes部署",
            listOf("we use", "Kubernetes deploy"),
        ) ?: throw AssertionError("Expected a split")
        assertEquals(listOf("我们使用", "Kubernetes部署"), pieces)
    }

    @Test
    fun splitsIntoThreePieces() {
        val pieces = SentenceGroupingPolicy.split(
            "第一部分，第二部分，第三部分",
            listOf("aaaa", "bbbb", "cccc"),
        )
        assertEquals(listOf("第一部分", "第二部分", "第三部分"), pieces)
    }

    @Test
    fun splitGivesUpWhenTheTranslationIsTooShort() {
        assertNull(SentenceGroupingPolicy.split("好", listOf("a b", "c d")))
        assertNull(SentenceGroupingPolicy.split("   ", listOf("a b", "c d")))
        assertEquals(listOf("你好"), SentenceGroupingPolicy.split(" 你好 ", listOf("hello")))
    }

    @Test
    fun mergesTranslatesOnceAndSplitsBack() = runBlocking {
        val calls = ArrayList<List<String>>()
        val result = SentenceGroupedTranslation.translate(
            lines = listOf("I sat on the bank", "of the river all day fishing"),
            rowIndices = listOf(0, 1),
        ) { batch ->
            calls += batch
            listOf("我坐在河岸上，整天都在钓鱼")
        }
        assertEquals(listOf("我坐在河岸上", "整天都在钓鱼"), result)
        assertEquals(listOf(listOf("I sat on the bank of the river all day fishing")), listOf(calls.flatten()))
        assertEquals(1, calls.size)
    }

    @Test
    fun batchWithoutFragmentsIsTranslatedExactlyAsBefore() = runBlocking {
        val calls = ArrayList<List<String>>()
        val result = SentenceGroupedTranslation.translate(listOf("Hello.", "World."), listOf(4, 5)) { batch ->
            calls += batch
            batch.map { "译:$it" }
        }
        assertEquals(listOf("译:Hello.", "译:World."), result)
        assertEquals(listOf(listOf("Hello.", "World.")), calls)
    }

    @Test
    fun mixedBatchKeepsSingleRowsAndSplitsMergedOnes() = runBlocking {
        val result = SentenceGroupedTranslation.translate(
            lines = listOf("i went", "to the park", "Done."),
            rowIndices = listOf(0, 1, 2),
        ) { batch ->
            assertEquals(listOf("i went to the park", "Done."), batch)
            listOf("我们去公园玩", "完成。")
        }
        assertEquals(listOf("我们", "去公园玩", "完成。"), result)
    }

    @Test
    fun unsplittableTranslationFallsBackToLineByLineForThatGroupOnly() = runBlocking {
        val calls = ArrayList<List<String>>()
        var stats: SentenceGroupedTranslation.Stats? = null
        val result = SentenceGroupedTranslation.translate(
            lines = listOf("i went", "to the park"),
            rowIndices = listOf(0, 1),
            report = { stats = it },
        ) { batch ->
            calls += batch
            if (calls.size == 1) listOf("好") else batch.map { "译:$it" }
        }
        assertEquals(listOf("译:i went", "译:to the park"), result)
        assertEquals(2, calls.size)
        assertEquals(2, stats?.fallbackLines)
        assertEquals(1, stats?.mergedGroups)
    }

    @Test
    fun wrongResultCountFallsBackToTheOriginalLines() = runBlocking {
        val calls = ArrayList<List<String>>()
        val result = SentenceGroupedTranslation.translate(
            lines = listOf("i went", "to the park"),
            rowIndices = listOf(0, 1),
        ) { batch ->
            calls += batch
            if (calls.size == 1) emptyList() else batch.map { "译:$it" }
        }
        assertEquals(listOf("译:i went", "译:to the park"), result)
        assertTrue(calls.last() == listOf("i went", "to the park"))
    }

    @Test
    fun blankLinesPassThroughUntouched() = runBlocking {
        val result = SentenceGroupedTranslation.translate(listOf("", "hello"), listOf(0, 1)) { batch ->
            batch.map { if (it.isBlank()) "" else "译:$it" }
        }
        assertEquals(listOf("", "译:hello"), result)
    }
}
