package io.translatetube.local.translate

import android.content.Context
import io.translatetube.local.asr.AtomicFileInstaller
import io.translatetube.local.data.ResumableModelArchiveDownloader
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class HyMtStatus { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, PAUSED, READY, FAILED }

data class HyMtDownloadState(
    val status: HyMtStatus = HyMtStatus.NOT_DOWNLOADED,
    val progress: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = HyMtModelManager.DISPLAY_BYTES,
    val error: String = "",
) {
    val hasCompleteFiles: Boolean
        get() = totalBytes > 0L && downloadedBytes >= totalBytes
}

/** Official HY-MT1.5 source languages used by the Tencent model card. */
object HyMtLanguagePolicy {
    val languages: List<Pair<String, String>> = listOf(
        "en" to "英语", "fr" to "法语", "pt" to "葡萄牙语", "es" to "西班牙语",
        "ja" to "日语", "tr" to "土耳其语", "ru" to "俄语", "ar" to "阿拉伯语",
        "ko" to "韩语", "th" to "泰语", "it" to "意大利语", "de" to "德语",
        "vi" to "越南语", "ms" to "马来语", "id" to "印尼语", "tl" to "菲律宾语",
        "hi" to "印地语", "zh-Hant" to "繁体中文", "pl" to "波兰语", "cs" to "捷克语",
        "nl" to "荷兰语", "km" to "高棉语", "my" to "缅甸语", "fa" to "波斯语",
        "gu" to "古吉拉特语", "ur" to "乌尔都语", "te" to "泰卢固语", "mr" to "马拉地语",
        "he" to "希伯来语", "bn" to "孟加拉语", "ta" to "泰米尔语", "uk" to "乌克兰语",
        "bo" to "藏语", "kk" to "哈萨克语", "mn" to "蒙古语", "ug" to "维吾尔语",
        "yue" to "粤语",
    )

    private val supported = languages.mapTo(linkedSetOf()) { it.first.lowercase(Locale.ROOT) }

    fun canonicalCode(languageTag: String): String {
        val normalized = languageTag.trim().replace('_', '-')
        if (normalized.equals("zh-hant", ignoreCase = true) ||
            normalized.startsWith("zh-hant-", ignoreCase = true) ||
            normalized.equals("zh-tw", ignoreCase = true) ||
            normalized.equals("zh-hk", ignoreCase = true)
        ) return "zh-Hant"
        if (normalized.equals("yue", ignoreCase = true) ||
            normalized.startsWith("yue-", ignoreCase = true)
        ) return "yue"
        return SourceLanguagePolicy.canonicalPrimaryCode(normalized)
    }

    fun supportsSource(languageTag: String): Boolean {
        if (SourceLanguagePolicy.isUndetermined(languageTag)) return true
        return canonicalCode(languageTag).lowercase(Locale.ROOT) in supported
    }
}

/** A pinned GGUF model plus the Q2_0C llama.cpp runtime used to execute it. */
object HyMtModelManager {
    const val DISPLAY_BYTES = 601_000_000L
    private const val MAX_BYTES = 650_000_000L
    private const val MODEL_REVISION = "4b99bde"
    private const val VALIDATION_SCHEMA = 1
    private const val MODEL_NAME = "Hy-MT1.5-1.8B-2bit.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF/resolve/4b99bde/Hy-MT1.5-1.8B-2bit.gguf"

    private val lock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mutableState = MutableStateFlow(HyMtDownloadState())
    val state: StateFlow<HyMtDownloadState> = mutableState

    fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "models/translation/hymt")

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_NAME)

    private fun readyMarker(context: Context) = File(modelDirectory(context), ".validated")

    private fun signature(context: Context): String {
        val model = modelFile(context)
        return buildString {
            append(MODEL_REVISION).append(":Q2_0C:runtime-").append(VALIDATION_SCHEMA)
            append(':').append(model.length()).append(':').append(model.lastModified())
        }
    }

    private fun hasGgufHeader(file: File): Boolean {
        if (!file.isFile || file.length() !in 500_000_000L..MAX_BYTES) return false
        return runCatching {
            FileInputStream(file).use { input ->
                input.read() == 'G'.code &&
                    input.read() == 'G'.code &&
                    input.read() == 'U'.code &&
                    input.read() == 'F'.code
            }
        }.getOrDefault(false)
    }

    fun isReady(context: Context): Boolean {
        val model = modelFile(context)
        val marker = readyMarker(context)
        return hasGgufHeader(model) &&
            marker.isFile &&
            runCatching { marker.readText(Charsets.UTF_8).trim() == signature(context) }.getOrDefault(false)
    }

    fun hasPartialDownload(context: Context): Boolean {
        val model = modelFile(context)
        val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
        return part.isFile && part.length() > 0L || model.isFile && model.length() > 0L
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (lock.isLocked) return@withContext
        val model = modelFile(context)
        val marker = readyMarker(context)
        val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
        mutableState.value = when {
            isReady(context) -> HyMtDownloadState(
                status = HyMtStatus.READY,
                progress = 100,
                downloadedBytes = model.length(),
                totalBytes = model.length(),
            )
            hasGgufHeader(model) -> HyMtDownloadState(
                status = HyMtStatus.PAUSED,
                progress = 100,
                downloadedBytes = model.length(),
                totalBytes = model.length(),
                error = if (marker.isFile) "模型需要重新验证" else "模型文件已下载，等待运行时验证",
            )
            part.isFile && part.length() > 0L -> HyMtDownloadState(
                status = HyMtStatus.PAUSED,
                progress = (part.length() * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99),
                downloadedBytes = part.length(),
                totalBytes = DISPLAY_BYTES,
                error = "下载已暂停，可继续",
            )
            else -> HyMtDownloadState(status = HyMtStatus.NOT_DOWNLOADED)
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val directory = modelDirectory(app)
        val model = modelFile(app)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建 HY-MT1.5 模型目录")
        }
        if (isReady(app)) {
            mutableState.value = HyMtDownloadState(
                status = HyMtStatus.READY,
                progress = 100,
                downloadedBytes = model.length(),
                totalBytes = model.length(),
            )
            return@withLock
        }

        try {
            if (hasGgufHeader(model)) {
                mutableState.value = HyMtDownloadState(
                    status = HyMtStatus.VERIFYING,
                    progress = 100,
                    downloadedBytes = model.length(),
                    totalBytes = model.length(),
                )
                validateAndMark(app)
            } else {
                val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
                AppLogStore.info(
                    app,
                    "LocalTranslationModel",
                    "HY-MT1.5 2-bit 模型下载开始或继续",
                    diagnosticContext = mapOf(
                        "模型版本" to MODEL_REVISION,
                        "模型格式" to "GGUF / Q2_0C",
                        "断点续传" to (part.isFile && part.length() > 0L).toString(),
                        "模型目录" to directory.name,
                    ),
                )
                mutableState.value = HyMtDownloadState(
                    status = HyMtStatus.DOWNLOADING,
                    progress = 0,
                    downloadedBytes = part.length(),
                    totalBytes = DISPLAY_BYTES,
                )
                val result = ResumableModelArchiveDownloader.download(
                    client = client,
                    url = MODEL_URL,
                    partFile = part,
                    maxBytes = MAX_BYTES,
                    expectedSha256 = null,
                    onProgress = { downloaded, total ->
                        val effectiveTotal = total.takeIf { it > 0L } ?: DISPLAY_BYTES
                        mutableState.value = HyMtDownloadState(
                            status = HyMtStatus.DOWNLOADING,
                            progress = (downloaded * 100L / effectiveTotal).toInt().coerceIn(0, 99),
                            downloadedBytes = downloaded,
                            totalBytes = effectiveTotal,
                        )
                    },
                    checkActive = { currentCoroutineContext().ensureActive() },
                )
                if (result.downloadedBytes !in 500_000_000L..MAX_BYTES ||
                    !hasGgufHeader(part)
                ) {
                    throw IOException("下载文件大小或 GGUF 文件头校验失败")
                }
                AtomicFileInstaller.install(part, model)
                ResumableModelArchiveDownloader.clearResumeMetadata(part)
                ResumableModelArchiveDownloader.cleanupPartialFiles(model)
                mutableState.value = HyMtDownloadState(
                    status = HyMtStatus.VERIFYING,
                    progress = 100,
                    downloadedBytes = model.length(),
                    totalBytes = model.length(),
                )
                validateAndMark(app)
            }
            if (!isReady(app)) throw IOException("HY-MT1.5 模型运行时验证标记无效")
            mutableState.value = HyMtDownloadState(
                status = HyMtStatus.READY,
                progress = 100,
                downloadedBytes = model.length(),
                totalBytes = model.length(),
            )
            AppLogStore.info(
                app,
                "LocalTranslationModel",
                "HY-MT1.5 2-bit 模型下载及本地试译验证成功",
                diagnosticContext = mapOf(
                    "模型版本" to MODEL_REVISION,
                    "模型格式" to "GGUF / Q2_0C",
                    "模型字节数" to model.length().toString(),
                    "测试方式" to "本地英文短句试译",
                    "未记录测试文本" to "true",
                ),
            )
        } catch (cancelled: CancellationException) {
            val modelLength = model.takeIf { hasGgufHeader(it) }?.length() ?: 0L
            val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
            val partialBytes = part.takeIf { it.isFile }?.length() ?: 0L
            mutableState.value = HyMtDownloadState(
                status = if (modelLength > 0L) HyMtStatus.PAUSED else HyMtStatus.PAUSED,
                progress = when {
                    modelLength > 0L -> 100
                    partialBytes > 0L -> (partialBytes * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99)
                    else -> 0
                },
                downloadedBytes = if (modelLength > 0L) modelLength else partialBytes,
                totalBytes = if (modelLength > 0L) modelLength else DISPLAY_BYTES,
                error = if (modelLength > 0L) "模型文件已下载，等待运行时验证" else "下载已暂停，可继续",
            )
            AppLogStore.info(app, "LocalTranslationModel", "HY-MT1.5 模型下载已暂停，已保留断点")
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = HyMtStatus.FAILED,
                error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
            AppLogStore.error(
                app,
                "LocalTranslationModel",
                "HY-MT1.5 2-bit 模型下载或验证失败",
                error,
                diagnosticContext = mapOf(
                    "模型版本" to MODEL_REVISION,
                    "模型格式" to "GGUF / Q2_0C",
                    "模型文件存在" to model.isFile.toString(),
                    "是否保留断点" to hasPartialDownload(app).toString(),
                ),
            )
            throw error
        }
    }

    private suspend fun validateAndMark(context: Context) = withContext(Dispatchers.IO) {
        HyMtRuntime.validate(context)
        val marker = readyMarker(context)
        val temp = File(marker.parentFile, ".validated.tmp")
        temp.writeText(signature(context), Charsets.UTF_8)
        if (marker.exists() && !marker.delete()) throw IOException("无法替换 HY-MT1.5 验证标记")
        if (!temp.renameTo(marker)) {
            temp.copyTo(marker, overwrite = true)
            if (!temp.delete()) throw IOException("无法清理 HY-MT1.5 验证临时文件")
        }
    }

    suspend fun delete(context: Context) = lock.withLock {
        HyMtRuntime.closeLoadedModel()
        withContext(Dispatchers.IO) {
            val directory = modelDirectory(context)
            if (directory.exists() && !directory.deleteRecursively()) {
                throw IOException("无法完整删除 HY-MT1.5 模型目录")
            }
            mutableState.value = HyMtDownloadState(status = HyMtStatus.NOT_DOWNLOADED)
        }
    }
}

