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

    fun indexForSpeaker(speakerId: Int, voiceCount: Int): Int {
        if (voiceCount <= 1 || speakerId < 0) return 0
        return 1 + Math.floorMod(speakerId, voiceCount - 1)
    }
}
