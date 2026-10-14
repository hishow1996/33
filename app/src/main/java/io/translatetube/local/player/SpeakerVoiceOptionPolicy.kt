package io.translatetube.local.player

import io.translatetube.local.tts.KokoroVoiceRange

internal data class SpeakerVoiceOption(
    val speakerId: Int,
    val label: String,
)

/** Engine-aware choices for the line-level speaker picker. */
internal object SpeakerVoiceOptionPolicy {
    fun options(
        isSystemTts: Boolean,
        systemVoiceNames: List<String>,
    ): List<SpeakerVoiceOption> {
        if (!isSystemTts) {
            return buildList {
                add(SpeakerVoiceOption(-1, "默认音色（设置中的默认音色）"))
                for (voiceId in 0..KokoroVoiceRange.MAX_SPEAKER_ID) {
                    add(SpeakerVoiceOption(voiceId, "Kokoro 音色 $voiceId"))
                }
            }
        }

        val defaultName = systemVoiceNames.firstOrNull()
        val defaultLabel = if (defaultName == null) "系统默认音色" else "系统默认音色 · $defaultName"
        val options = mutableListOf(SpeakerVoiceOption(-1, defaultLabel))
        // SystemTts reserves catalog index 0 for the default and maps IDs 0..N-2 to
        // catalog indices 1..N-1. Never expose duplicate modulo aliases in the picker.
        if (systemVoiceNames.size > 1) {
            for (voiceIndex in 1 until systemVoiceNames.size) {
                options += SpeakerVoiceOption(voiceIndex - 1, systemVoiceNames[voiceIndex])
            }
        }
        return options
    }

    fun labelFor(
        speakerId: Int,
        isSystemTts: Boolean,
        systemVoiceNames: List<String>,
    ): String {
        if (!isSystemTts) {
            return if (speakerId < 0) "默认音色" else "Kokoro 音色 $speakerId"
        }
        if (speakerId < 0) {
            val defaultName = systemVoiceNames.firstOrNull()
            return if (defaultName == null) "系统默认音色" else "默认 · $defaultName"
        }
        if (systemVoiceNames.size <= 1) return systemVoiceNames.firstOrNull() ?: "系统音色"
        val index = 1 + Math.floorMod(speakerId, systemVoiceNames.size - 1)
        return systemVoiceNames[index]
    }
}