internal object HyMtNative {
    @Volatile private var libraryLoaded = false

    @Synchronized
    fun ensureLibraryLoaded() {
        if (libraryLoaded) return
        try {
            System.loadLibrary("hymt")
            libraryLoaded = true
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "HY-MT1.5 ARM64 推理库未随应用安装，请安装包含混元本地运行时的版本。",
                error,
            )
        }
    }

    external fun nativeOpen(modelPath: String, threads: Int, contextTokens: Int): String?
    external fun nativeTranslate(prompt: String, maxTokens: Int): String?
    external fun nativeClose()
    external fun nativeCancel()
}

/** Serializes inference and keeps one mapped model alive across adjacent subtitle batches. */
private object HyMtRuntime {
    private const val CONTEXT_TOKENS = 4096
    private val inferenceMutex = Mutex()
    private val runtimeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadedIdentity: String? = null
    private var leases = 0
    private var unloadJob: Job? = null

    fun acquireLease() {
        synchronized(runtimeLock) {
            leases += 1
            unloadJob?.cancel()
            unloadJob = null
        }
    }

    fun releaseLease() {
        synchronized(runtimeLock) {
            leases = (leases - 1).coerceAtLeast(0)
            if (leases != 0) return
            unloadJob?.cancel()
            unloadJob = scope.launch {
                delay(20_000L)
                inferenceMutex.withLock {
                    val shouldClose = synchronized(runtimeLock) {
                        if (leases == 0) {
                            loadedIdentity = null
                            true
                        } else false
                    }
                    if (shouldClose) runCatching { HyMtNative.nativeClose() }
                }
            }
        }
    }

