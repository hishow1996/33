package io.translatetube.local.player

/** Distinguishes an intentional no-translation marker from a previous TTS synthesis failure. */
internal object DubbingCacheAudioPolicy {
    /**
     * null = missing/corrupt cache and synthesis should be queued;
     * false = valid intentional silence because no translated text exists;
     * true = cached synthesized audio is reusable.
     */
    fun reusableState(cachedAudio: Boolean?, translatedText: String?): Boolean? {
        if (cachedAudio == false && !translatedText.isNullOrBlank()) return null
        return cachedAudio
    }

    /**
     * A cache row must agree with whether this cue needs spoken audio.
     * Empty translations intentionally map to an empty marker; translated cues must contain audio.
     */
    fun isCompatibleWithTranslation(hasAudio: Boolean, translatedText: String?): Boolean =
        hasAudio == !translatedText.isNullOrBlank()
}
