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
}