    private fun identity(context: Context): String {
        val model = HyMtModelManager.modelFile(context)
        return model.absolutePath + ":" + model.length() + ":" + model.lastModified()
    }

    private fun ensureLoaded(context: Context, threads: Int) {
        val model = HyMtModelManager.modelFile(context)
        if (!HyMtModelManager.isReady(context) && !model.isFile) {
            throw IllegalStateException("HY-MT1.5 2-bit 模型尚未下载，请先在本地翻译模型管理页下载。")
        }
        val requestedIdentity = identity(context)
        if (loadedIdentity == requestedIdentity) return
        runCatching { HyMtNative.nativeClose() }
        HyMtNative.ensureLibraryLoaded()
        val error = HyMtNative.nativeOpen(model.absolutePath, threads.coerceIn(1, 4), CONTEXT_TOKENS)
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        loadedIdentity = requestedIdentity
    }

    suspend fun translate(
        context: Context,
        prompt: String,
        maxTokens: Int,
    ): String = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val ownerJob = currentCoroutineContext()[Job]
            val cancellationHandle = ownerJob?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is CancellationException) runCatching {
                    HyMtNative.nativeCancel()
                }
            }
            try {
                ensureLoaded(context, LocalTranslationRuntimePolicy.inferenceThreads(context))
                val result = HyMtNative.nativeTranslate(prompt, maxTokens)
                    ?.trim()
                    .orEmpty()
                ownerJob?.ensureActive()
                if (result == "__HYMT_CANCELLED__") throw CancellationException("HY-MT1.5 推理已取消")
                if (result.isBlank()) throw IllegalStateException("HY-MT1.5 未返回可用译文")
                result
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    suspend fun validate(context: Context) = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            HyMtNative.ensureLibraryLoaded()
            val model = HyMtModelManager.modelFile(context)
            if (!model.isFile) throw IOException("HY-MT1.5 GGUF 模型文件不存在")
            val error = HyMtNative.nativeOpen(
                model.absolutePath,
                LocalTranslationRuntimePolicy.inferenceThreads(context),
                CONTEXT_TOKENS,
            )
            if (!error.isNullOrBlank()) throw IllegalStateException(error)
            loadedIdentity = identity(context)
            try {
                val result = HyMtNative.nativeTranslate(
                    "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello",
                    96,
                ).orEmpty().trim()
                if (result.isBlank() || result.equals("Hello", ignoreCase = true)) {
                    throw IllegalStateException("HY-MT1.5 模型试译未返回有效中文结果")
                }
            } finally {
                val closeNow = synchronized(runtimeLock) { leases == 0 }
                if (closeNow) {
                    HyMtNative.nativeClose()
                    loadedIdentity = null
                }
            }
        }
    }

    suspend fun closeLoadedModel() = inferenceMutex.withLock {
        val inUse = synchronized(runtimeLock) { leases > 0 }
        if (inUse) throw IllegalStateException("HY-MT1.5 正在翻译，暂时不能删除模型")
        runCatching { HyMtNative.nativeClose() }
        loadedIdentity = null
    }
}

