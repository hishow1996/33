package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPcmMemoryCacheTest {
    @Test
    fun evictsLeastRecentlyUsedPcmWhenByteBudgetIsExceeded() {
        val cache = DubbingPcmMemoryCache(maxBytes = 24L, maxEntries = 8)
        val oldest = FloatArray(4) { 0.1f } to 24_000
        val newest = FloatArray(4) { 0.2f } to 24_000

        cache[1] = oldest
        cache[2] = newest

        assertNull(cache[1])
        assertSame(newest.first, cache[2]?.first)
        assertEquals(16L, cache.retainedBytes())
        assertTrue(cache.retainedBytes() <= 24L)
    }

    @Test
    fun touchingAnEntryUpdatesLruOrder() {
        val cache = DubbingPcmMemoryCache(maxBytes = 24L, maxEntries = 8)
        val first = FloatArray(4) { 0.1f } to 24_000
        val second = FloatArray(4) { 0.2f } to 24_000

        cache[1] = first
        cache[2] = second
        cache[1] // make entry 1 most recently used
        cache[3] = FloatArray(4) { 0.3f } to 24_000

        assertSame(first.first, cache[1]?.first)
        assertNull(cache[2])
        assertTrue(cache.retainedBytes() <= 24L)
    }

    @Test
    fun replacesExistingEntriesWithoutLeakingTheByteAccounting() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = FloatArray(4) to 24_000
        cache[1] = FloatArray(8) to 24_000

        assertEquals(32L, cache.retainedBytes())
        assertEquals(1, cache.size)
    }

    @Test
    fun doesNotKeepOnePcmLargerThanTheEntireBudget() {
        val cache = DubbingPcmMemoryCache(maxBytes = 16L, maxEntries = 8)
        cache[7] = FloatArray(5) to 24_000

        assertFalse(cache.containsKey(7))
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun clearResetsTheCacheAndItsByteAccounting() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = FloatArray(4) to 24_000
        cache.clear()
        assertTrue(cache.isEmpty())
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun memoryPolicyUsesSmallerBudgetsForConstrainedPhones() {
        assertEquals(4L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(true, 512))
        assertEquals(8L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 256))
        assertEquals(16L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 384))
        assertEquals(32L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 512))
    }
}
