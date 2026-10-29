package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingChunkPolicyTest {
    private fun cue(
        index: Int,
        startMs: Long,
        endMs: Long,
        speakerId: Int,
        text: String = "这是一个非常非常非常非常非常非常非常非常长的句子",
    ) = SubLine(
        index = index,
        startMs = startMs,
        endMs = endMs,
        text = text,
        zh = text,
        speakerId = speakerId,
    )

    private fun adjacentCues(speakerA: Int, speakerB: Int) = listOf(
        cue(0, 0L, 1_000L, speakerA),
        cue(1, 1_100L, 10_000L, speakerB),
    )

    @Test
    fun differentAssignedVoicesAreNeverMergedIntoOnePcmBlock() {
        val chunks = DubbingChunkPolicy.buildChunks(adjacentCues(1, 2))

        assertEquals(listOf(listOf(0), listOf(1)), chunks.map { it.lineIndices })
        assertTrue(chunks.none { it.lineIndices.size > 1 })
    }

    @Test
    fun adjacentCuesWithTheSameVoiceCanStillMergeWhenTimingRequiresIt() {
        val chunks = DubbingChunkPolicy.buildChunks(adjacentCues(1, 1))

        assertEquals(1, chunks.size)
        assertEquals(listOf(0, 1), chunks.single().lineIndices)
        assertTrue(chunks.single().text.contains("句子"))
    }

    @Test
    fun unspecifiedSpeakerIdsKeepTheExistingSingleVoiceMergeBehavior() {
        val chunks = DubbingChunkPolicy.buildChunks(adjacentCues(-1, -1))

        assertEquals(1, chunks.size)
        assertEquals(listOf(0, 1), chunks.single().lineIndices)
    }
}

class DubbingChunkContinuationTest {
    private fun line(
        index: Int,
        startMs: Long,
        endMs: Long,
        source: String,
        zh: String?,
        speakerId: Int = -1,
    ) = SubLine(index, startMs, endMs, source, zh, speakerId)

    @Test
    fun aSentenceSplitAcrossCuesIsDubbedAsOnePiece() {
        val lines = listOf(
            line(0, 0, 1_500, "So we went to the", "我们去了"),
            line(1, 1_500, 3_000, "store yesterday.", "昨天的商店。"),
            line(2, 3_400, 5_000, "It was closed.", "它关门了。"),
        )
        val chunks = DubbingChunkPolicy.buildChunks(lines)

        assertEquals(listOf(listOf(0, 1), listOf(2)), chunks.map { it.lineIndices })
        // The first fragment is short, so no comma is inserted into the phrase.
        assertEquals("我们去了昨天的商店。", chunks[0].text)
        assertEquals(0L, chunks[0].windowStartMs)
        assertEquals(3_000L, chunks[0].windowEndMs)
    }

    @Test
    fun aFinishedSentenceIsNeverJoinedToTheNextOne() {
        val lines = listOf(
            line(0, 0, 1_500, "Hello there.", "你好。"),
            line(1, 1_600, 3_000, "How are you?", "你好吗？"),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(lines).map { it.lineIndices })
    }

    @Test
    fun aRealPauseBetweenCuesEndsTheSentenceEvenWithoutPunctuation() {
        val raw = listOf(
            line(0, 0, 1_000, "and then we", "然后我们"),
            line(1, 2_500, 4_000, "left the city.", "离开了城市。"),
        )
        // After gap borrowing the cues touch, which hides the pause: the raw times decide.
        val borrowed = DubbingTiming.extendCueWindows(raw)
        assertTrue(borrowed[1].startMs - borrowed[0].endMs <= 200L)

        val chunks = DubbingChunkPolicy.buildChunks(borrowed, pauseSource = raw)
        assertEquals(listOf(listOf(0), listOf(1)), chunks.map { it.lineIndices })

        val closeRaw = listOf(
            line(0, 0, 1_000, "and then we", "然后我们"),
            line(1, 1_300, 4_000, "left the city.", "离开了城市。"),
        )
        val joined = DubbingChunkPolicy.buildChunks(DubbingTiming.extendCueWindows(closeRaw), closeRaw)
        assertEquals(listOf(listOf(0, 1)), joined.map { it.lineIndices })
    }