class HyMtEngine(private val context: Context) : TranslationEngine {
    private val appContext = context.applicationContext
    override val id: String = "hy-mt-2bit"
    override val displayName: String = "HY-MT1.5 1.8B · 2-bit 本地翻译"

    override suspend fun translate(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
    ): List<String> = translateInternal(lines, srcLang, tgtLang, null)

    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>?,
    ): List<String> = translateInternal(lines, srcLang, tgtLang, context)

    private suspend fun translateInternal(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>?,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        val target = SourceLanguagePolicy.normalizeTag(tgtLang).lowercase(Locale.ROOT)
        if (target !in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn", "zh-sg")) {
            throw IllegalStateException("HY-MT1.5 当前应用配置的目标语言为简体中文。")
        }
        val source = SourceLanguagePolicy.sourceForTranslation(srcLang)
        if (!SourceLanguagePolicy.isUndetermined(source) &&
            !HyMtLanguagePolicy.supportsSource(source)
        ) {
            throw IllegalStateException(
                "HY-MT1.5 不支持源语言 " + LangMap.display(source) +
                    "（" + source + "）。请切换到支持的语言或开启自动识别。"
            )
        }
        if (!HyMtModelManager.isReady(appContext)) {
            throw IllegalStateException("HY-MT1.5 2-bit 模型尚未下载并通过本地试译验证。")
        }

        AppLogStore.info(
            appContext,
            "Translation",
            "开始调用 HY-MT1.5 2-bit 本地翻译模型",
            diagnosticContext = mapOf(
                "源语言" to if (SourceLanguagePolicy.isUndetermined(source)) "自动识别" else source,
                "目标语言" to "zh",
                "字幕条数" to lines.size.toString(),
                "模型格式" to "GGUF / Q2_0C",
            ),
        )
        val leaseLock = Any()
        var leased = false
        synchronized(leaseLock) {
            if (!leased) {
                HyMtRuntime.acquireLease()
                leased = true
            }
        }
        try {
            return withContext(Dispatchers.Default) {
                val taskContext = currentCoroutineContext()
                lines.mapIndexed { index, line ->
                    taskContext.ensureActive()
                    if (line.isBlank()) {
                        ""
                    } else if (SourceLanguagePolicy.isChinese(source)) {
                        line
                    } else {
                        val prompt = HyMtPromptBuilder.build(line, context)
                        val maxTokens = (line.length * 2 + 64).coerceIn(96, 512)
                        val translated = HyMtRuntime.translate(appContext, prompt, maxTokens)
                        if ((index + 1) % 10 == 0 || index == lines.lastIndex) {
                            AppLogStore.info(
                                appContext,
                                "Translation",
                                "HY-MT1.5 本地翻译进度",
                                diagnosticContext = mapOf(
                                    "已完成字幕" to (index + 1).toString(),
                                    "字幕总数" to lines.size.toString(),
                                    "模型版本" to "1.8B · 2-bit",
                                ),
                            )
                        }
                        translated
                    }
                }
            }
        } finally {
            synchronized(leaseLock) {
                if (leased) {
                    leased = false
                    HyMtRuntime.releaseLease()
                }
            }
        }
    }

    override fun close() = Unit
}

private object HyMtPromptBuilder {
    fun build(
        sourceText: String,
        context: Pair<List<String>, List<String>>?,
    ): String {
        val previous = context?.first.orEmpty()
            .zip(context?.second.orEmpty())
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .takeLast(3)
        if (previous.isEmpty()) {
            return "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$sourceText"
        }
        val contextText = previous.joinToString("\n") { (source, target) ->
            "原文：$source\n译文：$target"
        }
        return "$contextText\n参考上面的信息，把下面的文本翻译为中文，注意不需要翻译上文，也不要额外解释：\n$sourceText"
    }
}
