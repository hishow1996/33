package io.translatetube.local.yt

/** Validate that yt-dlp produced a non-empty media file; audio size is not capped here. */
internal object YtDlpAudioPolicy {
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
        isFile && sizeBytes > 0L
}
