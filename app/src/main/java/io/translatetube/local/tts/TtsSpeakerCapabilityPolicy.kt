package io.translatetube.local.tts

/** Capability policy for choosing genuinely different voices per detected speaker. */
internal object TtsSpeakerCapabilityPolicy {
    fun supportsDistinctVoices(
        engineId: String,
        systemChineseVoiceCount: Int = 0,
    ): Boolean = when (engineId) {
        "kokoro", "edge", "gemini" -> true
        "system" -> systemChineseVoiceCount > 1
        // All three sherpa VITS/Matcha catalog engines are single-speaker and render sid=0.
        "piper", "melo", "matcha" -> false
        // Unknown engines also default to no distinct-speaker capability.
        else -> false
    }
}
