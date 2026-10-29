package io.translatetube.local.player

/** Ignore playback-start callbacks delivered after their owner, line or play generation changed. */
internal object DubbingPlaybackStartedPolicy {
    fun shouldApply(
        dubbingEnabled: Boolean,
        engineIsCurrent: Boolean,
        startedLineStillCurrent: Boolean,
    ): Boolean = dubbingEnabled && engineIsCurrent && startedLineStillCurrent
}
