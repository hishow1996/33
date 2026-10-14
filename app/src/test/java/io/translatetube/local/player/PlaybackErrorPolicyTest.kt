package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {
    @Test
    fun ignoresEmptyErrorEvents() {
        assertFalse(PlaybackErrorPolicy.shouldHandle(null, null, false))
        assertFalse(PlaybackErrorPolicy.shouldHandle("", "yt-id", false))
        assertFalse(PlaybackErrorPolicy.shouldHandle("   ", null, false))
    }

    @Test
    fun localMediaErrorsRemainActionable() {
        assertTrue(PlaybackErrorPolicy.shouldHandle("decoder failed", null, false))
    }

    @Test
    fun youtubeErrorsAreActionableOnlyAfterCurrentStreamCandidatesExist() {
        assertFalse(PlaybackErrorPolicy.shouldHandle("old stream failed", "new-id", false))
        assertTrue(PlaybackErrorPolicy.shouldHandle("current stream failed", "current-id", true))
    }
}
