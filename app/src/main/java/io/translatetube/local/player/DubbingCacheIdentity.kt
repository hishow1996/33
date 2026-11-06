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

    /**
     * Read the subtitle index prefix from a finalized PCM filename. Callers must still compare
     * the full expected identity before treating the file as part of the active synthesis session.
     */
    fun lineIndexFromFileName(fileName: String): Int? {
        if (!fileName.endsWith(".pcm")) return null
        val separator = fileName.indexOf("-v")
        if (separator <= 0) return null
        return fileName.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 }
    }

    // 重进视频时要给每一句字幕算一次缓存文件名；每次都 getInstance + String.format 在老手机上
    // 累计起来很可观。每个线程复用一个 SHA-256，十六进制用查表拼接。输出与旧实现逐字节一致，
    // 所以已有的 PCM 缓存文件名不会变。
    private val sha256Local = object : ThreadLocal<java.security.MessageDigest>() {
        override fun initialValue(): java.security.MessageDigest =
            java.security.MessageDigest.getInstance("SHA-256")
    }
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun fingerprint(value: String): String {
        val digest = sha256Local.get()!!
        digest.reset()
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(16)
        for (i in 0 until 8) {
            val b = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_DIGITS[b ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
        }
        return String(out)
    }

    fun fileName(
        lineIndex: Int,
        speakerId: Int,
        engineKey: String,
        text: String,
        speed: Float,
        cueDurationMs: Long,
    ): String {
        val textHash = fingerprint(text)
        // Bump the version when cached PCM content rules change; include the bounded cue window.
        // v10: cached PCM is now only ever compressed to fit its window (never stretched), and a
        // merged chunk's identity is its merged text/window. Older entries were slowed down to fill
        // their window, so they must be synthesized again.
        // v11: every engine's PCM now has its leading/trailing silence trimmed (online engines such as
        // Microsoft Edge TTS used to keep 100-300 ms of lead-in), so older entries start late.
        val engineHash = fingerprint(
            "$engineKey-v11-speed-$speed-cue-ms-${cueDurationMs.coerceAtLeast(0L)}-fit"
        )
        return "$lineIndex-v$speakerId-v11-speed-$engineHash-t$textHash.pcm"
    }
}
