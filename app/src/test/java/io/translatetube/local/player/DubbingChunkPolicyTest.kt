package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
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

    @Test
    fun bracketedSoundCaptionStaysInTimelineButIsNotPartOfSpeechChunks() {
        val cues = listOf(
            cue(0, 0L, 1_000L, 1, "这是第一句很长很长很长很长很长的台词"),
            cue(1, 1_100L, 2_000L, 1, "[music]").copy(zh = "[音乐]"),
            cue(2, 2_100L, 10_000L, 1, "这是第二句很长很长很长很长很长的台词"),
        )
        val chunks = DubbingChunkPolicy.buildChunks(cues) { _, line ->
            !io.translatetube.local.translate.SubtitleSoundDescriptionPolicy.isSoundDescription(line.text)
        }
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), chunks.map { it.lineIndices })
        assertEquals("", chunks[1].text)
        assertTrue(chunks[0].text.isNotBlank())
        assertTrue(chunks[2].text.isNotBlank())
    }

    @Test
    fun playableCueWithoutChineseTranslationIsRejectedInsteadOfUsingSourceText() {
        val sourceOnly = cue(0, 0L, 1_000L, 1).copy(zh = null)

        val error = runCatching {
            DubbingChunkPolicy.buildChunks(listOf(sourceOnly))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("中文译文"))
    }
}
