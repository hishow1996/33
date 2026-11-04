package io.translatetube.local.player

/** Maps arbitrary video keys (including content URIs) to stable, filesystem-safe cache names. */
internal object DubbingCachePath {
    fun directoryName(videoKey: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(videoKey.toByteArray(Charsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
