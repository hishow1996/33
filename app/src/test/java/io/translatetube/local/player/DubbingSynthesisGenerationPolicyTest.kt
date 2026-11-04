package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSynthesisGenerationPolicyTest {
    @Test
    fun currentActiveWorkerMayPublish() {
        assertTrue(
            DubbingSynthesisGenerationPolicy.canContinue(
                expectedGeneration = 4,
                currentGeneration = 4,
                taskActive = true,
                engineReleased = false,
            ),
        )
    }

    @Test
    fun oldGenerationCannotPublishAfterEngineConfigurationChanges() {
        assertFalse(
            DubbingSynthesisGenerationPolicy.canContinue(
                expectedGeneration = 4,
                currentGeneration = 5,
                taskActive = true,
                engineReleased = false,
            ),
        )
    }

    @Test
    fun cancelledWorkerCannotPublish() {
        assertFalse(
            DubbingSynthesisGenerationPolicy.canContinue(
                expectedGeneration = 4,
                currentGeneration = 4,
                taskActive = false,
                engineReleased = false,
            ),
        )
    }

    @Test
    fun releasedEngineCannotPublish() {
        assertFalse(
            DubbingSynthesisGenerationPolicy.canContinue(
                expectedGeneration = 4,
                currentGeneration = 4,
                taskActive = true,
                engineReleased = true,
            ),
        )
    }
}
