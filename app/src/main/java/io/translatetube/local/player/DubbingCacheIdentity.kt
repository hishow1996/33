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
        // Bump v9 when cached PCM acceptance limits change; include the bounded cue window.
        val engineHash = fingerprint(
            "$engineKey-v9-speed-$speed-cue-ms-${cueDurationMs.coerceAtLeast(0L)}-adaptive"
        )
        return "$lineIndex-v$speakerId-v9-speed-$engineHash-t$textHash.pcm"
    }
}
