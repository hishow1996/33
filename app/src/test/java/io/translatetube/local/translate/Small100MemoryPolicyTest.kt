package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranslationMemoryPolicyTest {
    @Test
    fun enforcesMinimumHeadroomForSmallAndMidRangeDevices() {
        val threshold = 768L * LocalTranslationMemoryPolicy.MEGABYTE
        assertEquals(threshold, LocalTranslationMemoryPolicy.requiredAvailableBytes(2L * 1024L * LocalTranslationMemoryPolicy.MEGABYTE))
        assertEquals(threshold, LocalTranslationMemoryPolicy.requiredAvailableBytes(6L * 1024L * LocalTranslationMemoryPolicy.MEGABYTE))
    }

    @Test
    fun scalesHeadroomWithLargerTotalMemoryButKeepsAnUpperBound() {
        assertEquals(
            1024L * LocalTranslationMemoryPolicy.MEGABYTE,
            LocalTranslationMemoryPolicy.requiredAvailableBytes(8L * 1024L * LocalTranslationMemoryPolicy.MEGABYTE),
        )
        assertEquals(
            LocalTranslationMemoryPolicy.MAX_AVAILABLE_BYTES,
            LocalTranslationMemoryPolicy.requiredAvailableBytes(32L * 1024L * LocalTranslationMemoryPolicy.MEGABYTE),
        )
    }

    @Test
    fun rejectsInsufficientMemoryAndAcceptsTheExactThreshold() {
        val total = 6L * 1024L * LocalTranslationMemoryPolicy.MEGABYTE
        val required = LocalTranslationMemoryPolicy.requiredAvailableBytes(total)
        assertFalse(LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(required - 1L, total))
        assertTrue(LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(required, total))
        assertTrue(LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(required + 1L, total))
    }

    @Test
    fun zeroMemoryInformationFallsBackToTheSafeMinimum() {
        assertEquals(
            LocalTranslationMemoryPolicy.MIN_AVAILABLE_BYTES,
            LocalTranslationMemoryPolicy.requiredAvailableBytes(0L),
        )
        assertFalse(LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(0L, 0L))
    }
}
