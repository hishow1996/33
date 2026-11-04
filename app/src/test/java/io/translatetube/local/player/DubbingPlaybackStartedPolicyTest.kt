package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPlaybackStartedPolicyTest {
    @Test
    fun currentEnabledPlaybackCanPublishStartState() {
        assertTrue(
            DubbingPlaybackStartedPolicy.shouldApply(
                dubbingEnabled = true,
                engineIsCurrent = true,
                startedLineStillCurrent = true,
            ),
        )
    }

    @Test
    fun stalePlayGenerationCannotMuteOriginalOrHighlightOldLine() {
        assertFalse(
            DubbingPlaybackStartedPolicy.shouldApply(
                dubbingEnabled = true,
                engineIsCurrent = true,
                startedLineStillCurrent = false,
            ),
        )
    }

    @Test
    fun disabledDubbingAndReplacedEngineRejectLateStartCallbacks() {
        assertFalse(DubbingPlaybackStartedPolicy.shouldApply(false, true, true))
        assertFalse(DubbingPlaybackStartedPolicy.shouldApply(true, false, true))
    }
}
