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
    fun memoryPolicyUsesSmallerBudgetsForConstrainedPhones() {
        assertEquals(4L * 1024 * 1024, DubbingPcmMemoryPolicy.maxBytes(true, 512))
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
}
