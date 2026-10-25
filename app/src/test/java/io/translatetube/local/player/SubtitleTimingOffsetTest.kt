package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimingOffsetTest {
    @Test
    fun ordinaryCuesKeepTheSharedLead() {
        assertEquals(SubtitleTimingOffset.LEAD_MS, SubtitleTimingOffset.dubbingLeadMs(isFirstSpokenCue = false))
    }

    @Test
    fun firstCueStartsEarlierThanOrdinaryCues() {
        val first = SubtitleTimingOffset.dubbingLeadMs(isFirstSpokenCue = true)
        assertEquals(SubtitleTimingOffset.LEAD_MS + SubtitleTimingOffset.FIRST_CUE_EXTRA_LEAD_MS, first)
        assertTrue(first > SubtitleTimingOffset.LEAD_MS)
    }
}
