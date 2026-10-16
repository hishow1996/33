package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingTimelineTest {
    @Test
    fun findsActiveCueInsideNormalWindowAndReturnsMinusOneForGaps() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, 1_000, 2_000, "first"),
                SubLine(1, 3_000, 4_000, "second"),
            )
        )

        assertEquals(-1, timeline.lineAt(500))
        assertEquals(0, timeline.lineAt(1_500))
        assertEquals(-1, timeline.lineAt(2_500))
        assertEquals(1, timeline.lineAt(3_500))
        assertEquals(-1, timeline.lineAt(4_000))
    }

    @Test
    fun prefersNewestActiveCueButFallsBackToAnOlderOverlappingCue() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, 0, 5_000, "long cue"),
                SubLine(1, 4_000, 4_500, "short overlay"),
            )
        )

        assertEquals(1, timeline.lineAt(4_200))
        assertEquals(0, timeline.lineAt(4_700))
        assertEquals(-1, timeline.lineAt(5_000))
    }

    @Test
    fun simultaneousCueStartsPreferTheLastCueInTheList() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, 1_000, 3_000, "first"),
                SubLine(1, 1_000, 2_000, "second"),
            )
        )

        assertEquals(1, timeline.lineAt(1_500))
        assertEquals(0, timeline.lineAt(2_500))
    }

    @Test
    fun outOfOrderInputIsIndexedByTimeButReturnsOriginalListIndices() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, 3_000, 4_000, "later"),
                SubLine(1, 0, 1_000, "earlier"),
                SubLine(2, 1_500, 2_000, "middle"),
            )
        )

        assertEquals(1, timeline.lineAt(500))
        assertEquals(2, timeline.lineAt(1_700))
        assertEquals(-1, timeline.lineAt(2_500))
        assertEquals(0, timeline.lineAt(3_500))
    }

    @Test
    fun ignoresInvalidWindowsBlankCaptionsAndNegativePositions() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, -100, 500, "negative start"),
                SubLine(1, 1_000, 1_000, "zero length"),
                SubLine(2, 1_500, 2_000, "   "),
                SubLine(3, 2_000, 3_000, "valid"),
            )
        )

        assertEquals(-1, timeline.lineAt(-1))
        assertEquals(-1, timeline.lineAt(1_750))
        assertEquals(3, timeline.lineAt(2_500))
    }

    @Test
    fun emptyTimelineAndPositionBeforeFirstCueReturnNoActiveLine() {
        assertEquals(-1, DubbingTimeline(emptyList()).lineAt(1_000))
        assertEquals(-1, DubbingTimeline(listOf(SubLine(0, 1_000, 2_000, "first"))).lineAt(999))
    }

    @Test
    fun nestedOverlapsFallBackToTheNewestStillActiveCue() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, 0, 10_000, "long"),
                SubLine(1, 2_000, 7_000, "medium"),
                SubLine(2, 4_000, 5_000, "short"),
            )
        )

        assertEquals(2, timeline.lineAt(4_500))
        assertEquals(1, timeline.lineAt(6_000))
        assertEquals(0, timeline.lineAt(8_000))
        assertEquals(-1, timeline.lineAt(10_000))
    }

}
