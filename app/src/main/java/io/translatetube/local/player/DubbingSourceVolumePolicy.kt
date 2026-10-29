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
        // Fail closed for non-finite voice gain while speech is active: an invalid setting must not
        // silently bring the source track back over potentially audible dubbing.
        dubbingEnabled && dubbingStarted &&
            (!dubbingVolume.isFinite() || dubbingVolume > 0f) -> 0f
        else -> 1f
    }
}
