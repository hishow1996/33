package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedTextCueTimingPolicyTest {
    @Test
    fun preservesDistinctExplicitDurationsInsteadOfUsingOneDefaultForEveryCue() {
        assertEquals(
            TimedTextCueTimingPolicy.Timing(1_200L, 4_600L),
            TimedTextCueTimingPolicy.parse("1.2", "3.4"),
        )
        assertEquals(
            TimedTextCueTimingPolicy.Timing(5_000L, 5_750L),
            TimedTextCueTimingPolicy.parse("5", "0.75"),
        )
    }

    @Test
    fun usesThreeSecondFallbackOnlyWhenDurationIsMissing() {
        assertEquals(
            TimedTextCueTimingPolicy.Timing(2_500L, 5_500L),
            TimedTextCueTimingPolicy.parse("2.5", null),
        )
        assertEquals(
            TimedTextCueTimingPolicy.Timing(2_500L, 5_500L),
            TimedTextCueTimingPolicy.parse("2.5", ""),
        )
    }

    @Test
    fun rejectsNegativeNonFiniteInvalidAndOverflowingTimes() {
        assertNull(TimedTextCueTimingPolicy.parse(null, "3"))
        assertNull(TimedTextCueTimingPolicy.parse("-1", "3"))
        assertNull(TimedTextCueTimingPolicy.parse("NaN", "3"))
        assertNull(TimedTextCueTimingPolicy.parse("1", "Infinity"))
        assertNull(TimedTextCueTimingPolicy.parse("1", "not-a-duration"))
        assertNull(TimedTextCueTimingPolicy.parse("1", "0"))
        assertNull(TimedTextCueTimingPolicy.parse("9.22e15", "3"))
    }
}
