package io.translatetube.local.player

/** Pure guard for committing the next static audio chunk after asynchronous track creation. */
internal object DubbingChunkTransitionPolicy {
    fun canCommit(
        released: Boolean,
        currentGeneration: Int,
        expectedGeneration: Int,
        currentTrackIsSource: Boolean,
    ): Boolean =
        !released && currentGeneration == expectedGeneration && currentTrackIsSource
}
