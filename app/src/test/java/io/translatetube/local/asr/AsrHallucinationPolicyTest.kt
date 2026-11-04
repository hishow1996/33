package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrHallucinationPolicyTest {
    private fun row(index: Int, text: String) =
        Triple(index * 1_000L, index * 1_000L + 900L, text)

    @Test
    fun creditLinesAreRecognisedButRealSpeechIsNot() {
        assertTrue(AsrHallucinationPolicy.isCreditLine("Subtitles by the Amara.org community"))
        assertTrue(AsrHallucinationPolicy.isCreditLine("字幕由 Amara.org 社群提供"))
        assertTrue(AsrHallucinationPolicy.isCreditLine("请不吝点赞 订阅 转发 打赏支持明镜与点点栏目"))
        assertFalse(AsrHallucinationPolicy.isCreditLine("Thank you."))
        // 英文署名只认行首
        assertFalse(AsrHallucinationPolicy.isCreditLine("I wrote the subtitles by hand last night."))
        assertFalse(AsrHallucinationPolicy.isCreditLine(""))
    }

    @Test
    fun aRepeatedSentenceLoopIsCollapsedToOneCopy() {
        assertEquals(
            "I'm going to go home.",
            AsrHallucinationPolicy.collapseRepetition("I'm going to go home. ".repeat(10)),
        )
        assertEquals(
            "Thank you.",
            AsrHallucinationPolicy.collapseRepetition("Thank you. ".repeat(6).trim()),
        )
    }

    @Test
    fun aTruncatedTailOfTheLoopIsDroppedToo() {
        val looped = "I'm going to go home. I'm going to go home. I'm going to go home. I'm going to"
        assertEquals("I'm going to go home.", AsrHallucinationPolicy.collapseRepetition(looped))
    }

    @Test
    fun repeatedChineseSentencesAreCollapsed() {
        assertEquals(
            "我们去公园玩吧。",
            AsrHallucinationPolicy.collapseRepetition("我们去公园玩吧。".repeat(5)),
        )
    }

    @Test
    fun repeatedSingleWordsKeepTwoCopiesAndOnlyAfterFiveRepeats() {
        assertEquals("no no", AsrHallucinationPolicy.collapseRepetition("no no no no no no"))
        // 4 次以内是正常的强调/口吃
        assertEquals("no no no no", AsrHallucinationPolicy.collapseRepetition("no no no no"))
        assertEquals(
            "I said no no no no and left",
            AsrHallucinationPolicy.collapseRepetition("I said no no no no and left"),
        )
    }

    @Test
    fun ordinaryTextAndLaughterAreLeftAlone() {
        val sentence = "I think we should go, and then we should go home."
        assertEquals(sentence, AsrHallucinationPolicy.collapseRepetition(sentence))
        val laughter = "哈哈哈哈哈哈哈哈哈哈哈哈"
        assertEquals(laughter, AsrHallucinationPolicy.collapseRepetition(laughter))
        assertEquals("Yeah.", AsrHallucinationPolicy.collapseRepetition("Yeah."))
    }

    @Test
    fun consecutiveIdenticalLongRowsAreMergedIntoTheFirst() {
        val rows = listOf(
            row(0, "I'm going to go home."),
            row(1, "I'm going to go home."),
            row(2, "I'm going to go home."),
        )
        assertEquals(listOf(rows[0]), AsrHallucinationPolicy.cleanRows(rows))
        // 只重复两次是正常的
        assertEquals(rows.take(2), AsrHallucinationPolicy.cleanRows(rows.take(2)))
    }

    @Test
    fun shortRowsNeedMoreRepeatsBeforeTheyAreTreatedAsALoop() {
        val three = List(3) { row(it, "Yeah.") }
        assertEquals(three, AsrHallucinationPolicy.cleanRows(three))
        val six = List(6) { row(it, "Yeah.") }
        assertEquals(listOf(six[0]), AsrHallucinationPolicy.cleanRows(six))
    }

    @Test
    fun creditRowsAreDroppedAndLoopRowsShrinkWithoutChangingTheirTimes() {
        val rows = listOf(
            row(0, "Hello everyone and welcome."),
            row(1, "Subtitles by the Amara.org community"),
            Triple(2_000L, 6_000L, "Thank you. ".repeat(6).trim()),
        )
        assertEquals(
            listOf(rows[0], Triple(2_000L, 6_000L, "Thank you.")),
            AsrHallucinationPolicy.cleanRows(rows),
        )
    }
}
