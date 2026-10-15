package io.translatetube.local.yt

/** Shared limits for yt-dlp audio output and the app's retry-audio cache. */
internal object YtDlpAudioPolicy {
    const val MAX_AUDIO_BYTES = 160L * 1024L * 1024L

    /** Only completed media files qualify; yt-dlp control/partial files must never be published. */
    fun isCandidateOutput(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        return lower.isNotBlank() &&
            !lower.endsWith(".part") &&
            !lower.endsWith(".ytdl") &&
            !lower.endsWith(".tmp") &&
            !lower.endsWith(".json") &&
            !lower.endsWith(".description")
    }

    fun isValidDownload(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes in 1L..MAX_AUDIO_BYTES
}
