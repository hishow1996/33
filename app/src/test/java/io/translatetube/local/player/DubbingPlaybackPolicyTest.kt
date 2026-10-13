package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPlaybackPolicyTest {
    @Test
    fun turningDubbingOffRestoresOriginalAudio() {
        val next = DubbingPlaybackPolicy.toggle(dubbingOn = true, muteOriginal = true)

        assertFalse(next.dubbingOn)
        assertFalse(next.muteOriginal)
    }

    @Test
    fun turningDubbingOnPreservesCurrentMixPreference() {
        val next = DubbingPlaybackPolicy.toggle(dubbingOn = false, muteOriginal = false)

        assertTrue(next.dubbingOn)
        assertFalse(next.muteOriginal)
    }
}
