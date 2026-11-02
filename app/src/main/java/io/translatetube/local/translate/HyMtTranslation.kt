package io.translatetube.local.translate

import android.app.ActivityManager
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
    val runtimeAvailable: Boolean = true,
) {
    val hasCompleteFiles: Boolean
        get() = totalBytes > 0L && downloadedBytes >= totalBytes
}

/** Official HY-MT1.5 source languages used by the Tencent model card. */
object HyMtLanguagePolicy {
    val languages: List<Pair<String, String>> = listOf(
        "zh" to "中文", "en" to "英语", "fr" to "法语", "pt" to "葡萄牙语", "es" to "西班牙语",
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

    fun display(languageTag: String): String {
        val code = canonicalCode(languageTag)
        return languages.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second
            ?: LangMap.display(languageTag)
    }

    fun requiresTranslation(sourceLanguage: String): Boolean =
        canonicalCode(sourceLanguage) != "zh"
}

/** A pinned GGUF model plus the Q2_0C llama.cpp runtime used to execute it. */
object HyMtModelManager {
    const val DISPLAY_BYTES = 601_000_000L
    private const val MAX_BYTES = 650_000_000L
    private const val DOWNLOAD_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
    internal const val MODEL_REVISION = "4b99bde"
    internal const val RUNTIME_SOURCE_REVISION = "2af64dd00a6689a7bfaf69b4768a944d0ec6bade"
    internal const val NATIVE_ADAPTER_REVISION = "jni-20261102-r4"
    private const val VALIDATION_SCHEMA = 3
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
    private var lastRuntimeCheckLogKey: String? = null
    val state: StateFlow<HyMtDownloadState> = mutableState

    fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "models/translation/hymt")

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_NAME)

    private fun readyMarker(context: Context) = File(modelDirectory(context), ".validated")

    private fun signature(context: Context): String {
        val model = modelFile(context)
        return buildString {
            append(MODEL_REVISION).append(":Q2_0C:")
                .append(RUNTIME_SOURCE_REVISION).append(":")
                .append(NATIVE_ADAPTER_REVISION).append(":validation-").append(VALIDATION_SCHEMA)
            append(':').append(model.length()).append(':').append(model.lastModified())
        }
    }

    private fun formatLocalModelSizeForError(bytes: Long): String =
        String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0))

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
        // Do not race an active download/verification and overwrite its live StateFlow status.
        if (!lock.tryLock()) return@withContext
        try {
            val app = context.applicationContext
            val model = modelFile(app)
            val marker = readyMarker(app)
            val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
            val completeModel = hasGgufHeader(model)
            val modelBytes = model.takeIf { completeModel }?.length() ?: 0L
            val partialBytes = part.takeIf { it.isFile }?.length() ?: 0L
            val supportsArm64 = android.os.Build.SUPPORTED_ABIS.any {
                it.equals("arm64-v8a", ignoreCase = true)
            }
            val nativeLibrary = File(app.applicationInfo.nativeLibraryDir, System.mapLibraryName("hymt"))
            val runtimeError = runCatching { HyMtNative.ensureLibraryLoaded(app) }.exceptionOrNull()
            val runtimeErrorMessage = runtimeError?.message?.takeIf { it.isNotBlank() }
                ?: "HY-MT1.5 ARM64 推理库不可用，请更新应用。"

            // A missing runtime is a recoverable prerequisite on ARM64: the model can still be
            // downloaded. Record the check immediately as INFO, rather than waiting for a later
            // download/verification failure and presenting the prerequisite as a model failure.
            if (runtimeError != null && supportsArm64) {
                val diagnosticKey = listOf(
                    runtimeError.javaClass.name,
                    runtimeError.message.orEmpty(),
                    nativeLibrary.absolutePath,
                    app.applicationInfo.splitSourceDirs?.joinToString(",").orEmpty(),
                ).joinToString("|")
                if (lastRuntimeCheckLogKey != diagnosticKey) {
                    AppLogStore.info(
                        app,
                        "LocalTranslationModel",
                        "HY-MT1.5 推理运行库检查完成",
                        diagnosticContext = mapOf(
                            "检查时机" to "打开模型管理页时的状态刷新",
                            "System.loadLibrary 可加载" to "false",
                            "运行库检查结果" to runtimeErrorMessage,
                            "需要的原生库" to "lib/arm64-v8a/libhymt.so",
                            "nativeLibraryDir" to app.applicationInfo.nativeLibraryDir,
                            "splitSourceDirs" to (app.applicationInfo.splitSourceDirs?.joinToString(",") ?: "无"),
                            "原生库实际路径" to nativeLibrary.absolutePath,
                            "原生库文件存在" to nativeLibrary.isFile.toString(),
                            "设备 ABI" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                            "模型文件存在" to model.isFile.toString(),
                            "模型文件字节数" to modelBytes.toString(),
                            "未完成下载字节数" to partialBytes.toString(),
                        ),
                    )
                    lastRuntimeCheckLogKey = diagnosticKey
                }
            } else if (runtimeError == null) {
                lastRuntimeCheckLogKey = null
            }

            mutableState.value = when {
                runtimeError != null && !supportsArm64 -> HyMtDownloadState(
                    status = HyMtStatus.FAILED,
                    progress = if (modelBytes > 0L) 100
                        else (partialBytes * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99),
                    downloadedBytes = if (modelBytes > 0L) modelBytes else partialBytes,
                    totalBytes = if (modelBytes > 0L) modelBytes else DISPLAY_BYTES,
                    error = runtimeErrorMessage,
                    runtimeAvailable = false,
                )
                runtimeError == null && isReady(app) -> HyMtDownloadState(
                    status = HyMtStatus.READY,
                    progress = 100,
                    downloadedBytes = model.length(),
                    totalBytes = model.length(),
                )
                completeModel -> HyMtDownloadState(
                    status = HyMtStatus.PAUSED,
                    progress = 100,
                    downloadedBytes = model.length(),
                    totalBytes = model.length(),
                    error = if (runtimeError != null) runtimeErrorMessage
                        else if (marker.isFile) "模型需要重新验证" else "模型文件已下载，等待运行时验证",
                    runtimeAvailable = runtimeError == null,
                )
                part.isFile && partialBytes > 0L -> HyMtDownloadState(
                    status = HyMtStatus.PAUSED,
                    progress = (partialBytes * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99),
                    downloadedBytes = partialBytes,
                    totalBytes = DISPLAY_BYTES,
                    error = runtimeError?.let { runtimeErrorMessage } ?: "下载已暂停，可继续",
                    runtimeAvailable = runtimeError == null,
                )
                runtimeError != null -> HyMtDownloadState(
                    status = HyMtStatus.NOT_DOWNLOADED,
                    error = runtimeErrorMessage,
                    runtimeAvailable = false,
                )
                else -> HyMtDownloadState(status = HyMtStatus.NOT_DOWNLOADED)
            }
        } finally {
            lock.unlock()
        }
    }

    suspend fun download(context: Context) = lock.withLock {
        val app = context.applicationContext
        val directory = modelDirectory(app)
        val model = modelFile(app)
        val part = ResumableModelArchiveDownloader.partFileFor(model, MODEL_URL)
        val nativeLibrary = File(app.applicationInfo.nativeLibraryDir, System.mapLibraryName("hymt"))
        val supportsArm64 = android.os.Build.SUPPORTED_ABIS.any {
            it.equals("arm64-v8a", ignoreCase = true)
        }
        val libraryLoadable = withContext(Dispatchers.IO) { HyMtNative.isLibraryLoadable(app) }
        val canDownloadBeforeRuntimeUpdate = supportsArm64 && !libraryLoadable
        if (!canDownloadBeforeRuntimeUpdate) {
            try {
                withContext(Dispatchers.IO) { HyMtNative.ensureLibraryLoaded(app) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val modelBytes = model.takeIf { hasGgufHeader(it) }?.length() ?: 0L
                val partialBytes = part.takeIf { it.isFile }?.length() ?: 0L
                val downloadedBytes = if (modelBytes > 0L) modelBytes else partialBytes
                val totalBytes = if (modelBytes > 0L) modelBytes else DISPLAY_BYTES
                mutableState.value = HyMtDownloadState(
                    status = HyMtStatus.FAILED,
                    progress = if (modelBytes > 0L) 100
                        else (partialBytes * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99),
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    error = error.message?.takeIf { it.isNotBlank() }
                        ?: "HY-MT1.5 ARM64 推理库不可用，请更新应用。",
                    runtimeAvailable = false,
                )
                AppLogStore.error(
                    app,
                    "LocalTranslationModel",
                    "HY-MT1.5 推理运行库检查失败，已停止模型下载",
                    error,
                    diagnosticContext = mapOf(
                        "需要的原生库" to "lib/arm64-v8a/libhymt.so",
                        "原生库实际路径" to nativeLibrary.absolutePath,
                        "原生库文件存在" to nativeLibrary.isFile.toString(),
                        "原生库文件字节数" to nativeLibrary.takeIf { it.isFile }?.length()?.toString().orEmpty(),
                        "模型文件已下载" to (modelBytes > 0L).toString(),
                        "模型文件字节数" to modelBytes.toString(),
                        "设备 ABI" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                        "链接器错误" to (error.cause?.message ?: error.message.orEmpty()),
                    ),
                )
                throw error
            }
        } else {
            AppLogStore.info(
                app,
                "LocalTranslationModel",
                "HY-MT1.5 推理运行库检查完成，模型下载可继续",
                diagnosticContext = mapOf(
                    "需要的原生库" to "lib/arm64-v8a/libhymt.so",
                    "原生库实际路径" to nativeLibrary.absolutePath,
                    "System.loadLibrary 可加载" to HyMtNative.isLibraryLoadable(app).toString(),
                    "nativeLibraryDir" to app.applicationInfo.nativeLibraryDir,
                    "splitSourceDirs" to (app.applicationInfo.splitSourceDirs?.joinToString(",") ?: "无"),
                    "设备 ABI" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                    "模型文件" to model.name,
                ),
            )
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            val error = "无法创建 HY-MT1.5 模型目录"
            mutableState.value = HyMtDownloadState(status = HyMtStatus.FAILED, error = error)
            throw IOException(error)
        }
        if (!isReady(app) && !hasGgufHeader(model)) {
            val remainingBytes = if (part.isFile) {
                (DISPLAY_BYTES - part.length()).coerceAtLeast(0L)
            } else {
                DISPLAY_BYTES
            }
            val requiredSpace = remainingBytes + DOWNLOAD_SPACE_RESERVE_BYTES
            if (directory.usableSpace < requiredSpace) {
                val error = "存储空间不足：还需要约 " +
                    formatLocalModelSizeForError(requiredSpace) +
                    " 可用空间，请清理空间后继续。"
                mutableState.value = HyMtDownloadState(
                    status = HyMtStatus.FAILED,
                    progress = if (part.isFile) {
                        (part.length() * 100L / DISPLAY_BYTES).toInt().coerceIn(0, 99)
                    } else 0,
                    downloadedBytes = part.takeIf { it.isFile }?.length() ?: 0L,
                    totalBytes = DISPLAY_BYTES,
                    error = error,
                )
                AppLogStore.warning(
                    app,
                    "LocalTranslationModel",
                    "HY-MT1.5 下载因存储空间不足而停止",
                    diagnosticContext = mapOf(
                        "需要的可用空间字节" to requiredSpace.toString(),
                        "当前可用空间字节" to directory.usableSpace.toString(),
                        "已下载字节" to (part.takeIf { it.isFile }?.length() ?: 0L).toString(),
                    ),
                )
                throw IOException(error)
            }
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
                val downloadContext = currentCoroutineContext()
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
                    checkActive = { downloadContext.ensureActive() },
                )
                if (result.downloadedBytes !in 500_000_000L..MAX_BYTES ||
                    !hasGgufHeader(part)
                ) {
                    part.delete()
                    ResumableModelArchiveDownloader.clearResumeMetadata(part)
                    throw IOException("下载文件大小或 GGUF 文件头校验失败，已清理异常下载文件")
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
                status = HyMtStatus.PAUSED,
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
        } catch (error: Exception) {
            val nativeLibraryMissing = supportsArm64 &&
                !withContext(Dispatchers.IO) { HyMtNative.isLibraryLoadable(app) }
            val completeModel = hasGgufHeader(model)
            val partialBytes = part.takeIf { it.isFile }?.length() ?: 0L
            mutableState.value = mutableState.value.copy(
                status = HyMtStatus.FAILED,
                progress = if (completeModel) 100 else mutableState.value.progress,
                downloadedBytes = if (completeModel) model.length() else partialBytes,
                totalBytes = if (completeModel) model.length() else DISPLAY_BYTES,
                error = when {
                    nativeLibraryMissing && completeModel ->
                        "模型已下载，推理运行库暂不可用，已保留模型文件。"
                    else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                },
                runtimeAvailable = !nativeLibraryMissing,
            )
            AppLogStore.error(
                app,
                "LocalTranslationModel",
                if (nativeLibraryMissing && completeModel) {
                    "HY-MT1.5 模型已下载，推理运行库暂不可用"
                } else {
                    "HY-MT1.5 2-bit 模型下载或验证失败"
                },
                error,
                diagnosticContext = mapOf(
                    "模型版本" to MODEL_REVISION,
                    "模型格式" to "GGUF / Q2_0C",
                    "模型文件存在" to model.isFile.toString(),
                    "模型文件大小" to model.length().toString(),
                    "原生运行库存在" to nativeLibrary.isFile.toString(),
                    "原生运行库路径" to nativeLibrary.absolutePath,
                    "System.loadLibrary 可加载" to HyMtNative.isLibraryLoadable(app).toString(),
                    "nativeLibraryDir" to app.applicationInfo.nativeLibraryDir,
                    "splitSourceDirs" to (app.applicationInfo.splitSourceDirs?.joinToString(",") ?: "无"),
                    "设备 ABI" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
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
        try {
            HyMtRuntime.deleteModelFiles {
                withContext(Dispatchers.IO) {
                    val directory = modelDirectory(context)
                    if (directory.exists() && !directory.deleteRecursively()) {
                        throw IOException("无法完整删除 HY-MT1.5 模型目录")
                    }
                    mutableState.value = HyMtDownloadState(status = HyMtStatus.NOT_DOWNLOADED)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val model = modelFile(context)
            val complete = hasGgufHeader(model)
            mutableState.value = HyMtDownloadState(
                status = HyMtStatus.FAILED,
                progress = if (complete) 100 else 0,
                downloadedBytes = model.takeIf { it.isFile }?.length() ?: 0L,
                totalBytes = if (complete) model.length() else DISPLAY_BYTES,
                error = error.message?.takeIf { it.isNotBlank() } ?: "删除 HY-MT1.5 模型失败",
            )
            throw error
        }
    }
}

internal object HyMtNative {
    @Volatile private var libraryLoaded = false

    fun isLibraryLoadable(context: Context): Boolean =
        runCatching { ensureLibraryLoaded(context) }.isSuccess

    @Synchronized
    fun ensureLibraryLoaded(context: Context) {
        if (libraryLoaded) return

        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        if (supportedAbis.none { it.equals("arm64-v8a", ignoreCase = true) }) {
            throw IllegalStateException(
                "当前设备不支持 ARM64，无法运行 HY-MT1.5。（设备 ABI：${supportedAbis.joinToString(", ")}）",
            )
        }

        val nativeLibrary = File(
            context.applicationContext.applicationInfo.nativeLibraryDir,
            System.mapLibraryName("hymt"),
        )
        try {
            // JNI_OnLoad registers and validates every native method before this returns.
            System.loadLibrary("hymt")
            libraryLoaded = true
        } catch (error: UnsatisfiedLinkError) {
            val message = if (nativeLibrary.isFile) {
                "HY-MT1.5 ARM64 推理库已存在，但加载失败或 JNI 接口签名不匹配，请更新应用后重试。"
            } else {
                "当前应用安装包未包含 HY-MT1.5 ARM64 推理库，请更新到包含混元运行时的版本。"
            }
            throw IllegalStateException(message, error)
        }
    }

    external fun nativeOpen(modelPath: String, threads: Int, contextTokens: Int): String?
    external fun nativeTranslate(prompt: String, maxTokens: Int): String?
    external fun nativeClose()
    external fun nativeCancel()
    external fun nativeResetCancel()
}

/** Serializes inference and keeps one mapped model alive across adjacent subtitle batches. */
private object HyMtRuntime {
    private const val CONTEXT_TOKENS = 2048
    private const val MIN_FREE_MEMORY_BYTES = 768L * 1024L * 1024L
    private val inferenceMutex = Mutex()
    private val runtimeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadedIdentity: String? = null
    private var leases = 0
    private var deletingModel = false
    private var unloadJob: Job? = null

    fun acquireLease() {
        synchronized(runtimeLock) {
            if (deletingModel) {
                throw IllegalStateException("HY-MT1.5 模型正在删除，请稍后重试。")
            }
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
        return HyMtModelManager.MODEL_REVISION + ":" + HyMtModelManager.RUNTIME_SOURCE_REVISION + ":" +
            HyMtModelManager.NATIVE_ADAPTER_REVISION + ":" + model.absolutePath + ":" +
            model.length() + ":" + model.lastModified()
    }

    private fun ensureMemoryAvailable(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        if (memoryInfo.lowMemory || memoryInfo.availMem < MIN_FREE_MEMORY_BYTES) {
            throw IllegalStateException(
                "设备当前可用内存不足，暂不加载 HY-MT1.5。请关闭占用内存的应用后重试。"
            )
        }
    }

    private fun ensureLoaded(context: Context, threads: Int) {
        ensureMemoryAvailable(context)
        val model = HyMtModelManager.modelFile(context)
        if (!HyMtModelManager.isReady(context)) {
            throw IllegalStateException("HY-MT1.5 2-bit 模型尚未通过本地试译验证，请先在本地翻译模型管理页重新验证。")
        }
        val requestedIdentity = identity(context)
        if (loadedIdentity == requestedIdentity) return
        runCatching { HyMtNative.nativeClose() }
        HyMtNative.ensureLibraryLoaded(context)
        val error = HyMtNative.nativeOpen(model.absolutePath, threads.coerceIn(1, 4), CONTEXT_TOKENS)
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        loadedIdentity = requestedIdentity
    }

    suspend fun prepare(context: Context) = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context, LocalTranslationRuntimePolicy.inferenceThreads(context))
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
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
                ownerJob?.ensureActive()
                val result = HyMtNative.nativeTranslate(prompt, maxTokens)
                    ?.trim()
                    .orEmpty()
                ownerJob?.ensureActive()
                if (result == "__HYMT_CANCELLED__") throw CancellationException("HY-MT1.5 推理已取消")
                if (result.startsWith("HY-MT1.5") || result.startsWith("字幕内容过长") ||
                    result.startsWith("无法为 HY-MT1.5")
                ) throw IllegalStateException(result)
                if (result.isBlank()) throw IllegalStateException("HY-MT1.5 未返回可用译文")
                result
            } finally {
                cancellationHandle?.dispose()
                runCatching { HyMtNative.nativeResetCancel() }
            }
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun validate(context: Context) = inferenceMutex.withLock {
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
                ensureMemoryAvailable(context)
                HyMtNative.ensureLibraryLoaded(context)
                val model = HyMtModelManager.modelFile(context)
                if (!model.isFile) throw IOException("HY-MT1.5 GGUF 模型文件不存在")
                ownerJob?.ensureActive()
                val error = HyMtNative.nativeOpen(
                    model.absolutePath,
                    LocalTranslationRuntimePolicy.inferenceThreads(context),
                    CONTEXT_TOKENS,
                )
                if (!error.isNullOrBlank()) throw IllegalStateException(error)
                loadedIdentity = identity(context)
                ownerJob?.ensureActive()
                val result = HyMtNative.nativeTranslate(
                    "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello",
                    96,
                ).orEmpty().trim()
                ownerJob?.ensureActive()
                if (result == "__HYMT_CANCELLED__") {
                    throw CancellationException("HY-MT1.5 本地试译验证已取消")
                }
                if (result.isBlank() || result.equals("Hello", ignoreCase = true) ||
                    result.none { it in '\u4e00'..'\u9fff' }
                ) {
                    throw IllegalStateException("HY-MT1.5 模型试译未返回有效中文结果")
                }
            } finally {
                cancellationHandle?.dispose()
                runCatching { HyMtNative.nativeResetCancel() }
                val closeNow = synchronized(runtimeLock) { leases == 0 }
                if (closeNow) {
                    runCatching { HyMtNative.nativeClose() }
                    loadedIdentity = null
                }
            }
        }
    }

    suspend fun deleteModelFiles(deleteFiles: suspend () -> Unit) = inferenceMutex.withLock {
        synchronized(runtimeLock) {
            if (leases > 0) {
                throw IllegalStateException("HY-MT1.5 正在翻译，暂时不能删除模型")
            }
            deletingModel = true
            unloadJob?.cancel()
            unloadJob = null
        }
        try {
            runCatching { HyMtNative.nativeClose() }
            loadedIdentity = null
            deleteFiles()
        } finally {
            synchronized(runtimeLock) {
                deletingModel = false
            }
        }
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

    @Suppress("UNUSED_PARAMETER")
    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>?,
        lookaheadSource: List<String>,
        videoTitle: String?,
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
        val simplifiedChineseSource = !HyMtLanguagePolicy.requiresTranslation(source)
        HyMtRuntime.acquireLease()
        try {
            if (!simplifiedChineseSource && lines.any { it.isNotBlank() }) {
                HyMtRuntime.prepare(appContext)
            }
            return withContext(Dispatchers.Default) {
                val taskContext = currentCoroutineContext()
                var rollingContext = context?.first.orEmpty()
                    .zip(context?.second.orEmpty())
                    .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                    .takeLast(3)
                    .let { cues ->
                        if (cues.isEmpty()) null else cues.map { it.first } to cues.map { it.second }
                    }
                lines.mapIndexed { index, line ->
                    taskContext.ensureActive()
                    val translated = when {
                        line.isBlank() -> ""
                        simplifiedChineseSource -> line
                        else -> try {
                            val prompt = HyMtPromptBuilder.build(line, source, rollingContext)
                            val maxTokens = (line.length * 2 + 64).coerceIn(96, 512)
                            HyMtRuntime.translate(appContext, prompt, maxTokens)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            AppLogStore.warning(
                                appContext,
                                "Translation",
                                "HY-MT1.5 单条字幕翻译失败，已交给批次恢复逻辑",
                                error,
                                diagnosticContext = mapOf(
                                    "字幕序号" to (index + 1).toString(),
                                    "字幕总数" to lines.size.toString(),
                                    "字幕字符数" to line.length.toString(),
                                    "模型版本" to "1.8B · 2-bit",
                                    "未记录字幕原文" to "true",
                                ),
                            )
                            ""
                        }
                    }
                    if (
                        translated.isNotBlank() &&
                        !SubtitleTranslationQualityPolicy.isPunctuationOnlyTranslation(line, translated) &&
                        !SubtitleTranslationQualityPolicy.isSourceEcho(line, translated, source) &&
                        !SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                            line, translated, source, "zh-CN",
                        )
                    ) {
                        rollingContext = appendHyMtTranslationContext(rollingContext, line, translated)
                    }
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
        } finally {
            HyMtRuntime.releaseLease()
        }
    }

    override fun close() = Unit
}

internal fun appendHyMtTranslationContext(
    context: Pair<List<String>, List<String>>?,
    sourceText: String,
    translatedText: String,
): Pair<List<String>, List<String>>? {
    if (sourceText.isBlank() || translatedText.isBlank()) return context
    val recent = context?.first.orEmpty()
        .zip(context?.second.orEmpty())
        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        .takeLast(2)
        .toMutableList()
    recent += sourceText to translatedText
    return recent.map { it.first } to recent.map { it.second }
}

private object HyMtPromptBuilder {
    fun build(
        sourceText: String,
        sourceLanguage: String,
        context: Pair<List<String>, List<String>>?,
    ): String {
        val previous = context?.first.orEmpty()
            .zip(context?.second.orEmpty())
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .takeLast(3)
        val sourceLabel = if (SourceLanguagePolicy.isUndetermined(sourceLanguage)) {
            "自动识别原文语言"
        } else {
            HyMtLanguagePolicy.display(sourceLanguage)
        }
        val fidelityGuidelines = listOf(
            SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES,
            SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES,
        ).joinToString("\n")
        if (previous.isEmpty()) {
            return "$fidelityGuidelines\n将以下${sourceLabel}字幕台词翻译为简体中文。保持人物称呼；字幕可能是被切开的半句话，请结合当前文本理解，但只翻译当前这一条。不要删减、概括或扩写，只输出译文，不要解释：\n\n$sourceText"
        }
        val contextText = previous.joinToString("\n") { (source, target) ->
            "原文：$source\n译文：$target"
        }
        return "$fidelityGuidelines\n以下是前面字幕的原文和已确认译文，仅供理解上下文及统一人物称呼、专有名词和术语；优先沿用已确认的译法，不要翻译或重复上文：\n$contextText\n\n将下面的${sourceLabel}字幕翻译为简体中文。当前字幕可能是半句话，请结合上文理解，保持人称、指代和语气连贯；只翻译当前这一条，不要删减、概括、扩写或额外解释：\n$sourceText"

    }
}
