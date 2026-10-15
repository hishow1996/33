package io.translatetube.local.yt

/** Shared limits for yt-dlp audio output and the app's retry-audio cache. */
internal object YtDlpAudioPolicy {
    const val MAX_AUDIO_BYTES = 160L * 1024L * 1024L

    // The command's output template always includes an extension. An allowlist prevents an
    // unexpected sidecar (subtitle, thumbnail, JSON, or fragment file) being renamed to .bin.
    private val MEDIA_EXTENSIONS = setOf(
        "3gp", "aac", "ac3", "aiff", "alac", "amr", "ape", "avi", "eac3",
        "flac", "flv", "m2ts", "m4a", "m4b", "m4p", "m4v", "mka", "mkv",
        "mov", "mp2", "mp3", "mp4", "mpeg", "mpg", "ogg", "oga", "opus",
        "ts", "tta", "wav", "webm", "wma", "wmv", "wv",
    )

    /** Only recognized media containers qualify; partial files and sidecars are never published. */
    fun isCandidateOutput(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        if (lower.isBlank() || lower.startsWith(".")) return false
        return lower.substringAfterLast('.', "") in MEDIA_EXTENSIONS
    }

    fun isValidDownload(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes in 1L..MAX_AUDIO_BYTES
}
