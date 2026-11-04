package io.translatetube.local.player

/**
 * READY is also a valid checkpoint phase: playback may start after the initial PCM buffer while
 * the remaining subtitle cues are still being synthesized in the background.
 */
internal object InProgressProcessingCheckpointPhasePolicy {
    fun canPersist(phase: Phase): Boolean = phase in setOf(
        Phase.TRANSLATING,
        Phase.SYNTHESIZING,
        Phase.READY,
    )
}
