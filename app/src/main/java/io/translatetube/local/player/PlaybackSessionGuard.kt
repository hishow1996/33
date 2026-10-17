package io.translatetube.local.player

/**
 * Rejects results belonging to an obsolete player/UI session.
 * Kept Android-free so the session transition policy can be checked with JVM unit tests.
 */
internal object PlaybackSessionGuard {
    fun isCurrent(
        sessionKey: String,
        activeVideoKey: String,
        activeUiKey: String,
        expectedPipelineGeneration: Long? = null,
        activePipelineGeneration: Long? = null,
        expectedDubbingMutationGeneration: Long? = null,
        activeDubbingMutationGeneration: Long? = null,
    ): Boolean =
        sessionKey == activeVideoKey &&
            sessionKey == activeUiKey &&
            (expectedPipelineGeneration == null ||
                expectedPipelineGeneration == activePipelineGeneration) &&
            (expectedDubbingMutationGeneration == null ||
                expectedDubbingMutationGeneration == activeDubbingMutationGeneration)

    fun ensureCurrent(
        sessionKey: String,
        activeVideoKey: String,
        activeUiKey: String,
        expectedPipelineGeneration: Long? = null,
        activePipelineGeneration: Long? = null,
        expectedDubbingMutationGeneration: Long? = null,
        activeDubbingMutationGeneration: Long? = null,
    ) {
        if (!isCurrent(
                sessionKey = sessionKey,
                activeVideoKey = activeVideoKey,
                activeUiKey = activeUiKey,
                expectedPipelineGeneration = expectedPipelineGeneration,
                activePipelineGeneration = activePipelineGeneration,
                expectedDubbingMutationGeneration = expectedDubbingMutationGeneration,
                activeDubbingMutationGeneration = activeDubbingMutationGeneration,
            )
        ) {
            throw kotlinx.coroutines.CancellationException(
                "视频处理任务或配音引擎任务已被新的请求替代，取消旧任务",
            )
        }
    }
}
