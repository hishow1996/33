package io.translatetube.local.tts

/**
 * Kokoro v1.1 multi-language model speaker IDs are zero-based with 103 available voices.
 * Keep all UI, TTS synthesis and persisted voice assignments on the same validated range.
 */
internal object KokoroVoiceRange {
    const val VOICE_COUNT = 103
    const val MAX_SPEAKER_ID = VOICE_COUNT - 1
    const val DEFAULT_SPEAKER_ID = 14

    fun clampSpeakerId(id: Int): Int = id.coerceIn(0, MAX_SPEAKER_ID)

    /** -1 means "use the default voice"; non-negative values select a concrete speaker ID. */
    fun clampSelectionId(id: Int): Int = id.coerceIn(-1, MAX_SPEAKER_ID)
}
