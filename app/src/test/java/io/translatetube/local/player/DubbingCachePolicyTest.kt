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
    fun neverEvictsCurrentSessionFilesEvenWhenTheirCacheExceedsBudget() {
        val active = DubbingCacheEntry("active", 200L, 1L, true)
        assertTrue(DubbingCachePolicy.filesToEvict(listOf(active), 100L).isEmpty())
    }

    @Test
    fun largeAggregateSizesDoNotOverflowAndSkipUnnecessaryEvictions() {
        val huge = DubbingCacheEntry("huge", Long.MAX_VALUE, 1L, false)
        val small = DubbingCacheEntry("small", 10L, 2L, false)
        assertEquals(
            listOf(huge),
            DubbingCachePolicy.filesToEvict(listOf(huge, small), 10L),
        )
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
    fun oldVoiceCacheForSameVideoCanBeEvictedButCurrentVoiceCacheIsProtected() {
        val oldVoice = DubbingCacheEntry("same-video-old-voice", 80L, 10L, false)
        val currentVoice = DubbingCacheEntry("same-video-current-voice", 80L, 20L, true)

        assertEquals(
            listOf(oldVoice),
            DubbingCachePolicy.filesToEvict(listOf(currentVoice, oldVoice), 80L),
        )
    }

}