    @Test
    fun aChunkNeverHasMoreThanThreeLines() {
        val lines = (0 until 4).map { i ->
            line(i, i * 1_000L, (i + 1) * 1_000L, "word$i", "词${i}词")
        }
        val chunks = DubbingChunkPolicy.buildChunks(lines)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), chunks.map { it.lineIndices })
    }

    @Test
    fun spanAndLengthLimitsStopMerging() {
        val long = listOf(
            line(0, 0, 6_000, "first part of a long", "第一部分"),
            line(1, 6_000, 11_000, "sentence that keeps going.", "一直说下去的句子。"),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(long).map { it.lineIndices })

        val manyChars = "字".repeat(40)
        val wordy = listOf(
            line(0, 0, 3_000, "first part of a long", manyChars),
            line(1, 3_000, 6_000, "sentence that keeps going.", manyChars),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(wordy).map { it.lineIndices })
    }

    @Test
    fun differentSpeakersAndMissingTranslationsAreNotJoined() {
        val speakers = listOf(
            line(0, 0, 1_000, "we went to the", "我们去了", speakerId = 1),
            line(1, 1_000, 2_000, "store.", "商店。", speakerId = 2),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(speakers).map { it.lineIndices })

        val missing = listOf(
            line(0, 0, 1_000, "we went to the", "我们去了"),
            line(1, 1_000, 2_000, "store.", null),
        )
        assertTrue(DubbingChunkPolicy.buildChunks(missing).none { it.lineIndices.size > 1 })
    }

    @Test
    fun autoCaptionsWithoutPunctuationUseTranslationAndCasing() {
        val fragments = listOf(
            line(0, 0, 1_500, "so today we are going to", "所以今天我们要"),
            line(1, 1_500, 3_000, "talk about the history", "谈谈历史"),
            line(2, 3_000, 4_500, "of the roman empire", "罗马帝国的"),
        )
        assertEquals(listOf(listOf(0, 1, 2)), DubbingChunkPolicy.buildChunks(fragments).map { it.lineIndices })

        // A translation that already ends its sentence is a boundary.
        val finished = listOf(
            line(0, 0, 3_000, "so today we are going", "所以今天我们开始。"),
            line(1, 3_000, 6_000, "talk about the history", "谈谈历史"),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(finished).map { it.lineIndices })

        // An uppercase start in the next cue signals a new sentence.
        val capital = listOf(
            line(0, 0, 1_500, "so today we are going", "所以今天我们"),
            line(1, 1_500, 3_000, "Next we look at Rome", "接着看罗马"),
        )
        assertEquals(listOf(listOf(0), listOf(1)), DubbingChunkPolicy.buildChunks(capital).map { it.lineIndices })
    }

    @Test
    fun punctuationHelpersBehave() {
        assertTrue(DubbingChunkPolicy.endsSentence("Really?\""))
        assertTrue(DubbingChunkPolicy.endsSentence("结束了。”"))
        assertFalse(DubbingChunkPolicy.endsSentence("and then,"))
        assertFalse(DubbingChunkPolicy.endsSentence("没有标点"))
        assertEquals("你好，世界。", DubbingChunkPolicy.joinContinuation("你好，", "世界。"))
        assertEquals("这是一个比较长的片段，后面继续", DubbingChunkPolicy.joinContinuation("这是一个比较长的片段", "后面继续"))
        assertEquals("我们去了商店", DubbingChunkPolicy.joinContinuation("我们去了", "商店"))
    }

    @Test
    fun aFullStopInsideAContinuedSentenceIsDropped() {
        val lines = listOf(
            line(0, 0, 1_500, "so we went to the", "我们去了。"),
            line(1, 1_500, 3_000, "store yesterday.", "昨天的商店。"),
        )
        assertEquals("我们去了昨天的商店。", DubbingChunkPolicy.buildChunks(lines).single().text)
        assertEquals("你好！", DubbingChunkPolicy.dropClosingFullStop("你好！"))
        assertEquals("你好", DubbingChunkPolicy.dropClosingFullStop("你好。"))
    }
}
