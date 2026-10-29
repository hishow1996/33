package io.translatetube.local.player

/** Computes source gain so the original narration does not overlap audible Chinese dubbing. */
internal object DubbingSourceVolumePolicy {
    fun sourceGain(
        originalVolume: Float,
        dubbingVolume: Float,
        dubbingEnabled: Boolean,
        dubbingStarted: Boolean,
    ): Float = when {
        !originalVolume.isFinite() || originalVolume <= 0f -> 0f
        dubbingEnabled && dubbingVolume.isFinite() && dubbingVolume > 0f && dubbingStarted -> 0f
        else -> 1f
    }
}
