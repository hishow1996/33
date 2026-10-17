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
        expectedPipelineGeneration: Long? = null,
        activePipelineGeneration: Long? = null,
    ) {
        if (
            sessionKey != activeVideoKey ||
            sessionKey != activeUiKey ||
            (expectedPipelineGeneration != null &&
                expectedPipelineGeneration != activePipelineGeneration)
        ) {
            throw kotlinx.coroutines.CancellationException(
                "视频处理任务已被新的打开请求替代，取消旧任务",
            )
        }
    }
}
