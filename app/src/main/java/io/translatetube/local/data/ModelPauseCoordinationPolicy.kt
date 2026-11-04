package io.translatetube.local.data

/** Coordinates one notification pause button across two independent model download engines. */
internal object ModelPauseCoordinationPolicy {
    fun allActiveTasksPaused(
        managedActive: Boolean,
        managedPaused: Boolean,
        piperActive: Boolean,
        piperPaused: Boolean,
    ): Boolean {
        val activeStates = buildList {
            if (managedActive) add(managedPaused)
            if (piperActive) add(piperPaused)
        }
        return activeStates.isNotEmpty() && activeStates.all { it }
    }

    /** A click pauses all active engines unless they are already all paused, in which case it resumes all. */
    fun shouldPauseAfterToggle(
        managedActive: Boolean,
        managedPaused: Boolean,
        piperActive: Boolean,
        piperPaused: Boolean,
    ): Boolean = !allActiveTasksPaused(managedActive, managedPaused, piperActive, piperPaused)
}
