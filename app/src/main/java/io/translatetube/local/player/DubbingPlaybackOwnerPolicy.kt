package io.translatetube.local.player

/** Guards against a tick starting voice output after pause, disable, or engine replacement. */
internal object DubbingPlaybackOwnerPolicy {
    fun shouldStop(
        hasSelectedLine: Boolean,
        dubbingEnabled: Boolean,
        playerIsPlaying: Boolean,
        engineIsCurrent: Boolean,
    ): Boolean =
        hasSelectedLine && (!dubbingEnabled || !playerIsPlaying || !engineIsCurrent)
}
