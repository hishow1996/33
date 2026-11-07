package io.translatetube.local.translate

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.performance.DevicePerformancePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.translatetube.local.util.AppLogStore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Native CTranslate2 runtime for additional quantized OPUS-MT pairs; keeps one model resident. */
internal object OpusMtCt2Runtime {
    private data class Loaded(
        val source: String,
        val identity: String,
        val handle: Long,
        val inferenceThreads: Int,
    )

    private data class BatchRuntimeSnapshot(
        val limits: OpusMtBatchPolicy.Limits,
        val availableMemoryBytes: Long,
        val playbackActive: Boolean,
    )

    private data class BatchRecoveryStats(
        var nativeCalls: Int = 0,
        var splitRetries: Int = 0,
        var singleCueRetries: Int = 0,
        var maxSplitDepth: Int = 0,
        var firstError: Exception? = null,
        val failedRows: MutableList<Int> = mutableListOf(),
    )

    private val lock = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeLeases = SharedRuntimeLeaseCounter()
    @Volatile private var loaded: Loaded? = null

    fun acquireLease() {
        runtimeLeases.acquire()
    }

    fun releaseLease() {
        if (!runtimeLeases.release()) return
        closeScope.launch { release() }
    }

    suspend fun translate(context: Context, source: String, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val engine = loadLocked(context.applicationContext, source)
                // Keep original positions, but never pass blank subtitle cues into native inference.
                val output = MutableList(lines.size) { "" }
                val nonBlankEntries = lines.withIndex().filter { it.value.isNotBlank() }
                val lineLengths = nonBlankEntries.map { it.value.length }
                var batchStartIndex = 0
                var batchNumber = 0

                suspend fun translateSegment(
                    entries: List<IndexedValue<String>>,
                    depth: Int,
                    stats: BatchRecoveryStats,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (entries.isEmpty()) return
                    stats.nativeCalls += 1
                    val input = entries.map { it.value.toByteArray(StandardCharsets.UTF_8) }.toTypedArray()
                    val result = try {
                        OpusMtCt2Native.nativeTranslate(engine.handle, input).also { translated ->
                            currentCoroutineContext().ensureActive()
                            if (translated.size != entries.size) {
                                throw IOException(
                                    "CTranslate2 返回的字幕条数不匹配：预期 ${entries.size}，实际 ${translated.size}"
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (stats.firstError == null) stats.firstError = error
                        val splitAt = OpusMtBatchPolicy.splitBatch(entries.size)
                        if (splitAt != null && OpusMtBatchPolicy.shouldSplitAfterFailure(entries.size, error)) {
                            stats.splitRetries += 1
                            stats.maxSplitDepth = maxOf(stats.maxSplitDepth, depth + 1)
                            // Retry smaller contiguous halves. Each successful half is kept even
                            // when the other half still contains one malformed or expensive cue.
                            translateSegment(entries.subList(0, splitAt), depth + 1, stats)
                            translateSegment(entries.subList(splitAt, entries.size), depth + 1, stats)
                        } else {
                            entries.forEach { entry -> stats.failedRows += entry.index + 1 }
                        }
                        return
                    }

                    for ((offset, entry) in entries.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val translatedText = String(result[offset], StandardCharsets.UTF_8).trim()
                        if (translatedText.isNotBlank()) {
                            output[entry.index] = translatedText
                        } else if (entries.size > 1) {
                            // A valid batch response can still contain an empty hypothesis for
                            // one cue. Retry only that cue rather than repeating good rows.
                            stats.singleCueRetries += 1
                            translateSegment(listOf(entry), depth + 1, stats)
                        } else {
                            stats.failedRows += entry.index + 1
                            if (stats.firstError == null) {
                                stats.firstError = IOException("CTranslate2 单条推理返回空译文")
                            }
                        }
                    }
                }

                while (batchStartIndex < nonBlankEntries.size) {
                    currentCoroutineContext().ensureActive()
                    batchNumber += 1
                    // Re-sample memory for every top-level batch so later batches can shrink
                    // when other apps or the video player increase device memory pressure.
                    val runtimeSnapshot = resolveBatchRuntimeSnapshot(context.applicationContext)
                    val limits = runtimeSnapshot.limits
                    val batchEndIndex = OpusMtBatchPolicy.nextBatchEndIndex(
                        lineLengths,
                        batchStartIndex,
                        limits,
                    )
                    val batchEntries = nonBlankEntries.subList(batchStartIndex, batchEndIndex)
                    val batch = batchEntries.map { it.value }
                    val totalCharacters = batch.sumOf { it.length }
                    val maxLineCharacters = batch.maxOfOrNull { it.length } ?: 0
                    val stats = BatchRecoveryStats()
                    val startedAt = SystemClock.elapsedRealtime()

                    translateSegment(batchEntries, depth = 0, stats = stats)

                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                    val firstRowNumber = batchEntries.first().index + 1
                    val diagnosticContext = mapOf(
                        "批次序号" to batchNumber.toString(),
                        "批次起始字幕序号" to firstRowNumber.toString(),
                        "批次字幕数量" to batch.size.toString(),
                        "批次总字符数" to totalCharacters.toString(),
                        "单行最大字符数" to maxLineCharacters.toString(),
                        "采样可用内存MB" to (runtimeSnapshot.availableMemoryBytes / (1024L * 1024L)).toString(),
                        "内存分档最大字幕数" to limits.maxExamples.toString(),
                        "内存分档字符上限" to limits.maxCharacters.toString(),
                        "播放负载活跃" to runtimeSnapshot.playbackActive.toString(),
                        "模型加载时推理线程数" to engine.inferenceThreads.toString(),
                        "CTranslate2 原生调用次数" to stats.nativeCalls.toString(),
                        "二分缩小次数" to stats.splitRetries.toString(),
                        "空结果单条重试次数" to stats.singleCueRetries.toString(),
                        "最大拆分深度" to stats.maxSplitDepth.toString(),
                        "最终失败字幕序号" to stats.failedRows.distinct().sorted().joinToString(","),
                        "最终失败数量" to stats.failedRows.distinct().size.toString(),
                        "耗时毫秒" to elapsedMs.toString(),
                        "未记录字幕原文" to "true",
                    )

                    if (stats.splitRetries > 0 || stats.singleCueRetries > 0 || stats.failedRows.isNotEmpty()) {
                        val recoveryPath = when {
                            stats.failedRows.isNotEmpty() && stats.splitRetries > 0 -> "二分缩小后仍隔离失败行"
                            stats.splitRetries > 0 -> "二分缩小后恢复"
                            stats.singleCueRetries > 0 -> "仅对空结果逐条重试"
                            else -> "无"
                        }
                        AppLogStore.warning(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT CTranslate2 批次已执行恢复",
                            stats.firstError ?: IOException("CTranslate2 批次中存在空译文"),
                            diagnosticContext = diagnosticContext + mapOf(
                                "恢复路径" to recoveryPath,
                                "错误分类" to (stats.firstError?.javaClass?.simpleName ?: "空译文"),
                            ),
                        )
                    }
                    AppLogStore.info(
                        context.applicationContext,
                        "Translation",
                        "OPUS-MT CTranslate2 批次处理完成",
                        diagnosticContext = diagnosticContext,
                    )

                    // Failed cues stay blank at their original positions. The playback layer can
                    // retry only those rows without losing successful translations in this batch.
                    batchStartIndex = batchEndIndex
                }
                output
            }
        }
    }
    suspend fun validate(context: Context, source: String, sampleText: String) {
        val translated = try {
            translate(context, source, listOf(sampleText)).singleOrNull().orEmpty()
        } finally {
            release(source)
        }
        val targetLanguage = OpusMtAdditionalModelManager.targetLanguage(source)
        val validOutput = when (targetLanguage) {
            "zh" -> translated.any { it in '\u4E00'..'\u9FFF' }
            "en" -> translated.any { it in 'A'..'Z' || it in 'a'..'z' }
            else -> translated.isNotBlank()
        }
        if (translated.isBlank() || !validOutput) {
            val target = if (targetLanguage == "en") "英语" else "中文"
            throw IllegalStateException("OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 模型已下载，但试译未生成有效${target}文本，不能标记为可用。")
        }
    }

    suspend fun release(source: String? = null) = lock.withLock {
        runtimeLeases.closeIfUnused {
            val current = loaded
            if (current != null && (source == null || current.source == source)) {
                if (OpusMtCt2Native.isAvailable) {
                    runCatching { OpusMtCt2Native.nativeFree(current.handle) }
                }
                loaded = null
            }
        }
        Unit
    }

    private fun resolveBatchRuntimeSnapshot(context: Context): BatchRuntimeSnapshot {
        val device = DevicePerformanceManager.snapshot(context)
        val playbackActive = DevicePerformanceManager.isPlaybackLoadActive()
        return BatchRuntimeSnapshot(
            limits = OpusMtBatchPolicy.resolve(
                availableMemoryBytes = device.availableMemoryBytes,
                deviceMaxExamples = DevicePerformancePolicy.translationBatchMaxExamples(device, playbackActive),
                deviceMaxCharacters = DevicePerformancePolicy.translationBatchMaxCharacters(device, playbackActive),
            ),
            availableMemoryBytes = device.availableMemoryBytes,
            playbackActive = playbackActive,
        )
    }

    private fun loadLocked(context: Context, source: String): Loaded {
        if (!OpusMtCt2Native.isAvailable) {
            throw IOException(
                "当前运行环境未包含可用的 CTranslate2 原生库 (libopusmtct2.so)。" +
                    OpusMtCt2Native.loadError.takeIf { it.isNotBlank() }?.let { " 加载原因：$it" }.orEmpty()
            )
        }
        val dir = OpusMtAdditionalModelManager.modelDirectory(context, source)
        val required = listOf("config.json", "model.bin", "shared_vocabulary.json", "source.spm", "target.spm").map { File(dir, it) }
        val missing = required.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) throw IOException("OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 模型文件不完整：${missing.joinToString { it.name }}")
        val identity = required.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        loaded?.takeIf { it.source == source && it.identity == identity }?.let { return it }

        // Drop the previous language pair before checking headroom for the replacement.
        loaded?.let { old -> runCatching { OpusMtCt2Native.nativeFree(old.handle) } }
        loaded = null

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val requiredBytes = LocalTranslationMemoryPolicy.requiredAvailableBytes(memoryInfo.totalMem)
            if (!LocalTranslationMemoryPolicy.hasEnoughAvailableMemory(memoryInfo.availMem, memoryInfo.totalMem)) {
                val availableMb = memoryInfo.availMem / LocalTranslationMemoryPolicy.MEGABYTE
                val requiredMb = requiredBytes / LocalTranslationMemoryPolicy.MEGABYTE
                throw IllegalStateException(
                    "OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 加载需要更多运行内存：" +
                        "当前约 ${availableMb} MB，可用内存建议至少 ${requiredMb} MB。" +
                        "请关闭其他应用后重试，已下载模型无需重新下载。"
                )
            }
        }
        val inferenceThreads = LocalTranslationRuntimePolicy.inferenceThreads(context)

