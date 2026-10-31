package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPcmAudioCachePolicyTest {
    @Test
    fun reusesOnlyNonEmptyEvenSizedPcmWithinSevenDays() {
        val now = 100L * 24L * 60L * 60L * 1_000L
        assertTrue(
            LocalPcmAudioCachePolicy.canReuse(
                isFile = true,
                sizeBytes = 2L,
                lastModifiedMs = now - LocalPcmAudioCachePolicy.TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(LocalPcmAudioCachePolicy.canReuse(true, 0L, now, now))
        assertFalse(LocalPcmAudioCachePolicy.canReuse(true, 3L, now, now))
        assertFalse(
            LocalPcmAudioCachePolicy.canReuse(
                true, 2L, now - LocalPcmAudioCachePolicy.TTL_MS - 1L, now,
            ),
        )
        assertFalse(LocalPcmAudioCachePolicy.canReuse(true, 2L, now + 1L, now))
        assertFalse(LocalPcmAudioCachePolicy.canReuse(false, 2L, now, now))
    }

    @Test
    fun freshnessWindowsStayAlignedWithRemoteSourceAudioCache() {
        assertTrue(LocalPcmAudioCachePolicy.TTL_MS == AsrAudioCachePolicy.TTL_MS)
        assertTrue(LocalPcmAudioCachePolicy.PARTIAL_TTL_MS == AsrAudioCachePolicy.PARTIAL_TTL_MS)
    }

    @Test
    fun rejectsPcmFilesLargerThanDecoderAndCacheLimit() {
        assertFalse(
            LocalPcmAudioCachePolicy.canReuse(
                isFile = true,
                sizeBytes = LocalPcmAudioCachePolicy.MAX_CACHE_BYTES + 2L,
                lastModifiedMs = 1L,
                nowMs = 1L,
            ),
        )
    }

    @Test
    fun cacheBudgetLimitsBothTotalBytesAndFileCount() {
        assertFalse(LocalPcmAudioCachePolicy.exceedsBudget(0L, 0))
        assertFalse(
            LocalPcmAudioCachePolicy.exceedsBudget(
                LocalPcmAudioCachePolicy.MAX_CACHE_BYTES,
                LocalPcmAudioCachePolicy.MAX_CACHE_FILES,
            ),
        )
        assertTrue(
            LocalPcmAudioCachePolicy.exceedsBudget(
                LocalPcmAudioCachePolicy.MAX_CACHE_BYTES + 1L,
                1,
            ),
        )
        assertTrue(
            LocalPcmAudioCachePolicy.exceedsBudget(
                1L,
                LocalPcmAudioCachePolicy.MAX_CACHE_FILES + 1,
            ),
        )
    }

    @Test
    fun stalePartialFilesAreCleanedOnlyAfterSixHours() {
        val now = 50_000_000L
        assertFalse(
            LocalPcmAudioCachePolicy.isStalePartial(
                now - LocalPcmAudioCachePolicy.PARTIAL_TTL_MS,
                now,
            ),
        )
        assertTrue(
            LocalPcmAudioCachePolicy.isStalePartial(
                now - LocalPcmAudioCachePolicy.PARTIAL_TTL_MS - 1L,
                now,
            ),
        )
    }
}
