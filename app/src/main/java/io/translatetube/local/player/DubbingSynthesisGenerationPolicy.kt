package io.translatetube.local.player

/** Prevents stale synthesis jobs from publishing readiness or retrying into a newer config. */
internal object DubbingSynthesisGenerationPolicy {
    fun canContinue(
        expectedGeneration: Int,
        currentGeneration: Int,
        taskActive: Boolean,
        engineReleased: Boolean,
    ): Boolean =
        !engineReleased && taskActive && expectedGeneration == currentGeneration
}
