package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingStartupFlushPolicyTest {
    @Test
    fun keepsMountedCuesOnlyForTheExpectedStartupSeek() {
        assertTrue(
            DubbingStartupFlushPolicy.shouldKeepMountedBus(
                expectedPositionMs = 12_000L,
                actualPositionMs = 12_000L,
                nowNanos = 100L,
                expiresAtNanos = 200L,
            ),
        )
    }

    @Test
    fun aRealSeekDuringTheStartupWindowInvalidatesTheMountedCues() {
        assertFalse(
            DubbingStartupFlushPolicy.shouldKeepMountedBus(
                expectedPositionMs = 12_000L,
                actualPositionMs = 35_000L,
                nowNanos = 100L,
                expiresAtNanos = 200L,
            ),
        )
    }

    @Test
    fun expiredOrInvalidStartupTokensNeverPreserveOldCues() {
        assertFalse(
            DubbingStartupFlushPolicy.shouldKeepMountedBus(
                expectedPositionMs = 12_000L,
                actualPositionMs = 12_000L,
                nowNanos = 200L,
                expiresAtNanos = 200L,
            ),
        )
        assertFalse(
            DubbingStartupFlushPolicy.shouldKeepMountedBus(
                expectedPositionMs = -1L,
                actualPositionMs = 12_000L,
                nowNanos = 100L,
                expiresAtNanos = 200L,
            ),
        )
    }
}
