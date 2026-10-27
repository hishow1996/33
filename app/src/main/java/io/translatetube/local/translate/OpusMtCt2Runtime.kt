package io.translatetube.local.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
    @Volatile private var loaded: Loaded? = null

    suspend fun translate(context: Context, source: String, lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val engine = loadLocked(context.applicationContext, source)
                val output = ArrayList<String>(lines.size)
                for (batch in lines.chunked(8)) {
                    currentCoroutineContext().ensureActive()
                    val batchStartIndex = output.size
                    val input = batch.map { it.toByteArray(StandardCharsets.UTF_8) }.toTypedArray()
                    val translated = try {
                        val result = OpusMtCt2Native.nativeTranslate(engine.handle, input)
                        if (result.size != batch.size) {
                            throw IOException("CTranslate2 返回的字幕条数不匹配：预期 ${batch.size}，实际 ${result.size}")
                        }
                        result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (batchError: Exception) {
                        // Retry this chunk row-by-row. A single problematic cue should not discard
                        // successful neighbours, while a completely failed batch remains an error.
                        val recovered = ArrayList<ByteArray>(batch.size)
                        val failedRows = ArrayList<Int>()
                        for ((offset, line) in batch.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val rowResult = try {
                                val one = OpusMtCt2Native.nativeTranslate(
                                    engine.handle,
                                    arrayOf(line.toByteArray(StandardCharsets.UTF_8)),
                                )
                                if (one.size != 1) {
                                    throw IOException("CTranslate2 单条重试返回了 ${one.size} 条结果")
                                }
                                one.single()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (rowError: Exception) {
                                failedRows += batchStartIndex + offset + 1
                                ByteArray(0)
                            }
                            recovered += rowResult
                        }
                        if (failedRows.size == batch.size) {
                            throw IllegalStateException(
                                "OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 批处理失败，逐条恢复后仍无有效结果。",
                                batchError,
                            )
                        }
                        AppLogStore.warning(
                            context.applicationContext,
                            "Translation",
                            "OPUS-MT CTranslate2 批处理失败，已逐条恢复可用字幕",
                            batchError,
                            diagnosticContext = mapOf(
                                "源语言" to source,
                                "目标语言" to "zh-CN",
                                "批次起始字幕序号" to (batchStartIndex + 1).toString(),
                                "批次字幕数量" to batch.size.toString(),
                                "逐条恢复成功数量" to (batch.size - failedRows.size).toString(),
                                "逐条恢复失败字幕序号" to failedRows.joinToString(","),
                                "未记录字幕原文" to "true",
                            ),
                        )
                        recovered.toTypedArray()
                    }
                    translated.forEach { bytes -> output += String(bytes, StandardCharsets.UTF_8).trim() }
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
        val current = loaded ?: return@withLock
        if (source != null && current.source != source) return@withLock
        runCatching { OpusMtCt2Native.nativeFree(current.handle) }
        loaded = null
    }

    private fun loadLocked(context: Context, source: String): Loaded {
        val dir = OpusMtAdditionalModelManager.modelDirectory(context, source)
        val required = listOf("config.json", "model.bin", "shared_vocabulary.json", "source.spm", "target.spm").map { File(dir, it) }
        val missing = required.filterNot { it.isFile && it.length() > 0L }
        if (missing.isNotEmpty()) throw IOException("OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 模型文件不完整：${missing.joinToString { it.name }}")
        val identity = required.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        loaded?.takeIf { it.source == source && it.identity == identity }?.let { return it }

        loaded?.let { old -> runCatching { OpusMtCt2Native.nativeFree(old.handle) } }
        loaded = null
        val handle = try {
            OpusMtCt2Native.nativeLoad(dir.absolutePath, File(dir, "source.spm").absolutePath, File(dir, "target.spm").absolutePath, 2)
        } catch (error: Throwable) {
            throw IllegalStateException("无法加载 OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 的 CTranslate2 运行时：${error.message ?: error.javaClass.simpleName}", error)
        }
        if (handle == 0L) throw IOException("CTranslate2 未返回有效的模型句柄")
        return Loaded(source, identity, handle).also { loaded = it }
    }
}

internal object OpusMtCt2Native {
    init { System.loadLibrary("opusmtct2") }

    external fun nativeLoad(modelDirectory: String, sourceSentencePiecePath: String, targetSentencePiecePath: String, threads: Int): Long
    external fun nativeFree(handle: Long)
    /** Text crosses JNI as UTF-8 byte arrays so non-ASCII subtitles are preserved. */
    external fun nativeTranslate(handle: Long, texts: Array<ByteArray>): Array<ByteArray>
}
