package io.translatetube.local.player

/**
 * A PCM read may publish only while both the playback request and synthesis configuration
 * still match the generation captured when that read started.
 */
internal object DubbingCacheReadGenerationPolicy {
    fun canContinue(
        expectedGeneration: Int,
        currentGeneration: Int,
        playbackIsCurrent: Boolean,
    ): Boolean = playbackIsCurrent && expectedGeneration == currentGeneration
}
