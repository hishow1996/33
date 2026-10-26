package io.translatetube.local.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
                    val input = batch.map { it.toByteArray(StandardCharsets.UTF_8) }.toTypedArray()
                    val translated = try {
                        OpusMtCt2Native.nativeTranslate(engine.handle, input)
                    } catch (error: Throwable) {
                        throw IllegalStateException(
                            "OPUS-MT ${OpusMtAdditionalModelManager.label(source)} CTranslate2 推理失败：${error.message ?: error.javaClass.simpleName}",
                            error,
                        )
                    }
                    if (translated.size != batch.size) {
                        throw IOException("CTranslate2 返回的字幕条数不匹配：预期 ${batch.size}，实际 ${translated.size}")
                    }
                    translated.forEach { bytes -> output += String(bytes, StandardCharsets.UTF_8).trim() }
                }
                output
            }
        }
    }

    suspend fun validate(context: Context, source: String, sampleText: String) {
        val translated = translate(context, source, listOf(sampleText)).singleOrNull().orEmpty()
        if (translated.isBlank() || translated.none { it in '\u4E00'..'\u9FFF' }) {
            release(source)
            throw IllegalStateException("OPUS-MT ${OpusMtAdditionalModelManager.label(source)} 模型已下载，但试译未生成中文，不能标记为可用。")
        }
        release(source)
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
