package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.performance.DevicePerformancePolicy

/** Shares the device CPU budget with active video playback across local translation runtimes. */
internal object LocalTranslationRuntimePolicy {
    private const val MB = 1024L * 1024L
    private const val LOW_MEMORY_THREAD_LIMIT_BYTES = 1024L * MB

    /**
     * Prefer one inference thread on small CPUs or when the system is under memory pressure.
     * Two threads are used only when the device has at least four logical processors and
     * Android reports at least 1 GB available memory without its low-memory warning.
     */
    fun inferenceThreads(context: Context): Int =
        DevicePerformancePolicy.recommendedLocalInferenceThreads(
            DevicePerformanceManager.snapshot(context),
            DevicePerformanceManager.isPlaybackLoadActive(),
        )

    fun inferenceThreads(
        availableProcessors: Int,
        availableMemoryBytes: Long,
        systemLowMemory: Boolean,
    ): Int {
        val cores = availableProcessors.coerceAtLeast(1)
        val available = availableMemoryBytes.coerceAtLeast(0L)
        return if (
            cores < 4 ||
            systemLowMemory ||
            available < LOW_MEMORY_THREAD_LIMIT_BYTES
        ) 1 else 2
    }
}
