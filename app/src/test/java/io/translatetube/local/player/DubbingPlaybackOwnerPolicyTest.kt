package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPlaybackOwnerPolicyTest {
    @Test
    fun liveStartGuardRejectsPauseDisableAndEngineReplacement() {
        assertTrue(DubbingPlaybackOwnerPolicy.mayStartAudio(true, true, true))
        assertFalse(DubbingPlaybackOwnerPolicy.mayStartAudio(true, false, true))
        assertFalse(DubbingPlaybackOwnerPolicy.mayStartAudio(false, true, true))
        assertFalse(DubbingPlaybackOwnerPolicy.mayStartAudio(true, true, false))
    }

    @Test
    fun keepsVoiceOnlyWhileCurrentEngineIsEnabledAndVideoIsPlaying() {
        assertFalse(
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = true,
                dubbingEnabled = true,
                playerIsPlaying = true,
                engineIsCurrent = true,
            )
        )
    }

    @Test
    fun stopsVoiceWhenUserDisablesDubbingOrPausesVideo() {
        assertTrue(
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = true,
                dubbingEnabled = false,
                playerIsPlaying = true,
                engineIsCurrent = true,
            )
        )
        assertTrue(
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = true,
                dubbingEnabled = true,
                playerIsPlaying = false,
                engineIsCurrent = true,
            )
        )
    }

    @Test
    fun stopsVoiceWhenItsEngineWasReplaced() {
        assertTrue(
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = true,
                dubbingEnabled = true,
                playerIsPlaying = true,
                engineIsCurrent = false,
            )
        )
    }

    @Test
    fun doesNotKeepStoppingWhenNoLineIsSelected() {
        assertFalse(
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = false,
                dubbingEnabled = false,
                playerIsPlaying = false,
                engineIsCurrent = false,
            )
        )
    }
}
