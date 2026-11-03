package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoScrollPolicyTest {
    @Test
    fun keepsOrdinaryCaptionInsideTheReadingBandWithoutScrolling() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, 300, 80)
        assertFalse(decision.shouldScroll)
        assertFalse(decision.isLongCaption)
        assertEquals(-440, decision.scrollOffset)
    }

    @Test
    fun scrollsOrdinaryCaptionWhenItLeavesTheReadingBand() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, 100, 80)
        assertTrue(decision.shouldScroll)
        assertFalse(decision.isLongCaption)
    }

    @Test
    fun movesAnUnmeasuredTargetIntoTheReadingArea() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, null, null)
        assertTrue(decision.shouldScroll)
        assertEquals(-440, decision.scrollOffset)
    }

    @Test
    fun doesNotPullPartiallyVisibleLongCaptionAwayFromReader() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, 80, 1_600)
        assertFalse(decision.shouldScroll)
        assertTrue(decision.isLongCaption)
        assertEquals(-80, decision.scrollOffset)
    }

    @Test
    fun bringsLongCaptionBackWhenItHasLeftViewportAbove() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, -1_700, 1_600)
        assertTrue(decision.shouldScroll)
        assertTrue(decision.isLongCaption)
        assertEquals(-80, decision.scrollOffset)
    }

    @Test
    fun bringsLongCaptionIntoViewWhenItHasNotReachedViewportYet() {
        val decision = SubtitleAutoScrollPolicy.decide(0, 1_000, 1_200, 1_600)
        assertTrue(decision.shouldScroll)
        assertTrue(decision.isLongCaption)
    }

    @Test
    fun skipsTheFirstCueAfterManualBrowseButFollowsTheNextCue() {
        assertTrue(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(true, 12, 12))
        assertFalse(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(true, 12, 13))
        assertFalse(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(false, 12, 12))
    }

    @Test
    fun usesShorterCoalescingForShortCaptions() {
        assertEquals(0L, SubtitleAutoScrollPolicy.coalescingDelayMs(0L, 200L))
        assertEquals(15L, SubtitleAutoScrollPolicy.coalescingDelayMs(0L, 500L))
        assertEquals(30L, SubtitleAutoScrollPolicy.coalescingDelayMs(0L, 1_200L))
        assertEquals(45L, SubtitleAutoScrollPolicy.coalescingDelayMs(0L, 2_500L))
    }

    @Test
    fun jumpsImmediatelyAcrossLargeForwardAndReverseGaps() {
        assertFalse(SubtitleAutoScrollPolicy.shouldJumpImmediately(10, 11))
        assertTrue(SubtitleAutoScrollPolicy.shouldJumpImmediately(10, 14))
        assertTrue(SubtitleAutoScrollPolicy.shouldJumpImmediately(14, 10))
        assertTrue(SubtitleAutoScrollPolicy.shouldJumpImmediately(Int.MIN_VALUE, 0))
    }

    @Test
    fun aTimelineSeekForcesImmediatePositioningEvenForOneCue() {
        assertTrue(SubtitleAutoScrollPolicy.shouldJumpImmediately(10, 11, forceImmediate = true))
    }

    @Test
    fun invalidViewportDoesNotProduceUnnecessaryScroll() {
        val decision = SubtitleAutoScrollPolicy.decide(100, 100, 200, 50)
        assertFalse(decision.shouldScroll)
        assertFalse(decision.isLongCaption)
    }
    @Test
    fun shortCuesSnapInsteadOfStartingAnInterruptedScrollAnimation() {
        assertFalse(SubtitleAutoScrollPolicy.shouldAnimateScroll(10, 11, 500L))
        assertTrue(SubtitleAutoScrollPolicy.shouldAnimateScroll(10, 11, 1_200L))
        assertFalse(SubtitleAutoScrollPolicy.shouldAnimateScroll(10, 14, 2_000L))
        assertFalse(SubtitleAutoScrollPolicy.shouldAnimateScroll(10, 11, 2_000L, forceImmediate = true))
    }

    @Test
    fun holdsPreviousHighlightOnlyForBriefCueTail() {
        val lines = listOf(SubLine(0, 1_000L, 2_000L, "first"))
        assertEquals(0, SubtitleHighlightPolicy.holdPreviousCueBriefly(lines, 0, 2_100L))
        assertEquals(0, SubtitleHighlightPolicy.holdPreviousCueBriefly(lines, 0, 2_160L))
        assertEquals(-1, SubtitleHighlightPolicy.holdPreviousCueBriefly(lines, 0, 2_161L))
        assertEquals(-1, SubtitleHighlightPolicy.holdPreviousCueBriefly(lines, -1, 2_100L))
    }

    @Test
    fun invalidPreviousCueCannotRemainHighlighted() {
        val lines = listOf(SubLine(0, 1_000L, 2_000L, "first"))
        assertEquals(-1, SubtitleHighlightPolicy.holdPreviousCueBriefly(lines, 5, 2_050L))
    }

}
