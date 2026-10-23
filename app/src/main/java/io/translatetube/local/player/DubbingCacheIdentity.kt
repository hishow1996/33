package io.translatetube.local.player

/**
 * Builds a deterministic per-cue PCM cache filename.
 *
 * Per-line role/voice must participate in the identity (as it does in role-aware TTS pipelines
 * such as pyVideoTrans); otherwise changing a person’s assigned voice can replay stale audio.
 */
internal object DubbingCacheIdentity {
    /**
     * Staging names must be unique per engine instance because retired and replacement engines can
     * briefly overlap while a blocking TTS call unwinds. Keep the ".pcm.tmp" suffix for cleanup.
     */
    fun tempFileName(targetName: String, writerId: Int): String {
        require(targetName.endsWith(".pcm")) { "配音缓存目标必须是 PCM 文件" }
        require(writerId > 0) { "配音缓存写入实例 ID 必须为正数" }
        return "${targetName.removeSuffix(".pcm")}.${writerId}.pcm.tmp"
    }

    fun fileName(
        lineIndex: Int,
        speakerId: Int,
        engineKey: String,
        text: String,
        speed: Float,
        cueDurationMs: Long,
    ): String {
        fun fingerprint(value: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        val textHash = fingerprint(text)
        // Bump the version when cached PCM content rules change; include the bounded cue window.
        // v10: cached PCM is now only ever compressed to fit its window (never stretched), and a
        // merged chunk's identity is its merged text/window. Older entries were slowed down to fill
        // their window, so they must be synthesized again.
        val engineHash = fingerprint(
            "$engineKey-v10-speed-$speed-cue-ms-${cueDurationMs.coerceAtLeast(0L)}-fit"
        )
        return "$lineIndex-v$speakerId-v10-speed-$engineHash-t$textHash.pcm"
    }
}
