package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPlaybackPolicyTest {
    @Test
    fun turningDubbingOffRestoresOriginalAudio() {
        val result = DubbingPlaybackPolicy.toggle(dubbingOn = true)
        assertFalse(result.dubbingOn)
        assertFalse(result.muteOriginal)
    }

    @Test
    fun turningDubbingOnStartsInDubbingMode() {
        val result = DubbingPlaybackPolicy.toggle(dubbingOn = false)
        assertTrue(result.dubbingOn)
        assertTrue(result.muteOriginal)
    }

    @Test
    fun toggleAlternatesBetweenDubbingAndOriginalPlayback() {
        val disabled = DubbingPlaybackPolicy.toggle(dubbingOn = true)
        val enabled = DubbingPlaybackPolicy.toggle(dubbingOn = disabled.dubbingOn)
        assertEquals(DubbingToggleState(true, true), enabled)
    }
}
