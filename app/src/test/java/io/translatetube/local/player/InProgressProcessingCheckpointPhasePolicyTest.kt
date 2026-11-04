package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InProgressProcessingCheckpointPhasePolicyTest {
    @Test
    fun translationAndSynthesisPhasesCanBePersisted() {
        assertTrue(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.TRANSLATING))
        assertTrue(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.SYNTHESIZING))
    }

    @Test
    fun readyCanBePersistedWhileBackgroundDubbingIsStillRunning() {
        assertTrue(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.READY))
    }

    @Test
    fun sourceAcquisitionAndTerminalPhasesAreNotPersistedAsSubtitleCheckpoints() {
        assertFalse(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.IDLE))
        assertFalse(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.PARSING))
        assertFalse(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.DOWNLOADING_AUDIO))
        assertFalse(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.TRANSCRIBING))
        assertFalse(InProgressProcessingCheckpointPhasePolicy.canPersist(Phase.ERROR))
    }
}
