package io.translatetube.local.player

/**
 * Rejects results belonging to an obsolete player/UI session.
 * Kept Android-free so the session transition policy can be checked with JVM unit tests.
 */
internal object PlaybackSessionGuard {
    fun ensureCurrent(
        sessionKey: String,
        activeVideoKey: String,
        activeUiKey: String,
    ) {
        if (sessionKey != activeVideoKey || sessionKey != activeUiKey) {
            throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频处理任务")
        }
    }
}
