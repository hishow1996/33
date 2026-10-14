package io.translatetube.local.player

/** Filters delayed Media3 errors from a previous YouTube item while the next URL is being resolved. */
internal object PlaybackErrorPolicy {
    fun shouldHandle(
        error: String?,
        videoId: String?,
        hasCurrentYouTubeStreams: Boolean,
    ): Boolean {
        if (error.isNullOrBlank()) return false
        // Local media errors always belong to the active item. YouTube errors are actionable only
        // after this session has installed the stream candidates which recovery can refresh.
        return videoId == null || hasCurrentYouTubeStreams
    }
}
