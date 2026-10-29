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
    fun evictsOtherVideosBeforeOldVoiceVariantsOfTheCurrentVideo() {
        val otherVideo = DubbingCacheEntry(
            path = "other-video",
            bytes = 80L,
            lastModified = 1L,
            isCurrentSessionFile = false,
            isCurrentVideoFile = false,
        )
        val oldVoiceForCurrentVideo = DubbingCacheEntry(
            path = "current-video-old-voice",
            bytes = 10L,
            lastModified = 2L,
            isCurrentSessionFile = false,
            isCurrentVideoFile = true,
        )
        val currentVoice = DubbingCacheEntry(
            path = "current-video-current-voice",
            bytes = 20L,
            lastModified = 3L,
            isCurrentSessionFile = true,
            isCurrentVideoFile = true,
        )

        // Removing the unrelated video is enough to meet the budget, so preserve the cache
        // needed if the user switches back to the previous voice.
        assertEquals(
            listOf(otherVideo),
            DubbingCachePolicy.filesToEvict(
                listOf(oldVoiceForCurrentVideo, currentVoice, otherVideo),
                30L,
            ),
        )
    }

    @Test
    fun oldVoiceVariantsRemainEvictableAfterOtherVideoCachesAreExhausted() {
        val otherVideo = DubbingCacheEntry("other", 60L, 1L, false, false)
        val oldVoice = DubbingCacheEntry("old-voice", 60L, 2L, false, true)
        val currentVoice = DubbingCacheEntry("current-voice", 60L, 3L, true, true)

        assertEquals(
            listOf(otherVideo, oldVoice),
            DubbingCachePolicy.filesToEvict(listOf(oldVoice, currentVoice, otherVideo), 80L),
        )
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