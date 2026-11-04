package io.translatetube.local.util

/**
 * Correlates all diagnostic events emitted during one video-processing run.
 * The context is carried by Kotlin coroutines and is never persisted as global mutable state.
 */
internal data class AppLogOperationContext(
    val operationId: String,
    val mediaSource: String,
    val videoSessionFingerprint: String,
    val pipelineGeneration: Long,
)

internal object AppLogOperationContextHolder {
    val current: ThreadLocal<AppLogOperationContext?> = ThreadLocal()
}
