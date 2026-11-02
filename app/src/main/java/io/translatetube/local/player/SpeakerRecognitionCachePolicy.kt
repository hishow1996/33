package io.translatetube.local.player

import java.security.MessageDigest

/**
 * Identifies a diarization result independently from the selected TTS engine.
 *
 * The fingerprint changes when the media source, cue timing/text, or diarization model changes.
 * Translation text and voice IDs are intentionally excluded so a TTS/translation-engine switch
 * can reuse the same person timeline.
 */
internal object SpeakerRecognitionCacheIdentity {
    data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    fun create(
        sessionKey: String,
        sourceAudioIdentity: String,
        cues: Sequence<Cue>,
        modelVersion: String,
    ): String {
        if (sessionKey.isBlank() || sourceAudioIdentity.isBlank() || modelVersion.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateField(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0)
        }

        updateField("speaker-recognition-cache-v2")
        updateField(sessionKey)
        updateField(sourceAudioIdentity)
        updateField(modelVersion)
        var cueCount = 0
        for (cue in cues) {
            if (cue.startMs < 0L || cue.endMs <= cue.startMs || cue.text.isBlank()) continue
            updateField(cue.startMs.toString())
            updateField(cue.endMs.toString())
            updateField(cue.text.trim())
            cueCount++
        }
        if (cueCount == 0) return ""
        updateField(cueCount.toString())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A cache hit is valid only when the exact identity and a usable saved person timeline exist. */
internal object SpeakerRecognitionCachePolicy {
    fun canReuse(
        savedIdentity: String?,
        requestedIdentity: String,
        totalCueCount: Int,
        assignedCueCount: Int,
        personCount: Int,
    ): Boolean =
        requestedIdentity.isNotBlank() &&
            savedIdentity == requestedIdentity &&
            totalCueCount > 0 &&
            assignedCueCount > 0 &&
            personCount > 0
}
