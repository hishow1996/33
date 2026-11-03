package io.translatetube.local.player

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
    fun resumesFollowingOnNextCueRatherThanCurrentManuallyBrowsedCue() {
        assertTrue(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(true, 12, 12))
        assertFalse(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(true, 12, 13))
        assertFalse(SubtitleAutoScrollPolicy.shouldSkipImmediateResume(false, 12, 12))
    }

    @Test
    fun invalidViewportDoesNotProduceUnnecessaryScroll() {
        val decision = SubtitleAutoScrollPolicy.decide(100, 100, 200, 50)
        assertFalse(decision.shouldScroll)
        assertFalse(decision.isLongCaption)
    }
}
