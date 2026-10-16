package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioCachePolicyTest {
    @Test
    fun reusesValidAudioWithinSevenDayWindow() {
        val now = 10_000_000_000L
        assertTrue(
            AsrAudioCachePolicy.canReuse(
                true, 40L * 1024 * 1024,
                now - 24L * 60 * 60 * 1000L, now,
            )
        )
    }

    @Test
    fun onlyAcceptsCompleteDownloadedAudio() {
        assertTrue(AsrAudioCachePolicy.isCompleteDownload(1_024L, 1_024L))
        assertTrue(AsrAudioCachePolicy.isCompleteDownload(-1L, 1_024L))
        assertFalse(AsrAudioCachePolicy.isCompleteDownload(0L, 1_024L))
        assertFalse(AsrAudioCachePolicy.isCompleteDownload(-2L, 1_024L))
        assertFalse(AsrAudioCachePolicy.isCompleteDownload(1_024L, 1_023L))
        assertFalse(AsrAudioCachePolicy.isCompleteDownload(1_024L, 1_025L))
        assertFalse(AsrAudioCachePolicy.isCompleteDownload(-1L, 0L))
        assertTrue(AsrAudioCachePolicy.isCompleteDownload(-1L, 200L * 1024L * 1024L))
    }

    @Test
    fun allowsLargeAudioWritesButProtectsLongCountersFromOverflow() {
        val largeSize = 200L * 1024L * 1024L
        assertTrue(AsrAudioCachePolicy.canAppendBytes(0L, 1L))
        assertTrue(AsrAudioCachePolicy.canAppendBytes(largeSize, 1L))
        assertTrue(AsrAudioCachePolicy.canAppendBytes(0L, Long.MAX_VALUE))
        assertFalse(AsrAudioCachePolicy.canAppendBytes(-1L, 1L))
        assertFalse(AsrAudioCachePolicy.canAppendBytes(0L, -1L))
        assertFalse(AsrAudioCachePolicy.canAppendBytes(largeSize, Long.MAX_VALUE))
    }

    @Test
    fun onlyPrunesPartialsOlderThanGracePeriodWithSaneTimestamps() {
        val now = 10_000_000_000L
        assertTrue(
            AsrAudioCachePolicy.isStalePartial(
                now - AsrAudioCachePolicy.PARTIAL_TTL_MS - 1L, now,
            )
        )
        assertFalse(
            AsrAudioCachePolicy.isStalePartial(
                now - AsrAudioCachePolicy.PARTIAL_TTL_MS, now,
            )
        )
        assertFalse(AsrAudioCachePolicy.isStalePartial(now + 1L, now))
        assertFalse(AsrAudioCachePolicy.isStalePartial(0L, now))
    }

    @Test
    fun reusesLargeFilesButRejectsMissingEmptyExpiredAndFutureDatedFiles() {
        val now = 10_000_000_000L
        assertFalse(AsrAudioCachePolicy.canReuse(false, 100L, now - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 0L, now - 1, now))
        assertTrue(AsrAudioCachePolicy.canReuse(true, 200L * 1024L * 1024L, now - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 100L, now - AsrAudioCachePolicy.TTL_MS - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 100L, now + 1, now))
    }
}
