package io.translatetube.local.player

/**
 * Keep source speech muted while an active Chinese cue is waiting for voice, unless the user
 * explicitly enables the source track during this playback session.
 */
internal object DubbingSourceVolumePolicy {
    fun sourceGain(
        originalVolume: Float,
        @Suppress("UNUSED_PARAMETER") dubbingVolume: Float,
        dubbingEnabled: Boolean,
        dubbingStarted: Boolean,
        dubbingCuePending: Boolean = false,
        manualOriginalAudioOverride: Boolean = false,
    ): Float {
        if (!originalVolume.isFinite() || originalVolume <= 0f) return 0f
        if (manualOriginalAudioOverride) return 1f
        if (dubbingEnabled && dubbingCuePending && !dubbingStarted) return 0f
        return 1f
    }
}
