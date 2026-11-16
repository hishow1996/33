package io.translatetube.local.asr

import java.util.Locale

/** The two supported Whisper output modes. */
internal object WhisperOutputModePolicy {
    const val PREF_KEY = "whisper_output_mode"
    const val TRANSCRIBE = "transcribe"
    const val TRANSLATE_TO_ENGLISH = "translate"

    fun normalize(value: String?): String =
        when (value?.trim()?.lowercase(Locale.ROOT)) {
            TRANSLATE_TO_ENGLISH, "english" -> TRANSLATE_TO_ENGLISH
            else -> TRANSCRIBE
        }

    /** The language of the text returned by Whisper, not necessarily the spoken audio. */
    fun outputLanguage(mode: String?, detectedLanguage: String?): String =
        if (normalize(mode) == TRANSLATE_TO_ENGLISH) "en" else detectedLanguage?.trim().orEmpty()
}
