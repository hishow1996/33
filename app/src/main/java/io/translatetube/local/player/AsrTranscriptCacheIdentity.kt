package io.translatetube.local.player

import io.translatetube.local.asr.WhisperOutputModePolicy
import java.util.Locale

/**
 * Stable identity for ASR transcript and subtitle caches.
 *
 * Whisper output modes produce different text and therefore use separate source and checkpoint
 * cache namespaces. Other recognizers retain their existing engine identity.
 */
internal object AsrTranscriptCacheIdentity {
    @Suppress("UNUSED_PARAMETER")
    fun key(
        provider: String,
        localModelId: String,
        language: String = "auto",
        configFingerprint: String = "",
        modelFingerprint: String = "",
        whisperTask: String = WhisperOutputModePolicy.TRANSCRIBE,
    ): String {
        val normalizedProvider = token(provider)
        val model = if (normalizedProvider == "local") token(localModelId) else "cloud"
        val isWhisper = normalizedProvider == "local" &&
            (model == "whisper" || model.startsWith("whisper-") || model.startsWith("whisper_"))
        return enginePrefix(provider, localModelId, whisperTask) +
            if (isWhisper) "engine-v2" else "engine-v1"
    }

    /** Prefixes also isolate legacy lookups and resumable segment checkpoints by Whisper task. */
    fun enginePrefix(
        provider: String,
        localModelId: String,
        whisperTask: String = WhisperOutputModePolicy.TRANSCRIBE,
    ): String {
        val normalizedProvider = token(provider)
        val model = if (normalizedProvider == "local") token(localModelId) else "cloud"
        val base = "$normalizedProvider-$model-"
        val isWhisper = normalizedProvider == "local" &&
            (model == "whisper" || model.startsWith("whisper-") || model.startsWith("whisper_"))
        return if (isWhisper) "$base${WhisperOutputModePolicy.normalize(whisperTask)}-" else base
    }

    private fun token(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "unknown" }
}
