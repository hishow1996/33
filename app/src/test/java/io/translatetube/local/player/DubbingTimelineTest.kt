package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun cueLookupWithOnlyInvalidRowsNeverReturnsAnIndex() {
        val timeline = DubbingTimeline(
            listOf(
                SubLine(0, -10L, 100L, "negative"),
                SubLine(1, 100L, 100L, "empty"),
                SubLine(2, 300L, 200L, "reversed"),
                SubLine(3, 400L, 500L, " "),
            )
        )
        assertEquals(-1, timeline.lineAt(450L))
        assertEquals(-1, timeline.lineAt(Long.MAX_VALUE))
    }

    @Test
    fun singleCueUsesHalfOpenWindowAndFastOutsideBounds() {
        val timeline = DubbingTimeline(listOf(SubLine(7, 1_000L, 2_000L, "only cue")))
        assertEquals(-1, timeline.lineAt(999L))
        assertEquals(7, timeline.lineAt(1_000L))
        assertEquals(7, timeline.lineAt(1_999L))
        assertEquals(-1, timeline.lineAt(2_000L))
        assertEquals(-1, timeline.lineAt(Long.MAX_VALUE))
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


    @Test
    fun sharedPlayableCueGuardMatchesTheTimelineFilter() {
        assertTrue(DubbingTimeline.isPlayableCue(SubLine(0, 0, 1_000, "spoken line")))
        assertFalse(DubbingTimeline.isPlayableCue(SubLine(1, -1, 1_000, "negative start")))
        assertFalse(DubbingTimeline.isPlayableCue(SubLine(2, 1_000, 1_000, "zero length")))
        assertFalse(DubbingTimeline.isPlayableCue(SubLine(3, 2_000, 1_000, "reversed window")))
        assertFalse(DubbingTimeline.isPlayableCue(SubLine(4, 1_000, 2_000, "   ")))
    }


    @Test
    fun longCueCanBeFoundBehindThousandsOfExpiredOverlappingRows() {
        val rows = buildList {
            add(SubLine(0, 0L, 20_000L, "long active cue"))
            for (index in 1..10_000) {
                val start = index * 2L
                add(SubLine(index, start, start + 1L, "short cue $index"))
            }
        }

        assertEquals(0, DubbingTimeline(rows).lineAt(15_001L))
    }

    @Test
    fun indexedLookupMatchesBruteForceForUnorderedOverlappingCueSets() {
        val rows = List(1_200) { index ->
            val start = ((index * 37) % 8_000).toLong()
            val duration = ((index * 17) % 1_200 + 1).toLong()
            SubLine(index, start, start + duration, "cue $index")
        }
        val timeline = DubbingTimeline(rows)

        for (position in 0L..10_000L step 47L) {
            var expectedIndex = -1
            var latestStart = Long.MIN_VALUE
            rows.forEachIndexed { index, line ->
                if (
                    DubbingTimeline.isPlayableCue(line) &&
                    line.startMs <= position &&
                    position < line.endMs &&
                    (line.startMs > latestStart ||
                        line.startMs == latestStart && index > expectedIndex)
                ) {
                    expectedIndex = index
                    latestStart = line.startMs
                }
            }
            assertEquals("position=$position", expectedIndex, timeline.lineAt(position))
        }
    }


@Test
    fun refusesMoreInputRowsThanTheTimelineCanSafelyScan() {
        val invalid = SubLine(0, -1L, 0L, "invalid")
        val rows = List(DubbingTimeline.MAX_INPUT_CUE_COUNT + 1) { invalid }
        val error = runCatching { DubbingTimeline(rows) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun refusesTooManyPlayableCuesBeforeAllocatingTheSegmentTree() {
        val rows = List(DubbingTimeline.MAX_PLAYABLE_CUE_COUNT + 1) { index ->
            SubLine(index, index.toLong() * 2L, index.toLong() * 2L + 1L, "cue $index")
        }
        val error = runCatching { DubbingTimeline(rows) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun invalidRowsDoNotCountTowardThePlayableCueBudget() {
        val rows = List(2_000) { index -> SubLine(index, -1L, 0L, "") } +
            SubLine(2_000, 0L, 100L, "only playable cue")
        val timeline = DubbingTimeline(rows)
        assertEquals(2_000, timeline.lineAt(50L))
    }

}
