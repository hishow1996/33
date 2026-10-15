package io.translatetube.local.yt

/** Shared limits for yt-dlp audio output and the app's retry-audio cache. */
internal object YtDlpAudioPolicy {
    const val MAX_AUDIO_BYTES = 160L * 1024L * 1024L

    fun isValidDownload(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes in 1L..MAX_AUDIO_BYTES
}
