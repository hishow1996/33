package io.translatetube.local.tts

/** Capability policy for choosing genuinely different voices per detected speaker. */
internal object TtsSpeakerCapabilityPolicy {
    fun supportsDistinctVoices(
        engineId: String,
        systemChineseVoiceCount: Int = 0,
    ): Boolean = when (engineId) {
        "kokoro", "edge" -> true
        "system" -> systemChineseVoiceCount > 1
        // The Piper models in the current catalog are single-speaker; PiperTts deliberately
        // fixes the native speaker id to zero.
        else -> false
    }
}
