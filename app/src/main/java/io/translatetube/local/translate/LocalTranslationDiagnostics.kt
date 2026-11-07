package io.translatetube.local.translate

import android.content.Context
import android.os.SystemClock
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException

/**
 * Records local translation workload and runtime conditions without storing subtitle text.
 * It observes the current device recommendation; engines with a fixed loaded session should
 * log their actual session thread count separately.
 */
internal object LocalTranslationDiagnostics {
    private const val MEGABYTE = 1024L * 1024L

    suspend fun run(
        context: Context,
        engine: String,
        sourceLanguage: String,
        targetLanguage: String,
        lines: List<String>,
        operation: suspend () -> List<String>,
    ): List<String> {
        val appContext = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val memory = runCatching { DevicePerformanceManager.snapshot(appContext) }.getOrNull()
        val playbackActive = runCatching {
            DevicePerformanceManager.isPlaybackLoadActive()
        }.getOrDefault(false)
        val recommendedThreads = runCatching {
            LocalTranslationRuntimePolicy.inferenceThreads(appContext)
        }.getOrNull()
        val inputCueCount = lines.count { it.isNotBlank() }
        val inputCharacters = lines.filter { it.isNotBlank() }.sumOf { it.length }

        fun metrics(output: List<String>? = null): Map<String, String> {
            val missingRows = if (output == null) {
                inputCueCount
            } else {
                lines.indices.count { index ->
                    lines[index].isNotBlank() && output.getOrNull(index).isNullOrBlank()
                }
            }
            return mapOf(
                "引擎" to engine,
                "源语言" to sourceLanguage,
                "目标语言" to targetLanguage,
                "输入字幕条数" to lines.size.toString(),
                "非空字幕条数" to inputCueCount.toString(),
                "输入总字符数" to inputCharacters.toString(),
                "输出字幕条数" to (output?.size?.toString() ?: "未完成"),
                "未返回译文条数" to missingRows.toString(),
                "采样可用内存MB" to (memory?.availableMemoryBytes?.div(MEGABYTE)?.toString() ?: "不可用"),
                "播放负载活跃" to playbackActive.toString(),
                "当前设备推荐推理线程数" to (recommendedThreads?.toString() ?: "不可用"),
                "耗时毫秒" to (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L).toString(),
                "未记录字幕原文" to "true",
            )
        }

        return try {
            val output = operation()
            AppLogStore.info(
                appContext,
                "Translation",
                "本地翻译任务完成",
                diagnosticContext = metrics(output),
            )
            output
        } catch (cancelled: CancellationException) {
            AppLogStore.info(
                appContext,
                "Translation",
                "本地翻译任务已取消",
                diagnosticContext = metrics(),
            )
            throw cancelled
        } catch (error: Exception) {
            AppLogStore.error(
                appContext,
                "Translation",
                "本地翻译任务失败",
                error,
                diagnosticContext = metrics(),
            )
            throw error
        }
    }
}
