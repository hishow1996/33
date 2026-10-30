package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Small100MemoryPolicyTest {
    @Test
    fun enforcesMinimumHeadroomForSmallAndMidRangeDevices() {
        val threshold = 768L * Small100MemoryPolicy.MEGABYTE
        assertEquals(threshold, Small100MemoryPolicy.requiredAvailableBytes(2L * 1024L * Small100MemoryPolicy.MEGABYTE))
        assertEquals(threshold, Small100MemoryPolicy.requiredAvailableBytes(6L * 1024L * Small100MemoryPolicy.MEGABYTE))
    }

    @Test
    fun scalesHeadroomWithLargerTotalMemoryButKeepsAnUpperBound() {
        assertEquals(
            1024L * Small100MemoryPolicy.MEGABYTE,
            Small100MemoryPolicy.requiredAvailableBytes(8L * 1024L * Small100MemoryPolicy.MEGABYTE),
        )
        assertEquals(
            Small100MemoryPolicy.MAX_AVAILABLE_BYTES,
            Small100MemoryPolicy.requiredAvailableBytes(32L * 1024L * Small100MemoryPolicy.MEGABYTE),
        )
    }

    @Test
    fun rejectsInsufficientMemoryAndAcceptsTheExactThreshold() {
        val total = 6L * 1024L * Small100MemoryPolicy.MEGABYTE
        val required = Small100MemoryPolicy.requiredAvailableBytes(total)
        assertFalse(Small100MemoryPolicy.hasEnoughAvailableMemory(required - 1L, total))
        assertTrue(Small100MemoryPolicy.hasEnoughAvailableMemory(required, total))
        assertTrue(Small100MemoryPolicy.hasEnoughAvailableMemory(required + 1L, total))
    }

    @Test
    fun zeroMemoryInformationFallsBackToTheSafeMinimum() {
        assertEquals(
            Small100MemoryPolicy.MIN_AVAILABLE_BYTES,
            Small100MemoryPolicy.requiredAvailableBytes(0L),
        )
        assertFalse(Small100MemoryPolicy.hasEnoughAvailableMemory(0L, 0L))
    }
}
