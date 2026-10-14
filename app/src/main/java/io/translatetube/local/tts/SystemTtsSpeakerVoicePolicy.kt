package io.translatetube.local.tts

/** Maps a person id to a device-provided Chinese voice while reserving index 0 as the default voice. */
internal object SystemTtsSpeakerVoicePolicy {
    /**
     * Prefer on-device voices for reliable offline dubbing. Only expose network-backed voices
     * when the engine has no local voice in the requested language.
     */
    fun <T> preferOfflineVoices(
        voices: List<T>,
        requiresNetwork: (T) -> Boolean,
    ): List<T> {
        val offline = voices.filterNot(requiresNetwork)
        return if (offline.isNotEmpty()) offline else voices
    }

    /**
     * Prefer the current voice if it is listed; otherwise choose a listed voice, and finally
     * fall back to the current voice when an OEM TTS engine omits it from getVoices().
     */
    fun <T> chooseDefaultVoice(
        current: T?,
        available: List<T>,
        name: (T) -> String,
    ): T? {
        val currentName = current?.let(name)
        return currentName?.let { candidate ->
            available.firstOrNull { name(it) == candidate }
        } ?: available.firstOrNull() ?: current
    }

    fun indexForSpeaker(speakerId: Int, voiceCount: Int): Int {
        if (voiceCount <= 1 || speakerId < 0) return 0
        return 1 + Math.floorMod(speakerId, voiceCount - 1)
    }
}
