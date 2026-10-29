package io.translatetube.local.player

/**
 * Keeps source audio audible independently of dubbing playback, respecting the original-volume setting.
 */
internal object DubbingSourceVolumePolicy {
    fun sourceGain(
        originalVolume: Float,
        @Suppress("UNUSED_PARAMETER") dubbingVolume: Float,
        @Suppress("UNUSED_PARAMETER") dubbingEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") dubbingStarted: Boolean,
    ): Float = if (!originalVolume.isFinite() || originalVolume <= 0f) 0f else 1f
}
