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
    fun symbolOnlySourceCueRemainsAnEmptyTimelineMarkerWithoutTranslation() {
        val symbolOnly = cue(0, 0L, 1_000L, 1, "♪♪").copy(zh = null)
        val chunks = DubbingChunkPolicy.buildChunks(listOf(symbolOnly))
        assertEquals(1, chunks.size)
        assertEquals(listOf(0), chunks.single().lineIndices)
        assertEquals("", chunks.single().text)
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
    // ---- 实测语速校准 / 用户配音语速 ----

    private val eightChars = "一二三四五六七八" // 无标点：默认估算 8 × 0.22s = 1.76s

    private fun twoCues(firstWindowMs: Long) = listOf(
        cue(0, 0L, firstWindowMs, 1, eightChars),
        cue(1, firstWindowMs + 100L, firstWindowMs + 4_000L, 1, eightChars),
    )

    @Test
    fun defaultPaceKeepsTheOriginalEstimateAndMergeDecisions() {
        val text = "你好，我的朋友。"
        assertEquals(
            DubbingChunkPolicy.estimateSpeechMs(text),
            DubbingChunkPolicy.estimateSpeechMs(text, 1.0, 1f),
        )
        // 1.76s 的句子放进 2s 窗口：不需要合并。
        assertEquals(2, DubbingChunkPolicy.buildChunks(twoCues(2_000L)).size)
    }

    @Test
    fun slowerMeasuredVoiceMergesAdjacentCuesThatNoLongerFit() {
        // 实测这个音色比公式估算慢 40%：1.76s → 2.46s，放不进 2s 窗口，应与下一句合并。
        val chunks = DubbingChunkPolicy.buildChunks(twoCues(2_000L), paceScale = 1.4)
        assertEquals(1, chunks.size)
        assertEquals(listOf(0, 1), chunks.single().lineIndices)
    }

    @Test
    fun fasterMeasuredVoiceAvoidsNeedlessMerging() {
        // 默认估算 1.76s > 1.5s 窗口会合并；实测偏快（×0.7 → 1.23s）时一句就放得下。
        assertEquals(1, DubbingChunkPolicy.buildChunks(twoCues(1_500L)).size)
        assertEquals(2, DubbingChunkPolicy.buildChunks(twoCues(1_500L), paceScale = 0.7).size)
    }

    @Test
    fun fasterConfiguredSpeechRateShortensTheEstimate() {
        assertEquals(1, DubbingChunkPolicy.buildChunks(twoCues(1_500L), speechRate = 1f).size)
        // 配音语速 1.3：1.76s → 约 1.35s，放得进 1.5s 窗口。
        assertEquals(2, DubbingChunkPolicy.buildChunks(twoCues(1_500L), speechRate = 1.3f).size)
        assertTrue(
            DubbingChunkPolicy.estimateSpeechMs(eightChars, speechRate = 2f) <
                DubbingChunkPolicy.estimateSpeechMs(eightChars),
        )
    }

    @Test
    fun invalidCalibrationInputsFallBackToTheDefaultEstimate() {
        val base = DubbingChunkPolicy.estimateSpeechMs(eightChars)
        assertEquals(base, DubbingChunkPolicy.estimateSpeechMs(eightChars, Double.NaN, Float.NaN))
        assertEquals(base, DubbingChunkPolicy.estimateSpeechMs(eightChars, -3.0, 0f))
        // 超大系数被限制在上限，不会让每一句都被判成放不下。
        assertTrue(DubbingChunkPolicy.estimateSpeechMs(eightChars, 1_000.0) <= base * 2 + 1)
    }

}
