package io.translatetube.local.player

/** Guards against a tick starting voice output after pause, disable, or engine replacement. */
internal object DubbingPlaybackOwnerPolicy {
    fun mayStartAudio(
        dubbingEnabled: Boolean,
        playerIsPlaying: Boolean,
        engineIsCurrent: Boolean,
    ): Boolean = dubbingEnabled && playerIsPlaying && engineIsCurrent

    fun shouldStop(
        hasSelectedLine: Boolean,
        dubbingEnabled: Boolean,
        playerIsPlaying: Boolean,
        engineIsCurrent: Boolean,
    ): Boolean =
        hasSelectedLine && !mayStartAudio(dubbingEnabled, playerIsPlaying, engineIsCurrent)
}
