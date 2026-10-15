package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQualitySwitchPolicyTest {
    @Test
    fun capturePreservesPositionAndWhetherPlaybackShouldResume() {
        assertEquals(
            PlayerQualityResumeState(positionMs = 42_500L, wasPlaying = true),
            PlayerQualitySwitchPolicy.capture(positionMs = 42_500L, playWhenReady = true),
        )
        assertEquals(
            PlayerQualityResumeState(positionMs = 42_500L, wasPlaying = false),
            PlayerQualitySwitchPolicy.capture(positionMs = 42_500L, playWhenReady = false),
        )
    }

    @Test
    fun captureClampsUnknownNegativePositionToZero() {
        assertEquals(
            PlayerQualityResumeState(positionMs = 0L, wasPlaying = true),
            PlayerQualitySwitchPolicy.capture(positionMs = -1L, playWhenReady = true),
        )
    }
}
