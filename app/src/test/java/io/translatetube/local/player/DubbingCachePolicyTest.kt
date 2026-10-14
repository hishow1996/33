package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCachePolicyTest {
    @Test
    fun evictsOldestInactiveVideoCacheUntilWithinBudget() {
        val old = DubbingCacheEntry("old", 60L, 10L, false)
        val newer = DubbingCacheEntry("newer", 50L, 20L, false)
        val active = DubbingCacheEntry("active", 80L, 1L, true)

        assertEquals(listOf(old, newer), DubbingCachePolicy.filesToEvict(listOf(newer, active, old), 80L))
    }

    @Test
    fun neverEvictsTheCurrentVideosFilesEvenWhenItsCacheExceedsBudget() {
        val active = DubbingCacheEntry("active", 200L, 1L, true)
        assertTrue(DubbingCachePolicy.filesToEvict(listOf(active), 100L).isEmpty())
    }

    @Test
    fun keepsAllFilesWhenAlreadyWithinBudget() {
        val entries = listOf(
            DubbingCacheEntry("active", 20L, 1L, true),
            DubbingCacheEntry("old", 30L, 2L, false),
        )
        assertTrue(DubbingCachePolicy.filesToEvict(entries, 50L).isEmpty())
    }
    @Test
    fun emptyPcmIsNeverAcceptedAsAReusableDubbingCache() {
        assertTrue(!DubbingCachePolicy.isReusablePcmSampleCount(0))
        assertTrue(!DubbingCachePolicy.isReusablePcmSampleCount(-1))
        assertTrue(DubbingCachePolicy.isReusablePcmSampleCount(1))
        assertTrue(DubbingCachePolicy.isReusablePcmSampleCount(DubbingCachePolicy.MAX_PCM_SAMPLE_COUNT))
        assertTrue(!DubbingCachePolicy.isReusablePcmSampleCount(DubbingCachePolicy.MAX_PCM_SAMPLE_COUNT + 1))
    }

}
