package io.translatetube.local.translate

import android.app.ActivityManager
import android.content.Context
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
    private data class Loaded(val source: String, val identity: String, val handle: Long)

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
                while (batchStartIndex < nonBlankEntries.size) {
                    currentCoroutineContext().ensureActive()
                    // Re-sample available memory before each batch so later batches can shrink
                    // when other apps or the video player increase device memory pressure.
                    val limits = resolveBatchLimits(context.applicationContext)
                    val batchEndIndex = OpusMtBatchPolicy.nextBatchEndIndex(
                        lineLengths,
                        batchStartIndex,
                        limits,
                    )
                    val batchEntries = nonBlankEntries.subList(batchStartIndex, batchEndIndex)
                    val batch = batchEntries.map { it.value }
                    val input = batch.map { it.toByteArray(StandardCharsets.UTF_8) }.toTypedArray()

                    var batchError: Exception? = null
                    val batchResult: Array<ByteArray>? = try {
                        val result = OpusMtCt2Native.nativeTranslate(engine.handle, input)
                        currentCoroutineContext().ensureActive()
                        if (result.size != batch.size) {
                            throw IOException(
                                "CTranslate2 返回的字幕条数不匹配：预期 ${batch.size}，实际 ${result.size}"
                            )
                        }
                        result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        batchError = error
                        null
                    }

                    val failedRows = ArrayList<Int>()
                    var retriedEmptyRows = 0
                    for ((offset, line) in batch.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val originalRowNumber = batchEntries[offset].index + 1
                        val batchText = batchResult
                            ?.getOrNull(offset)
                            ?.let { String(it, StandardCharsets.UTF_8).trim() }
                            .orEmpty()

                        val translatedText = if (batchText.isNotBlank()) {
                            batchText
                        } else {
                            if (batchResult != null) retriedEmptyRows++
                            try {
                                val one = OpusMtCt2Native.nativeTranslate(
                                    engine.handle,
                                    arrayOf(line.toByteArray(StandardCharsets.UTF_8)),
                                )
                                currentCoroutineContext().ensureActive()
                                if (one.size != 1) {
                                    throw IOException("CTranslate2 单条重试返回了 ${one.size} 条结果")
                                }
                                String(one.single(), StandardCharsets.UTF_8).trim().also { value ->
                                    if (value.isBlank()) {
                                        throw IOException("CTranslate2 单条重试返回空译文")
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (rowError: Exception) {
                                failedRows += originalRowNumber
                                ""
                            }
                        }
                        output[batchEntries[offset].index] = translatedText
                    }

                    if (batchError != null || retriedEmptyRows > 0 || failedRows.isNotEmpty()) {
                        val diagnosticError = batchError
                            ?: IllegalStateException("CTranslate2 批量结果包含空译文，已逐条恢复")
                        AppLogStore.warning(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT CTranslate2 已执行逐条恢复",
                            diagnosticError,
                            diagnosticContext = mapOf(
                                "源语言" to source,
                                "目标语言" to "zh-CN",
                                "批次起始字幕序号" to (batchEntries.first().index + 1).toString(),
                                "批次字幕数量" to batch.size.toString(),
                                "空译文逐条重试数量" to retriedEmptyRows.toString(),
                                "逐条恢复失败字幕序号" to failedRows.joinToString(","),
                                "逐条恢复失败数量" to failedRows.size.toString(),
                                "未记录字幕原文" to "true",
                            ),
                        )
                    }
                    // Failed cues stay blank in their original positions. The playback layer can
                    // retry just those rows without losing successful translations in this batch.
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

    private fun resolveBatchLimits(context: Context): OpusMtBatchPolicy.Limits {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return OpusMtBatchPolicy.resolve(0L)
        val memoryInfo = ActivityManager.MemoryInfo()
        val availableBytes = runCatching {
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem
        }.getOrDefault(0L)
        return OpusMtBatchPolicy.resolve(availableBytes)
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

        var availableMemoryBytes = 0L
        var systemLowMemory = false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            availableMemoryBytes = memoryInfo.availMem
            systemLowMemory = memoryInfo.lowMemory
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
        val inferenceThreads = LocalTranslationRuntimePolicy.inferenceThreads(
            Runtime.getRuntime().availableProcessors(),
            availableMemoryBytes,
            systemLowMemory,
        )

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
        return Loaded(source, identity, handle).also { loaded = it }
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
