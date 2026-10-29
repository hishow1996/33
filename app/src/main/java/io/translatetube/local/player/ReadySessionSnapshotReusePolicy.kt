package io.translatetube.local.player

/**
 * Cache reuse rules shared by normal re-entry and engine-preference rollback.
 *
 * Exact historical results are safe to restore only when the current configuration fingerprint
 * matches. A forced YouTube subtitle-mode refresh is the exception: it must rediscover captions
 * even when a matching snapshot from that mode happens to exist.
 */
internal object ReadySessionSnapshotReusePolicy {
    fun canReuseExactSnapshot(
        canRestoreStoredResult: Boolean,
        forceYouTubeSubtitleRefresh: Boolean,
    ): Boolean = canRestoreStoredResult && !forceYouTubeSubtitleRefresh

    /** Return to an already-successful engine combination without re-running the five stages. */
    fun canRestoreMatchingHistoricalSnapshot(
        matchingSuccessfulSnapshot: Boolean,
        forceYouTubeSubtitleRefresh: Boolean,
    ): Boolean = matchingSuccessfulSnapshot && !forceYouTubeSubtitleRefresh

    /** A comparable latest success with no affected stage can be restored as a complete result. */
    fun canRestoreComparableFallbackSnapshot(
        fallbackSnapshotAvailable: Boolean,
        fallbackConfigComparable: Boolean,
        fallbackRestartStage: EngineRestartStage?,
    ): Boolean =
        fallbackSnapshotAvailable && fallbackConfigComparable && fallbackRestartStage == null

    /**
     * A global preference fingerprint can change even when the selected flow does not consume that
     * setting (for example, translation settings with already-translated imported subtitles).
     * Only relevant restart stages should force regeneration when a comparable snapshot exists.
     */
    fun shouldRegenerateDubbingForFallback(
        forceRegenerateDubbing: Boolean,
        restoreReadySnapshot: Boolean,
        hasReusableSnapshot: Boolean,
        fallbackSnapshotAvailable: Boolean,
        fallbackConfigComparable: Boolean,
        fallbackRestartStage: EngineRestartStage?,
        engineConfigChangedSinceLastSuccess: Boolean,
    ): Boolean {
        if (forceRegenerateDubbing) return true
        if (restoreReadySnapshot || hasReusableSnapshot) return false
        return fallbackRestartStage != null ||
            (fallbackSnapshotAvailable && !fallbackConfigComparable) ||
            (!fallbackSnapshotAvailable && engineConfigChangedSinceLastSuccess)
    }

    /**
     * Keep the source-processing path that produced the active result.
     * Two flows can share the same video/config fingerprint while having different subtitle inputs.
     */
    fun isSnapshotFlowCompatible(
        snapshotFlow: ProcessingFlow,
        snapshotIgnoresTranscription: Boolean,
        expectedFlows: Set<ProcessingFlow>?,
        isYouTube: Boolean,
    ): Boolean {
        if (expectedFlows == null) return true
        if (snapshotFlow !in expectedFlows) return false
        val expectedIgnoreTranscription = EngineRestartStagePolicy.ignoresTranscriptionConfig(
            processingFlow = snapshotFlow,
            isYouTube = isYouTube,
        )
        return snapshotIgnoresTranscription == expectedIgnoreTranscription
    }
}
