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
    val startedAtElapsedRealtimeMs: Long = System.nanoTime() / 1_000_000L,
) {
    @Volatile private var stageSnapshot: Map<String, String> = emptyMap()

    fun updateSnapshot(fields: Map<String, String>) {
        stageSnapshot = fields.toMap()
    }

    fun diagnosticFields(): Map<String, String> = buildMap {
        put("操作关联 ID", operationId)
        put("视频来源", mediaSource)
        put("视频会话指纹", videoSessionFingerprint)
        put("处理代数", pipelineGeneration.toString())
        val elapsedMs = (System.nanoTime() / 1_000_000L - startedAtElapsedRealtimeMs).coerceAtLeast(0L)
        put("本轮处理已耗时 ms", elapsedMs.toString())
        putAll(stageSnapshot)
    }
}

internal object AppLogOperationContextHolder {
    val current: ThreadLocal<AppLogOperationContext?> = ThreadLocal()
}
