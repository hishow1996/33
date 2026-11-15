package io.translatetube.local.player

import io.translatetube.local.player.MissingMediaRecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class MissingMediaRecoveryPolicyTest {
    @Test
    fun runningPipelineThatHasNotAssembledTheStreamYetJustWaits() {
        // Resuming mid-synthesis shows the synthesis page before the video is mounted.
        assertEquals(Action.WAIT, MissingMediaRecoveryPolicy.decide(true, true, true))
        assertEquals(Action.WAIT, MissingMediaRecoveryPolicy.decide(true, true, false))
    }

    @Test
    fun runningPipelineWithAssembledStreamOnlyRemountsYouTubeAndKeepsSynthesis() {
        assertEquals(Action.REMOUNT_ONLY, MissingMediaRecoveryPolicy.decide(true, false, true))
    }

    @Test
    fun finishedPipelineOrNonYouTubeSourceReopensAsBefore() {
        assertEquals(Action.REOPEN, MissingMediaRecoveryPolicy.decide(false, false, true))
        assertEquals(Action.REOPEN, MissingMediaRecoveryPolicy.decide(false, true, true))
        assertEquals(Action.REOPEN, MissingMediaRecoveryPolicy.decide(true, false, false))
    }
}
