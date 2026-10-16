package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTimelineProgressPolicyTest {
    @Test
    fun unknownOrInvalidDurationDoesNotInventProgress() {
        assertNull(MediaTimelineProgressPolicy.nextPercent(5_000_000L, 0L))
        assertNull(MediaTimelineProgressPolicy.nextPercent(5_000_000L, -1L))
        assertNull(MediaTimelineProgressPolicy.nextPercent(-1L, 10_000_000L))
    }

    @Test
    fun percentReflectsMeasuredMediaTimeline() {
        assertEquals(0, MediaTimelineProgressPolicy.nextPercent(0L, 10_000_000L))
        assertEquals(25, MediaTimelineProgressPolicy.nextPercent(2_500_000L, 10_000_000L))
        assertEquals(90, MediaTimelineProgressPolicy.nextPercent(9_000_000L, 10_000_000L))
    }

    @Test
    fun percentagesNeverGoBackAndLeaveCompletionForSuccessfulFinalize() {
        assertEquals(40, MediaTimelineProgressPolicy.nextPercent(4_000_000L, 10_000_000L, 20))
        assertNull(MediaTimelineProgressPolicy.nextPercent(3_000_000L, 10_000_000L, 40))
        assertNull(MediaTimelineProgressPolicy.nextPercent(4_000_000L, 10_000_000L, 40))
        assertEquals(99, MediaTimelineProgressPolicy.nextPercent(10_000_000L, 10_000_000L, 98))
        assertNull(MediaTimelineProgressPolicy.nextPercent(10_000_000L, 10_000_000L, 99))
    }

    @Test
    fun outOfRangeTimestampsAreBounded() {
        assertEquals(99, MediaTimelineProgressPolicy.nextPercent(Long.MAX_VALUE, 1L))
    }
}
