package io.translatetube.local.tts

/** Maps a person id to a device-provided Chinese voice while reserving index 0 as the default voice. */
internal object SystemTtsSpeakerVoicePolicy {
    fun indexForSpeaker(speakerId: Int, voiceCount: Int): Int {
        if (voiceCount <= 1 || speakerId < 0) return 0
        return 1 + Math.floorMod(speakerId, voiceCount - 1)
    }
}
