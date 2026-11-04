package io.translatetube.local.player

/**
 * Decides whether a playing sentence owns voice output after the video playhead crosses another
 * subtitle cue. Live System TTS completion is callback-driven; PCM remains active until the final
 * buffer has actually completed, whether or not timing predicted an overrun.
 */
internal object DubbingOverrunPolicy {
    fun shouldHold(
        currentLineIndex: Int,
        nextLineIndex: Int,
        liveUtteranceActive: Boolean,
        pcmOutputActive: Boolean,
        currentAudioFinished: Boolean,
    ): Boolean {
        if (currentLineIndex < 0 || currentLineIndex == nextLineIndex) return false
        if (liveUtteranceActive) return true
        return pcmOutputActive && !currentAudioFinished
    }
}
