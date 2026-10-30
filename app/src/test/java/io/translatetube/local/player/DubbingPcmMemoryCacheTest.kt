package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPcmMemoryCacheTest {
    private fun entry(
        samples: FloatArray,
        identity: String = "line-1-v9.pcm",
        sampleRate: Int = 24_000,
    ) = DubbingPcmMemoryEntry(samples, sampleRate, identity)

    @Test
    fun evictsLeastRecentlyUsedPcmWhenByteBudgetIsExceeded() {
        val cache = DubbingPcmMemoryCache(maxBytes = 24L, maxEntries = 8)
        val oldest = entry(FloatArray(4) { 0.1f }, identity = "old.pcm")
        val newest = entry(FloatArray(4) { 0.2f }, identity = "new.pcm")

        cache[1] = oldest
        cache[2] = newest

        assertNull(cache[1])
        assertSame(newest.samples, cache[2]?.samples)
        assertEquals("new.pcm", cache[2]?.cacheIdentity)
        assertEquals(16L, cache.retainedBytes())
        assertTrue(cache.retainedBytes() <= 24L)
    }

    @Test
    fun touchingAnEntryUpdatesLruOrder() {
        val cache = DubbingPcmMemoryCache(maxBytes = 40L, maxEntries = 8)
        val first = entry(FloatArray(4) { 0.1f }, identity = "first.pcm")
        val second = entry(FloatArray(4) { 0.2f }, identity = "second.pcm")

        cache[1] = first
        cache[2] = second
        cache[1] // make entry 1 most recently used
        cache[3] = entry(FloatArray(4) { 0.3f }, identity = "third.pcm")

        assertSame(first.samples, cache[1]?.samples)
        assertNull(cache[2])
        assertTrue(cache.retainedBytes() <= 40L)
    }

    @Test
    fun replacesExistingEntriesWithoutLeakingTheByteAccounting() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = entry(FloatArray(4), identity = "old.pcm")
        cache[1] = entry(FloatArray(8), identity = "new.pcm")

        assertEquals(32L, cache.retainedBytes())
        assertEquals(1, cache.size)
        assertEquals("new.pcm", cache[1]?.cacheIdentity)
    }

    @Test
    fun doesNotKeepOnePcmLargerThanTheEntireBudget() {
        val cache = DubbingPcmMemoryCache(maxBytes = 16L, maxEntries = 8)
        cache[7] = entry(FloatArray(5))

        assertFalse(cache.containsKey(7))
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun clearResetsTheCacheAndItsByteAccounting() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = entry(FloatArray(4))
        cache.clear()
        assertTrue(cache.isEmpty())
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun loweringRuntimeBudgetEvictsOldestPcmImmediately() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        val oldest = entry(FloatArray(8), identity = "oldest.pcm")
        val newest = entry(FloatArray(8), identity = "newest.pcm")
        cache[1] = oldest
        cache[2] = newest
        cache[1] // Make the older entry most recently used first.
        cache[2] // The newest entry becomes least-recently-used after this access order.

        cache.updateBudget(32L)

        assertEquals(32L, cache.retainedBytes())
        assertEquals(1, cache.size)
        assertFalse(cache.containsKey(1))
        assertTrue(cache.containsKey(2))
    }

    @Test
    fun zeroRuntimeBudgetClearsAllRetainedPcm() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = entry(FloatArray(4))
        cache.updateBudget(0L)
        assertTrue(cache.isEmpty())
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun memoryPolicyUsesSmallerBudgetsForConstrainedPhones() {
        assertEquals(4L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(true, 512))
        assertEquals(8L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 192))
        assertEquals(8L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 256))
        assertEquals(16L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 384))
        assertEquals(32L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 512))
    }

    @Test
    fun pcmFromPreviousTtsOrSpeedConfigurationCannotBeReused() {
        assertTrue(DubbingMemoryCacheIdentityPolicy.canReuse("system-v9.pcm", "system-v9.pcm"))
        assertFalse(DubbingMemoryCacheIdentityPolicy.canReuse("system-v9.pcm", "piper-v9.pcm"))
        assertFalse(DubbingMemoryCacheIdentityPolicy.canReuse("speed-1.0-v9.pcm", "speed-1.25-v9.pcm"))
    }

    @Test
    fun missingIdentityFailsClosed() {
        assertFalse(DubbingMemoryCacheIdentityPolicy.canReuse(null, "active.pcm"))
        assertFalse(DubbingMemoryCacheIdentityPolicy.canReuse("cached.pcm", null))
        assertFalse(DubbingMemoryCacheIdentityPolicy.canReuse("", "active.pcm"))
    }

@Test
    fun rejectsInvalidSampleRatesAndEmptyBuffers() {
        val cache = DubbingPcmMemoryCache(maxBytes = 1024L, maxEntries = 8)
        cache[1] = entry(FloatArray(4), sampleRate = 7_999)
        cache[2] = entry(FloatArray(4), sampleRate = 192_001)
        cache[3] = entry(FloatArray(0))
        assertEquals(0, cache.size)
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun rejectsBlankIdentityAndRemovesPreviouslyCachedStaleEntry() {
        val cache = DubbingPcmMemoryCache(maxBytes = 1024L, maxEntries = 8)
        cache[1] = entry(FloatArray(4), identity = "active.pcm")
        cache[1] = entry(FloatArray(4), identity = "   ")
        assertFalse(cache.containsKey(1))
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun bulkInsertUsesTheSameByteBudgetAndEvictionRules() {
        val cache = DubbingPcmMemoryCache(maxBytes = 32L, maxEntries = 8)
        cache.putAll(
            linkedMapOf(
                1 to entry(FloatArray(4), identity = "one.pcm"),
                2 to entry(FloatArray(4), identity = "two.pcm"),
                3 to entry(FloatArray(4), identity = "three.pcm"),
            )
        )
        assertEquals(2, cache.size)
        assertEquals(32L, cache.retainedBytes())
        assertFalse(cache.containsKey(1))
        assertTrue(cache.containsKey(2))
        assertTrue(cache.containsKey(3))
    }

    @Test
    fun invalidBudgetsDoNotRetainPcm() {
        val noBytes = DubbingPcmMemoryCache(maxBytes = 0L, maxEntries = 8)
        val noEntries = DubbingPcmMemoryCache(maxBytes = 1024L, maxEntries = 0)
        noBytes[1] = entry(FloatArray(4))
        noEntries[1] = entry(FloatArray(4))
        assertTrue(noBytes.isEmpty())
        assertTrue(noEntries.isEmpty())
        assertEquals(0L, noBytes.retainedBytes())
        assertEquals(0L, noEntries.retainedBytes())
    }

    @Test
    fun negativeCacheBudgetIsAlsoRejected() {
        val cache = DubbingPcmMemoryCache(maxBytes = -1L, maxEntries = 8)
        cache[1] = entry(FloatArray(4))
        assertFalse(cache.containsKey(1))
        assertEquals(0L, cache.retainedBytes())
    }

    @Test
    fun unknownMemoryClassUsesConservativeBudget() {
        assertEquals(8L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, 0))
        assertEquals(8L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(false, -1))
    }

    @Test
    fun missingLookupDoesNotChangeBudget() {
        val cache = DubbingPcmMemoryCache(maxBytes = 64L, maxEntries = 8)
        cache[1] = entry(FloatArray(4))
        assertNull(cache[2])
        assertEquals(16L, cache.retainedBytes())
    }

}
