package io.translatetube.local.player

/**
 * The player may outlive the Activity. Keep the visible video modes separate from background
 * audio-only playback so the service can protect audio without mistaking a hidden Surface for a
 * failed video renderer.
 */
enum class VideoPresentationMode {
    /** Video is presented by the normal playback page. */
    FOREGROUND,

    /** Video is presented by the application overlay while the Activity may be stopped. */
    FLOATING,

    /** No video surface is expected; audio and dubbing may continue in the service. */
    BACKGROUND_AUDIO,
}

/** Pure decisions shared by playback lifecycle code and unit tests. */
internal object VideoPresentationModePolicy {
    fun shouldMonitorVideo(mode: VideoPresentationMode): Boolean =
        mode != VideoPresentationMode.BACKGROUND_AUDIO

    fun shouldRebindVideoSurface(mode: VideoPresentationMode): Boolean =
        mode != VideoPresentationMode.BACKGROUND_AUDIO
}
