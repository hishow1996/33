package io.translatetube.local.player

/**
 * Normalizes the speaker part of a PCM cache identity according to engine behavior.
 * Fixed-voice providers don't use speakerId; voice-capable providers must keep it isolated.
 */
internal object DubbingCacheSpeakerIdentityPolicy {
    fun cacheSpeakerId(
        speakerId: Int,
        speakerAffectsOutput: Boolean,
    ): Int = if (speakerAffectsOutput) speakerId else -1
}
