package io.translatetube.local.player

/**
 * Decides whether a playing sentence owns voice output after the video playhead crosses another
 * subtitle cue. Live System TTS completion is callback-driven; PCM overruns use the final-buffer
 * marker with playback-head polling as a fallback.
 */
internal object DubbingOverrunPolicy {
    fun shouldHold(
        currentLineIndex: Int,
        nextLineIndex: Int,
        liveUtteranceActive: Boolean,
        pcmOverrunActive: Boolean,
        currentAudioFinished: Boolean,
    ): Boolean {
        if (currentLineIndex < 0 || currentLineIndex == nextLineIndex) return false
        if (liveUtteranceActive) return true
        return pcmOverrunActive && !currentAudioFinished
    }
}
