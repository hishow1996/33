package io.translatetube.local.player

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable identity for a persisted synthesized cue. Keep this pure so cache identity rules can
 * be unit-tested without Android or an active speech engine.
 */
internal object DubbingCacheIdentity {
    fun fileName(
        lineIndex: Int,
        speakerId: Int,
        engineSignature: String,
        speed: Float,
        translatedText: String,
    ): String {
        val normalizedSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        val engineHash = fingerprint("pcm-cache-v6|$engineSignature|speed=${String.format(Locale.US, "%.3f", normalizedSpeed)}")
        val textHash = fingerprint(translatedText)
        return "$lineIndex-v$speakerId-e$engineHash-t$textHash.pcm"
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
}

/** Header layout: 4-byte sample rate, 4-byte sample count, followed by Float32 mono PCM. */
internal object PcmCacheFormat {
    const val HEADER_BYTES = 8L
    const val MAX_SAMPLES = 16_000_000

    fun isValidHeader(sampleRate: Int, sampleCount: Int, fileLength: Long): Boolean {
        if (sampleRate !in 8_000..192_000 || sampleCount !in 0..MAX_SAMPLES) return false
        return fileLength == HEADER_BYTES + sampleCount.toLong() * 4L
    }
}