        val handle = try {
            OpusMtCt2Native.nativeLoad(
                dir.absolutePath,
                File(dir, "source.spm").absolutePath,
                File(dir, "target.spm").absolutePath,
                inferenceThreads,
            )
        } catch (error: Throwable) {
            val detail = if (error is OutOfMemoryError) {
                "当前设备可用运行内存不足，请关闭其他应用后重试"
            } else error.message ?: error.javaClass.simpleName
            throw IllegalStateException(
                "无法加载 OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 的 CTranslate2 运行时：$detail",
                error,
            )
        }
        if (handle == 0L) throw IOException("CTranslate2 未返回有效的模型句柄")
        return Loaded(source, identity, handle, inferenceThreads).also { loaded = it }
    }
}

internal object OpusMtCt2Native {
    private val loadFailure: Throwable? = try {
        System.loadLibrary("opusmtct2")
        null
    } catch (error: Throwable) {
        error
    }

    val isAvailable: Boolean
        get() = loadFailure == null

    val loadError: String
        get() = loadFailure?.let { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }.orEmpty()

    external fun nativeLoad(modelDirectory: String, sourceSentencePiecePath: String, targetSentencePiecePath: String, threads: Int): Long
    external fun nativeFree(handle: Long)
    /** Text crosses JNI as UTF-8 byte arrays so non-ASCII subtitles are preserved. */
    external fun nativeTranslate(handle: Long, texts: Array<ByteArray>): Array<ByteArray>
}
