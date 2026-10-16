package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingChunkTransitionPolicyTest {
    @Test
    fun acceptsOnlyTheStillActiveSourceTrackGeneration() {
        assertTrue(DubbingChunkTransitionPolicy.canCommit(false, 7, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(true, 7, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(false, 8, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(false, 7, 7, false))
    }
}
