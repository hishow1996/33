package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCacheFreshnessPolicyTest {
    @Test
    fun engineOrVoiceRestartCanReuseAnExactSuccessfulCache() {
        assertFalse(
            DubbingCacheFreshnessPolicy.shouldForceFreshSynthesis(
                forceRegenerateDubbing = true,
                reuseMatchingDubbingCache = true,
            ),
        )
        assertTrue(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = DubbingCacheFreshnessPolicy.shouldForceFreshSynthesis(
                    forceRegenerateDubbing = true,
                    reuseMatchingDubbingCache = true,
                ),
                wasWrittenByCurrentRun = false,
            ),
        )
    }

    @Test
    fun explicitFreshSynthesisStillBypassesOldCache() {
        assertTrue(
            DubbingCacheFreshnessPolicy.shouldForceFreshSynthesis(
                forceRegenerateDubbing = true,
                reuseMatchingDubbingCache = false,
            ),
        )
        assertFalse(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = DubbingCacheFreshnessPolicy.shouldForceFreshSynthesis(
                    forceRegenerateDubbing = true,
                    reuseMatchingDubbingCache = false,
                ),
                wasWrittenByCurrentRun = false,
            ),
        )
    }

    @Test
    fun normalPlaybackKeepsReuseOfPreviouslySuccessfulCache() {
        assertTrue(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = false,
                wasWrittenByCurrentRun = false,
            ),
        )
    }

    @Test
    fun forcedSynthesisNeverReadsAnOldMatchingCache() {
        assertFalse(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = true,
                wasWrittenByCurrentRun = false,
            ),
        )
    }

    @Test
    fun forcedSynthesisCanUseMatchingCurrentMemoryWhenDiskWriteFailed() {
        assertTrue(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = true,
                wasWrittenByCurrentRun = false,
                hasMatchingCurrentMemoryEntry = true,
            ),
        )
    }

    @Test
    fun oldDiskCacheStillCannotBypassForcedSynthesisWhenMemoryIsAbsent() {
        assertFalse(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = true,
                wasWrittenByCurrentRun = false,
                hasMatchingCurrentMemoryEntry = false,
            ),
        )
    }

    @Test
    fun forcedSynthesisCanReadPcmAfterCurrentRunPublishesIt() {
        assertTrue(
            DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = true,
                wasWrittenByCurrentRun = true,
            ),
        )
    }
}
