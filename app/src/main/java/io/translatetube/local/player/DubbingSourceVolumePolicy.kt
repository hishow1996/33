package io.translatetube.local.player

/**
 * Preserve the user's source gain except while an active Chinese cue is still waiting for voice.
 * This prevents source speech from becoming an unintended substitute for missing dubbing.
 */
internal object DubbingSourceVolumePolicy {
    fun sourceGain(
        originalVolume: Float,
        @Suppress("UNUSED_PARAMETER") dubbingVolume: Float,
        dubbingEnabled: Boolean,
        dubbingStarted: Boolean,
        dubbingCuePending: Boolean = false,
    ): Float {
        if (!originalVolume.isFinite() || originalVolume <= 0f) return 0f
        if (dubbingEnabled && dubbingCuePending && !dubbingStarted) return 0f
        return 1f
    }
}
