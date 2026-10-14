package io.translatetube.local.player

import java.security.MessageDigest

/**
 * Versioned identity for a line of synthesized PCM. Every input that can change the generated
 * waveform participates in the cache key, so text, person voice, TTS engine, speed, or cue timing
 * edits cannot accidentally replay old audio.
 */
internal object DubbingCacheIdentity {
    private const val VERSION = 8
    private const val ADAPTIVE_POLICY = "adaptive-speed-v2"

    fun fileName(
        index: Int,
        speakerId: Int,
        translatedText: String,
        engineKey: String,
        speed: Float,
        cueDurationMs: Long,
    ): String {
        val normalizedSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        val textHash = fingerprint(translatedText)
        val engineHash = fingerprint(
            "v$VERSION|$engineKey|speed=$normalizedSpeed|cue-ms=${cueDurationMs.coerceAtLeast(0L)}|$ADAPTIVE_POLICY"
        )
        return "$index-v$speakerId-e$engineHash-t$textHash.pcm"
    }

    private fun fingerprint(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
