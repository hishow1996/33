package io.translatetube.local.player

/**
 * A forced YouTube subtitle-mode refresh must re-discover the preferred caption track.
 * Even a historical snapshot for the newly selected mode must not short-circuit that refresh.
 */
internal object ReadySessionSnapshotReusePolicy {
    fun canReuseExactSnapshot(
        canRestoreStoredResult: Boolean,
        forceYouTubeSubtitleRefresh: Boolean,
    ): Boolean = canRestoreStoredResult && !forceYouTubeSubtitleRefresh
}
