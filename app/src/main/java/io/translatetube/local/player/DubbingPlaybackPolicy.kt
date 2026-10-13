package io.translatetube.local.player

/**
 * Pure state transition for the dubbing switch.
 *
 * Muting the source is a mixing option for dubbed playback, not a reason to keep
 * the original track silent after dubbing itself has been switched off.
 */
internal object DubbingPlaybackPolicy {
    data class ToggleState(
        val dubbingOn: Boolean,
        val muteOriginal: Boolean,
    )

    fun toggle(dubbingOn: Boolean, muteOriginal: Boolean): ToggleState =
        if (dubbingOn) {
            ToggleState(dubbingOn = false, muteOriginal = false)
        } else {
            ToggleState(dubbingOn = true, muteOriginal = muteOriginal)
        }
}
