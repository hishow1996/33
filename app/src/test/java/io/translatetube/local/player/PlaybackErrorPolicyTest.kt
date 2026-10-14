package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {
    @Test
    fun ignoresEmptyErrorEventsAndErrorsBeforeAnyCurrentSourceIsInstalled() {
        assertFalse(PlaybackErrorPolicy.shouldHandle(null, null, false, false))
        assertFalse(PlaybackErrorPolicy.shouldHandle("", "yt-id", false, false))
        assertFalse(PlaybackErrorPolicy.shouldHandle("decoder failed", null, false, false))
        assertFalse(PlaybackErrorPolicy.shouldHandle("old stream failed", "new-id", false, false))
    }

    @Test
    fun localMediaErrorsBecomeActionableAfterTheUriIsInstalled() {
        assertTrue(PlaybackErrorPolicy.shouldHandle("decoder failed", null, false, true))
    }

    @Test
    fun youtubeErrorsNeedBothTheActiveSourceAndCurrentStreamCandidates() {
        assertFalse(PlaybackErrorPolicy.shouldHandle("stale error", "current-id", false, true))
        assertFalse(PlaybackErrorPolicy.shouldHandle("stale error", "current-id", true, false))
        assertTrue(PlaybackErrorPolicy.shouldHandle("current stream failed", "current-id", true, true))
    }
}
