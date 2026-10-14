package io.translatetube.local.player

/** Filters delayed Media3 errors from a previous media item while the next source is being resolved. */
internal object PlaybackErrorPolicy {
    fun shouldHandle(
        error: String?,
        videoId: String?,
        hasCurrentYouTubeStreams: Boolean,
        hasActiveSource: Boolean,
    ): Boolean {
        if (error.isNullOrBlank() || !hasActiveSource) return false
        // Local media errors are actionable once its URI has been installed. YouTube errors also
        // require stream candidates from the current session so an old error cannot refresh a new URL.
        return videoId == null || hasCurrentYouTubeStreams
    }
}
