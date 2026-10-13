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
}
