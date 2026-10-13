package io.translatetube.local.player

/** Pure state transition for the playback-screen dubbing switch. */
internal data class DubbingToggleState(
    val dubbingOn: Boolean,
    val muteOriginal: Boolean,
)

internal object DubbingPlaybackPolicy {
    /**
     * Disabling dubbing always restores normal original audio. Re-enabling dubbing starts with
     * the original track muted when a voice line is ready; the separate original-audio control
     * can switch to a mixed soundtrack afterwards.
     */
    fun toggle(dubbingOn: Boolean): DubbingToggleState =
        if (dubbingOn) DubbingToggleState(dubbingOn = false, muteOriginal = false)
        else DubbingToggleState(dubbingOn = true, muteOriginal = true)
}
