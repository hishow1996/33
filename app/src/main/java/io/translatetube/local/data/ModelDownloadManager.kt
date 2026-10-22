package io.translatetube.local.data

import io.translatetube.local.util.HttpDownloadResponsePolicy
import io.translatetube.local.util.HttpDownloadSpeedPolicy
import io.translatetube.local.util.SlowDownloadSourceException

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.translatetube.local.tts.KokoroRuntimeFilePolicy
import io.translatetube.local.asr.AtomicFileInstaller
import io.translatetube.local.asr.ArchiveEntryLimitPolicy
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 模型种类：TTS=配音（Kokoro）、ASR=转写（Whisper）——完全独立，可分别下载 */
enum class ModelKind { TTS, ASR }

enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, EXTRACTING, READY, FAILED }

data class ModelState(
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val progress: Int = 0,          // 0-100（下载阶段）
    val downloadedMB: Long = 0,
    val totalMB: Long = 0,
    val error: String? = null,
    val stageMessage: String? = null,
)

/** 模型文件就绪判定与路径（engine 加载的单一事实来源） */
object ModelFiles {
    private val ttsAssetInstallLock = Any()
    private val bundledTtsSupportFiles = listOf(
        "tokens.txt", "lexicon-zh.txt", "phone-zh.fst", "date-zh.fst", "number-zh.fst",
    )

    fun ttsDir(ctx: Context) = File(ctx.filesDir, "models/tts")
    /** Keep the existing directory for Whisper and the shared Silero VAD file. */
    fun asrDir(ctx: Context) = File(ctx.filesDir, "models/whisper")
    fun senseVoiceDir(ctx: Context) = File(ctx.filesDir, "models/sensevoice")

    fun selectedLocalAsrModelId(ctx: Context): String =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("asr_local_model", "whisper")
            .let { if (it == "sensevoice") "sensevoice" else "whisper" }

    fun localAsrModelDisplayName(modelId: String): String =
        if (modelId == "sensevoice") "SenseVoice Small INT8" else "Whisper Base"

    private fun validModel(file: File, minBytes: Long = 1L * 1024 * 1024) =
        file.isFile && file.length() >= minBytes

    fun ttsCoreReady(ctx: Context): Boolean =
        validModel(File(ttsDir(ctx), "model.int8.onnx")) &&
            validModel(File(ttsDir(ctx), "voices.bin"))

    fun ttsReady(ctx: Context): Boolean = ttsDirectoryReady(ttsDir(ctx))
    internal fun ttsDirectoryReady(dir: File): Boolean =
        KokoroRuntimeFilePolicy.missingRuntimeFiles(dir).isEmpty()
    internal fun missingTtsRuntimeFiles(dir: File): List<String> =
        KokoroRuntimeFilePolicy.missingRuntimeFiles(dir)

    /** Restore small bundled text/FST assets without re-downloading the large ONNX package. */
    internal fun installBundledTtsSupportFiles(ctx: Context, targetDir: File = ttsDir(ctx)) {
        synchronized(ttsAssetInstallLock) {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) {
                throw java.io.IOException("无法创建 Kokoro 模型目录：${targetDir.absolutePath}")
            }
            for (name in bundledTtsSupportFiles) {
                val target = File(targetDir, name)
                if (KokoroRuntimeFilePolicy.isValidRequiredFile(name, target)) continue
                val temp = File(targetDir, ".$name.asset.tmp")
                temp.delete()
                try {
                    ctx.assets.open("tts/$name").use { input ->
                        java.io.FileOutputStream(temp).use { output ->
                            input.copyTo(output, 32 * 1024)
                            output.fd.sync()
                        }
                    }
                    if (!KokoroRuntimeFilePolicy.isValidRequiredFile(name, temp)) {
                        throw java.io.IOException("应用内 Kokoro 附属文件为空或不完整：$name")
                    }
                    AtomicFileInstaller.install(temp, target)
                } catch (e: Exception) {
                    temp.delete()
                    throw e
                }
            }
        }
    }

    fun ensureTtsRuntimeFiles(ctx: Context) {
        val dir = ttsDir(ctx)
        installBundledTtsSupportFiles(ctx, dir)
        val missing = missingTtsRuntimeFiles(dir)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Kokoro 运行文件不完整：${missing.joinToString("、")}。请在设置中重新准备配音模型。"
            )
        }
    }

    fun ttsCacheSignature(ctx: Context): String =
        KokoroRuntimeFilePolicy.cacheSignature(ttsDir(ctx))

    internal fun asrDirectoryCoreReady(dir: File): Boolean =
        WhisperModelFilePolicy.isCoreComplete(
            mapOf(
                "base-encoder.int8.onnx" to File(dir, "base-encoder.int8.onnx").takeIf { it.isFile }?.length().orZero(),
                "base-decoder.int8.onnx" to File(dir, "base-decoder.int8.onnx").takeIf { it.isFile }?.length().orZero(),
                "base-tokens.txt" to File(dir, "base-tokens.txt").takeIf { it.isFile }?.length().orZero(),
            )
        )

    fun whisperCoreReady(ctx: Context): Boolean = asrDirectoryCoreReady(asrDir(ctx))

    internal fun senseVoiceDirectoryCoreReady(dir: File): Boolean =
        SenseVoiceModelFilePolicy.isCoreComplete(
            mapOf(
                "model.int8.onnx" to File(dir, "model.int8.onnx").takeIf { it.isFile }?.length().orZero(),
                "tokens.txt" to File(dir, "tokens.txt").takeIf { it.isFile }?.length().orZero(),
            )
        )

    fun senseVoiceCoreReady(ctx: Context): Boolean =
        senseVoiceDirectoryCoreReady(senseVoiceDir(ctx))

    fun asrCoreReady(ctx: Context): Boolean =
        if (selectedLocalAsrModelId(ctx) == "sensevoice") senseVoiceCoreReady(ctx)
        else whisperCoreReady(ctx)

    fun whisperReady(ctx: Context): Boolean =
        whisperCoreReady(ctx) && isValidVadModel(File(asrDir(ctx), "silero_vad.onnx"))

    fun senseVoiceReady(ctx: Context): Boolean =
        senseVoiceCoreReady(ctx) && isValidVadModel(File(asrDir(ctx), "silero_vad.onnx"))

    fun asrReady(ctx: Context): Boolean =
        if (selectedLocalAsrModelId(ctx) == "sensevoice") senseVoiceReady(ctx)
        else whisperReady(ctx)

    internal fun isValidVadModel(file: File): Boolean {
        if (!file.isFile || file.length() < WhisperModelFilePolicy.MIN_VAD_BYTES) {
            vadValidationCache.remove(file.absolutePath)
            return false
        }
        val identity = runCatching {
            val stat = android.system.Os.stat(file.absolutePath)
            "${stat.st_dev}:${stat.st_ino}"
        }.getOrNull()
        val path = file.absolutePath
        val length = file.length()
        val modified = file.lastModified()
        vadValidationCache[path]?.let { cached ->
            if (cached.length == length && cached.modifiedMs == modified && cached.identity == identity) {
                return cached.valid
            }
        }
        val valid = runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .equals(VAD_SHA256, ignoreCase = true)
        }.getOrDefault(false)
        vadValidationCache[path] = VadValidation(length, modified, identity, valid)
        return valid
    }

    private data class VadValidation(
        val length: Long,
        val modifiedMs: Long,
        val identity: String?,
        val valid: Boolean,
    )

    private const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    private val vadValidationCache =
        java.util.concurrent.ConcurrentHashMap<String, VadValidation>()

    fun anyReady(ctx: Context) = ttsReady(ctx) || whisperReady(ctx) || senseVoiceReady(ctx)

    private fun Long?.orZero(): Long = this ?: 0L
}

/**
 * 双模型独立下载管理器：
 * - TTS（Kokoro v1.1 int8，压缩包 140MB → 就绪 207MB）
 * - ASR（Whisper base int8，压缩包 198MB → 就绪 160MB）
 * 双源：GitHub 官方 → 镜像加速（国内直连 GitHub 不稳时自动切换）
 * 原子性：下载到 .tmp → 解压到 .tmp → 原子 rename，任何中断不污染旧文件
 */
class ModelDownloadManager(private val ctx: Context, private val scope: CoroutineScope) {

    /** Queue lifecycle events away from UI and file/network workers; progress ticks are never logged. */
    private val modelLogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "model-download-log").apply { isDaemon = true }
    }

    private fun logModelInfo(message: String, details: Map<String, String> = emptyMap()) {
        runCatching {
            modelLogExecutor.execute {
                runCatching {
                    io.translatetube.local.util.AppLogStore.info(
                        ctx.applicationContext, "ModelDownloadManager", message,
                        diagnosticContext = details,
                    )
                }
            }
        }
    }

    private fun logModelWarning(
        message: String,
        failure: Throwable? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            modelLogExecutor.execute {
                runCatching {
                    io.translatetube.local.util.AppLogStore.warning(
                        ctx.applicationContext, "ModelDownloadManager", message, failure,
                        diagnosticContext = details,
                    )
                }
            }
        }
    }

    private fun logModelError(
        message: String,
        failure: Throwable? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            modelLogExecutor.execute {
                runCatching {
                    io.translatetube.local.util.AppLogStore.error(
                        ctx.applicationContext, "ModelDownloadManager", message, failure,
                        diagnosticContext = details,
                    )
                }
            }
        }
    }

    private fun modelKindDetails(kind: ModelKind): Map<String, String> = mapOf(
        "模型类别" to kind.name,
        "当前状态" to (if (kind == ModelKind.TTS) _tts.value else _asr.value).status.name,
        "当前进度百分比" to (if (kind == ModelKind.TTS) _tts.value else _asr.value).progress.toString(),
        "当前下载阶段" to (if (kind == ModelKind.TTS) _tts.value else _asr.value).stageMessage.orEmpty(),
        "目标目录类型" to if (kind == ModelKind.TTS) "TTS" else "ASR",
        "当前选择的转写模型" to if (kind == ModelKind.ASR) {
            runCatching { ModelFiles.selectedLocalAsrModelId(ctx) }.getOrDefault("读取失败")
        } else {
            "不适用"
        },
    )

    private val _tts = MutableStateFlow(ModelState())
    val tts: StateFlow<ModelState> = _tts
    private val _asr = MutableStateFlow(ModelState())
    val asr: StateFlow<ModelState> = _asr

    private var ttsJob: Job? = null
    private var asrJob: Job? = null
    /** Guards against rapid repeated taps starting multiple installers for the same model. */
    private val downloadStartLock = Any()
    private val startingDownloads = mutableSetOf<ModelKind>()

    private fun claimDownloadStart(kind: ModelKind): Boolean = synchronized(downloadStartLock) {
        val job = if (kind == ModelKind.TTS) ttsJob else asrJob
        val state = if (kind == ModelKind.TTS) _tts.value else _asr.value
        if (kind in startingDownloads || job?.isActive == true || isActiveModelState(state)) {
            false
        } else {
            startingDownloads.add(kind)
            true
        }
    }

    private fun releaseDownloadStart(kind: ModelKind) {
        synchronized(downloadStartLock) { startingDownloads.remove(kind) }
    }

    // A shared pause gate lets the notification pause either or both active model tasks.
    private val pauseLock = Any()
    @Volatile private var resumeSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val isPaused: Boolean get() = synchronized(pauseLock) { resumeSignal != null }

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        // StateFlow starts from NOT_DOWNLOADED, but models survive process restarts. Reconcile
        // persisted status against the same file checks used by the playback engines so the
        // settings page and foreground notification agree before another task is started.
        val ttsIsReady = runCatching { ModelFiles.ttsReady(ctx) }.getOrDefault(false)
        val asrIsReady = runCatching { ModelFiles.asrReady(ctx) }.getOrDefault(false)
        _tts.value = ModelState(
            status = ModelAvailabilityPolicy.initialStatus(ttsIsReady),
            progress = if (ttsIsReady) 100 else 0,
        )
        _asr.value = ModelState(
            status = ModelAvailabilityPolicy.initialStatus(asrIsReady),
            progress = if (asrIsReady) 100 else 0,
        )
    }

    /**
     * OkHttp's synchronous execute/read calls can block a worker even after its coroutine is
     * cancelled. Attach to the Job's cancelling transition so cancellation closes the socket
     * immediately instead of waiting for the 60-second read timeout.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T> executeCancellable(
        call: okhttp3.Call,
        consume: suspend (okhttp3.Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val activeJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val cancellationHandle = activeJob?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause is java.util.concurrent.CancellationException) call.cancel()
        }
        try {
            call.execute().use { response -> consume(response) }
        } catch (error: Exception) {
            if (activeJob?.isCancelled == true &&
                error !is kotlinx.coroutines.CancellationException
            ) {
                throw kotlinx.coroutines.CancellationException("模型下载已取消").also {
                    it.initCause(error)
                }
            }
            throw error
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private val ttsUrls = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
    )
    private val asrUrls = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
    )
    private val senseVoiceUrls = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
    )

    private fun startBackgroundDownloadService(): Boolean {
        val intent = android.content.Intent(ctx, ModelDownloadForegroundService::class.java)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.e("ModelDL", "无法启动后台下载前台服务", e)
            false
        }
    }

    fun download(kind: ModelKind) {
        if (!claimDownloadStart(kind)) {
            logModelWarning(
                "重复模型下载请求已忽略",
                details = modelKindDetails(kind) + mapOf(
                    "忽略原因" to "相同模型已有启动过程、下载任务或安装任务运行",
                    "是否已有配音模型任务" to (ttsJob?.isActive == true).toString(),
                    "是否已有转写模型任务" to (asrJob?.isActive == true).toString(),
                ),
            )
            return
        }
        logModelInfo(
            "模型下载/安装任务已请求启动",
            modelKindDetails(kind) + mapOf(
                "缓存目录可用空间 MB" to (ctx.cacheDir.usableSpace / (1024L * 1024L)).toString(),
                "应用文件目录可用空间 MB" to (ctx.filesDir.usableSpace / (1024L * 1024L)).toString(),
                "是否处于暂停状态" to isPaused.toString(),
                "下载源数量" to when (kind) {
                    ModelKind.TTS -> ttsUrls.size.toString()
                    ModelKind.ASR -> (if (ModelFiles.selectedLocalAsrModelId(ctx) == "sensevoice") senseVoiceUrls else asrUrls).size.toString()
                },
            ),
        )
        // A new task joins an already-paused model session instead of secretly resuming work.
        if (io.translatetube.local.tts.PiperModels.hasActiveDownloads &&
            io.translatetube.local.tts.PiperModels.isPaused
        ) {
            setDownloadsPaused(true)
        }
        try {
            when (kind) {
                ModelKind.TTS -> {
                    runCatching {
                        if (ModelFiles.ttsCoreReady(ctx)) ModelFiles.installBundledTtsSupportFiles(ctx)
                    }.onFailure {
                        Log.w("ModelDL", "无法修复 Kokoro 应用内附属文件，将检查完整模型包：${it.message}")
                    }
                    if (ModelFiles.ttsReady(ctx)) {
                        _tts.value = ModelState(status = ModelStatus.READY, progress = 100)
                        logModelInfo(
                            "配音模型已存在且完整，跳过重复下载",
                            modelKindDetails(ModelKind.TTS) + mapOf(
                                "检查目录类型" to "TTS",
                                "模型目录可用空间 MB" to (ModelFiles.ttsDir(ctx).usableSpace / (1024L * 1024L)).toString(),
                            ),
                        )
                        return
                    }
                    if (!startBackgroundDownloadService()) {
                        _tts.value = ModelState(status = ModelStatus.FAILED, error = "无法启动后台下载服务，请检查系统后台运行权限后重试")
                        logModelError(
                            "无法启动配音模型后台下载服务",
                            details = modelKindDetails(ModelKind.TTS) + mapOf(
                                "失败阶段" to "startForegroundService/startService",
                                "目标目录" to ModelFiles.ttsDir(ctx).name,
                            ),
                        )
                        return
                    }
                    val job = scope.launch {
                        val worker = kotlinx.coroutines.currentCoroutineContext()[Job]
                        try {
                            runDownload(ModelKind.TTS, _tts, ttsUrls, ModelFiles.ttsDir(ctx))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            io.translatetube.local.util.AppLogStore.error(
                                ctx.applicationContext,
                                "ModelDownload",
                                "配音模型下载或安装任务失败",
                                e,
                                diagnosticContext = mapOf(
                                    "模型类别" to "TTS",
                                    "下载阶段" to _tts.value.stageMessage.orEmpty().ifBlank { _tts.value.status.name },
                                    "下载进度百分比" to _tts.value.progress.toString(),
                                    "已下载 MB" to _tts.value.downloadedMB.toString(),
                                    "模型总大小 MB" to _tts.value.totalMB.toString(),
                                    "目标模型目录类型" to "TTS",
                                ),
                            )
                            Log.e("ModelDL", "TTS 下载任务异常", e)
                            _tts.value = ModelState(status = ModelStatus.FAILED, error = e.message ?: "下载任务异常")
                        } finally {
                            if (worker?.isCancelled == true && isActiveModelState(_tts.value)) {
                                _tts.value = ModelState(status = ModelStatus.FAILED, error = "下载任务已取消")
                            }
                            synchronized(downloadStartLock) {
                                if (ttsJob === worker) ttsJob = null
                            }
                            clearPauseIfIdle()
                        }
                    }
                    synchronized(downloadStartLock) { ttsJob = job }
                }
                ModelKind.ASR -> {
                    val selectedModelId = ModelFiles.selectedLocalAsrModelId(ctx)
                    if (ModelFiles.asrReady(ctx)) {
                        _asr.value = ModelState(status = ModelStatus.READY, progress = 100)
                        logModelInfo(
                            "当前转写模型及必需附属文件已完整，跳过重复下载",
                            modelKindDetails(ModelKind.ASR) + mapOf(
                                "Whisper 完整性" to ModelFiles.whisperReady(ctx).toString(),
                                "SenseVoice 完整性" to ModelFiles.senseVoiceReady(ctx).toString(),
                                "语音检测 VAD 完整性" to ModelFiles.isValidVadModel(File(ModelFiles.asrDir(ctx), "silero_vad.onnx")).toString(),
                            ),
                        )
                        return
                    }
                    if (!startBackgroundDownloadService()) {
                        _asr.value = ModelState(status = ModelStatus.FAILED, error = "无法启动后台下载服务，请检查系统后台运行权限后重试")
                        logModelError(
                            "无法启动语音转写模型后台下载服务",
                            details = modelKindDetails(ModelKind.ASR) + mapOf(
                                "失败阶段" to "startForegroundService/startService",
                                "目标模型" to ModelFiles.selectedLocalAsrModelId(ctx),
                            ),
                        )
                        return
                    }
                    val job = scope.launch {
                        val worker = kotlinx.coroutines.currentCoroutineContext()[Job]
                        try {
                            when {
                                ModelFiles.asrCoreReady(ctx) -> runVadOnly(_asr)
                                selectedModelId == "sensevoice" ->
                                    runDownload(ModelKind.ASR, _asr, senseVoiceUrls, ModelFiles.senseVoiceDir(ctx))
                                else -> runDownload(ModelKind.ASR, _asr, asrUrls, ModelFiles.asrDir(ctx))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            io.translatetube.local.util.AppLogStore.error(
                                ctx.applicationContext,
                                "ModelDownload",
                                "转写模型下载或安装任务失败",
                                e,
                                diagnosticContext = mapOf(
                                    "模型类别" to "ASR",
                                    "当前选择的本地转写模型" to ModelFiles.selectedLocalAsrModelId(ctx),
                                    "下载阶段" to _asr.value.stageMessage.orEmpty().ifBlank { _asr.value.status.name },
                                    "下载进度百分比" to _asr.value.progress.toString(),
                                    "已下载 MB" to _asr.value.downloadedMB.toString(),
                                    "模型总大小 MB" to _asr.value.totalMB.toString(),
                                ),
                            )
                            Log.e("ModelDL", "ASR 下载任务异常", e)
                            _asr.value = ModelState(status = ModelStatus.FAILED, error = e.message ?: "下载任务异常")
                        } finally {
                            if (worker?.isCancelled == true && isActiveModelState(_asr.value)) {
                                _asr.value = ModelState(status = ModelStatus.FAILED, error = "下载任务已取消")
                            }
                            synchronized(downloadStartLock) {
                                if (asrJob === worker) asrJob = null
                            }
                            clearPauseIfIdle()
                        }
                    }
                    synchronized(downloadStartLock) { asrJob = job }
                }
            }
        } finally {
            releaseDownloadStart(kind)
        }
    }

    fun refreshAsrState() {
        val busy = synchronized(downloadStartLock) {
            ModelKind.ASR in startingDownloads || asrJob?.isActive == true || isActiveModelState(_asr.value)
        }
        if (busy) return
        val ready = runCatching { ModelFiles.asrReady(ctx) }.getOrDefault(false)
        _asr.value = ModelState(
            status = ModelAvailabilityPolicy.initialStatus(ready),
            progress = if (ready) 100 else 0,
        )
    }

    fun cancel(kind: ModelKind) {
        val job = synchronized(downloadStartLock) {
            if (kind == ModelKind.TTS) ttsJob else asrJob
        }
        logModelWarning(
            "用户请求取消模型下载/安装任务",
            details = modelKindDetails(kind) + mapOf(
                "取消前任务是否活跃" to (job?.isActive == true).toString(),
                "取消前下载文件大小 bytes" to File(ctx.cacheDir, "${kind.name}_model.tar.bz2.tmp").length().toString(),
            ),
        )
        when (kind) {
            ModelKind.TTS -> _tts.value = ModelState(status = ModelStatus.FAILED, error = "已取消")
            ModelKind.ASR -> _asr.value = ModelState(status = ModelStatus.FAILED, error = "已取消")
        }
        job?.cancel()
        clearPauseIfIdle()
    }

    /** Toggle pause/resume for all active model downloads from the foreground notification. */
    fun togglePauseAll() {
        val managedActive = ttsJob?.isActive == true || asrJob?.isActive == true ||
            isActiveModelState(_tts.value) || isActiveModelState(_asr.value)
        val piperActive = io.translatetube.local.tts.PiperModels.hasActiveDownloads
        if (!managedActive && !piperActive) {
            logModelInfo(
                "忽略模型下载暂停/恢复操作：当前没有活动任务",
                mapOf("配音任务状态" to _tts.value.status.name, "转写任务状态" to _asr.value.status.name),
            )
            return
        }
        val shouldPause = ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
            managedActive = managedActive,
            managedPaused = isPaused,
            piperActive = piperActive,
            piperPaused = io.translatetube.local.tts.PiperModels.isPaused,
        )
        if (managedActive) setDownloadsPaused(shouldPause)
        if (piperActive) io.translatetube.local.tts.PiperModels.setPaused(shouldPause)
        logModelInfo(
            if (shouldPause) "模型下载任务已暂停" else "模型下载任务已恢复",
            mapOf(
                "配音模型状态" to _tts.value.status.name,
                "转写模型状态" to _asr.value.status.name,
                "管理器任务是否活跃" to managedActive.toString(),
                "Piper 模型任务是否活跃" to piperActive.toString(),
                "管理器是否暂停" to isPaused.toString(),
                "Piper 是否暂停" to io.translatetube.local.tts.PiperModels.isPaused.toString(),
                "状态来源" to "前台通知的全局暂停/恢复操作",
            ),
        )
    }

    private fun setDownloadsPaused(paused: Boolean) {
        synchronized(pauseLock) {
            val current = resumeSignal
            if (paused) {
                if (current == null) resumeSignal = kotlinx.coroutines.CompletableDeferred()
            } else {
                current?.complete(Unit)
                resumeSignal = null
            }
        }
    }

    /** Cancel every active model task and release pause waiters before cancelling the jobs. */
    fun cancelAll() {
        logModelWarning(
            "用户请求取消全部模型下载任务",
            details = mapOf(
                "配音模型状态" to _tts.value.status.name,
                "转写模型状态" to _asr.value.status.name,
                "配音任务是否活跃" to (ttsJob?.isActive == true).toString(),
                "转写任务是否活跃" to (asrJob?.isActive == true).toString(),
                "Piper 活跃任务数" to io.translatetube.local.tts.PiperModels.activeDownloadCount.value.toString(),
            ),
        )
        resumeDownloads()
        io.translatetube.local.tts.PiperModels.cancelAllDownloads()
        if (ttsJob?.isActive == true || isActiveModelState(_tts.value)) cancel(ModelKind.TTS)
        if (asrJob?.isActive == true || isActiveModelState(_asr.value)) cancel(ModelKind.ASR)
    }

    private fun isActiveModelState(state: ModelState): Boolean =
        state.status == ModelStatus.DOWNLOADING || state.status == ModelStatus.EXTRACTING

    private fun resumeDownloads() {
        synchronized(pauseLock) {
            resumeSignal?.complete(Unit)
            resumeSignal = null
        }
    }

    private fun clearPauseIfIdle() {
        // Job cancellation is asynchronous; state is switched to FAILED synchronously by cancel().
        // Use the published phases here so a cancelled last task cannot leave future tasks paused.
        if (!isActiveModelState(_tts.value) && !isActiveModelState(_asr.value)) {
            resumeDownloads()
        }
    }

    /** Suspension checkpoint used by network reads and other suspendable stages. */
    private suspend fun awaitDownloadsResumed() {
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val signal = synchronized(pauseLock) { resumeSignal } ?: return
            signal.await()
        }
    }

    /**
     * Blocking checkpoint for the synchronous archive decoder and checksum reader.
     *
     * The pause signal is shared by TTS and ASR, so cancelling one job must wake only its local
     * waiter; cancelling the shared signal would incorrectly resume or cancel the other download.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private fun awaitDownloadsResumedBlocking(job: Job?) {
        while (true) {
            job?.ensureActive()
            val signal = synchronized(pauseLock) { resumeSignal } ?: return
            val waiter = java.util.concurrent.CountDownLatch(1)
            val resumeHandle = signal.invokeOnCompletion { waiter.countDown() }
            val cancelHandle = job?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is java.util.concurrent.CancellationException) waiter.countDown()
            }
            try {
                if (ModelDownloadWaitPolicy.shouldWait(
                        jobIsActive = job?.isActive ?: true,
                        resumeSignalCompleted = signal.isCompleted,
                    )
                ) {
                    try {
                        waiter.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw kotlinx.coroutines.CancellationException("模型等待已中断").also {
                            it.initCause(interrupted)
                        }
                    }
                }
            } finally {
                resumeHandle.dispose()
                cancelHandle?.dispose()
            }
        }
    }

    private suspend fun runDownload(
        kind: ModelKind,
        state: MutableStateFlow<ModelState>,
        urls: List<String>,
        destDir: File,
    ) {
        val downloadStartedAtMs = System.currentTimeMillis()
        val pkg = File(ctx.cacheDir, "${kind.name}_model.tar.bz2.tmp")
        logModelInfo(
            "模型压缩包下载与安装阶段开始",
            modelKindDetails(kind) + mapOf(
                "候选下载源数量" to urls.size.toString(),
                "目标目录" to destDir.name,
                "缓存目录可用空间 MB" to (ctx.cacheDir.usableSpace / (1024L * 1024L)).toString(),
                "安装目录可用空间 MB" to ((destDir.parentFile ?: ctx.filesDir).usableSpace / (1024L * 1024L)).toString(),
            ),
        )
        state.value = ModelState(status = ModelStatus.DOWNLOADING, totalMB = 0)
        val parentDir = destDir.parentFile ?: throw java.io.IOException("模型目录无效")
        val requiredStorageBytes = when (kind) {
            ModelKind.TTS -> ModelStoragePreflightPolicy.requiredBytes(
                archiveBytes = 147_031_220L,
                extractedBytes = 207L * ModelStoragePreflightPolicy.MIB,
            )
            ModelKind.ASR -> if (ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(destDir.name)) {
                ModelStoragePreflightPolicy.requiredBytes(
                    archiveBytes = 165_783_878L,
                    extractedBytes = 228L * ModelStoragePreflightPolicy.MIB,
                )
            } else {
                ModelStoragePreflightPolicy.requiredBytes(
                    archiveBytes = 207_557_382L,
                    extractedBytes = 160L * ModelStoragePreflightPolicy.MIB,
                )
            }
        }
        val usableSpace = ModelStoragePreflightPolicy.minimumKnownUsableSpace(
            ctx.cacheDir.usableSpace,
            parentDir.usableSpace,
        )
        if (!ModelStoragePreflightPolicy.hasEnoughSpace(usableSpace, requiredStorageBytes)) {
            // A previous force-stop may have left a non-resumable partial archive in cache.
            // Free it before returning a storage error so the user can reclaim space and retry.
            pkg.delete()
            state.value = ModelState(
                status = ModelStatus.FAILED,
                error = "可用存储空间不足：下载和安装该模型预计至少需要 ${ModelStoragePreflightPolicy.roundedUpMiB(requiredStorageBytes)} MB 可用空间，请释放空间后重试。",
            )
            logModelError(
                "模型下载前置检查失败：存储空间不足",
                details = modelKindDetails(kind) + mapOf(
                    "所需最小可用空间 bytes" to requiredStorageBytes.toString(),
                    "估算所需空间 MB" to ModelStoragePreflightPolicy.roundedUpMiB(requiredStorageBytes).toString(),
                    "检测到的最小可用空间 bytes" to usableSpace.toString(),
                    "下载阶段" to "存储空间预检",
                    "临时压缩包是否已清理" to (!pkg.exists()).toString(),
                ),
            )
            return
        }
        var lastError: String? = null
        var primarySourceWasSlow = false
        for ((sourceIndex, url) in urls.withIndex()) {
            awaitDownloadsResumed()
            // Reset the phase before every fallback source. Otherwise an extraction error leaves
            // the UI stuck on "installing" while the manager is downloading the next mirror.
            state.value = ModelState(
                status = ModelStatus.DOWNLOADING,
                progress = 0,
                totalMB = 0,
                stageMessage = when {
                    sourceIndex == 0 -> "正在连接官方模型源…"
                    primarySourceWasSlow -> "官方源速度较慢，正在切换加速源（${sourceIndex + 1}/${urls.size}）…"
                    else -> "官方源不可用，正在切换下载源（${sourceIndex + 1}/${urls.size}）…"
                },
            )
            var downloadedArchiveSha256: String? = null
            logModelInfo(
                "开始尝试模型下载源",
                modelKindDetails(kind) + mapOf(
                    "下载源序号" to (sourceIndex + 1).toString(),
                    "候选下载源总数" to urls.size.toString(),
                    "下载源主机" to (runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "未知"),
                    "当前阶段" to state.value.stageMessage.orEmpty(),
                    "是否使用备用源" to (sourceIndex > 0).toString(),
                ),
            )
            try {
                withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    executeCancellable(http.newCall(req)) { resp ->
                        if (!HttpDownloadResponsePolicy.isCompleteGetResponse(resp.code)) {
                            throw IllegalStateException("模型下载需要完整 HTTP 200 响应，收到 HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IllegalStateException("空响应")
                        val sourceStartedAtNanos = System.nanoTime()
                        val maxArchiveBytes = 512L * 1024 * 1024
                        val expectedBytes = body.contentLength()
                        if (!HttpDownloadResponsePolicy.isValidExpectedLength(expectedBytes, maxArchiveBytes)) {
                            throw IllegalStateException("模型压缩包响应声明的文件大小无效或超过限制")
                        }
                        val total = expectedBytes.takeIf { it > 0L } ?: 0L
                        val downloadDigest = if (kind == ModelKind.TTS || ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(destDir.name)) {
                            java.security.MessageDigest.getInstance("SHA-256")
                        } else null
                        body.byteStream().use { ins ->
                            FileOutputStream(pkg).use { out ->
                                val buf = ByteArray(256 * 1024)
                                var got = 0L
                                var lastProgressBytes = 0L
                                var lastProgressAtNanos = System.nanoTime()
                                if (total > maxArchiveBytes) {
                                    throw IllegalStateException("模型压缩包超过大小限制")
                                }
                                while (true) {
                                    awaitDownloadsResumed()
                                    if (state.value.status == ModelStatus.FAILED) throw kotlinx.coroutines.CancellationException("用户取消")
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val n = io.translatetube.local.util.ProgressSafeInput.read(ins, buf)
                                    if (n < 0) break
                                    got += n
                                    if (got > maxArchiveBytes) throw IllegalStateException("模型压缩包超过大小限制")
                                    val elapsedMillis = (System.nanoTime() - sourceStartedAtNanos) / 1_000_000L
                                    if (HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(sourceIndex, got, elapsedMillis)) {
                                        throw SlowDownloadSourceException(got, elapsedMillis)
                                    }
                                    out.write(buf, 0, n)
                                    downloadDigest?.update(buf, 0, n)
                                    val nowNanos = System.nanoTime()
                                    if (got - lastProgressBytes >= 512L * 1024L ||
                                        nowNanos - lastProgressAtNanos >= 250_000_000L
                                    ) {
                                        val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                                        state.value = state.value.copy(
                                            progress = pct,
                                            downloadedMB = got / 1048576,
                                            totalMB = total / 1048576,
                                            stageMessage = "正在下载模型包…",
                                        )
                                        lastProgressBytes = got
                                        lastProgressAtNanos = nowNanos
                                    }
                                }
                                val finalPct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                                state.value = state.value.copy(
                                    progress = finalPct,
                                    downloadedMB = got / 1048576,
                                    totalMB = total / 1048576,
                                    stageMessage = "正在下载模型包…",
                                )
                                if (!HttpDownloadResponsePolicy.isCompletePayload(
                                        expectedBytes, got, maxArchiveBytes
                                    )
                                ) {
                                    throw java.io.IOException(
                                        "模型压缩包下载不完整或大小异常：预期 ${expectedBytes} 字节，实际收到 ${got} 字节"
                                    )
                                }
                                downloadedArchiveSha256 = downloadDigest?.digest()
                                    ?.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                out.flush()
                                out.fd.sync()
                            }
                        }
                    }
                }
                awaitDownloadsResumed()
                state.value = state.value.copy(progress = 99, stageMessage = "正在校验模型压缩包…")
                // Verify the TTS and SenseVoice archives against official release SHA-256 digests.
                // The current Whisper archive does not publish an asset digest.
                val expectedArchiveSha256 = when {
                    kind == ModelKind.TTS -> "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6"
                    ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(destDir.name) ->
                        "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63"
                    else -> null
                }
                if (expectedArchiveSha256 != null) {
                    val actual = downloadedArchiveSha256
                        ?: throw java.io.IOException("模型压缩包未能完成 SHA-256 校验")
                    if (!actual.equals(expectedArchiveSha256, ignoreCase = true)) {
                        throw java.io.IOException("模型压缩包 SHA-256 校验失败")
                    }
                }

                // 解压（原子：先解到 .extract 再整体 rename 内容）。定期更新状态，避免大模型
                // 解压时看起来像完全卡住。
                awaitDownloadsResumed()
                state.value = state.value.copy(
                    status = ModelStatus.EXTRACTING,
                    progress = 0,
                    stageMessage = "正在解压并安装模型…",
                )
                withContext(Dispatchers.IO) {
                    // Do not pre-create destDir: extraction recovery must be able to distinguish
                    // "destination missing after a crash" from a real installed directory.
                    val extractionJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                    extractTarBz2(
                        pkg = pkg,
                        destDir = destDir,
                        checkActive = { awaitDownloadsResumedBlocking(extractionJob) },
                        onProgress = { extractedBytes ->
                            val handledMB = extractedBytes / (1024L * 1024L)
                            val expectedExtractedBytes = (
                                when {
                                    kind == ModelKind.TTS -> 207L
                                    ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(destDir.name) -> 228L
                                    else -> 160L
                                }
                            ) * ModelStoragePreflightPolicy.MIB
                            val extractionProgress = (
                                extractedBytes * 100L / expectedExtractedBytes
                            ).toInt().coerceIn(0, 99)
                            state.value = state.value.copy(
                                progress = extractionProgress,
                                stageMessage = "正在解压并安装模型（已处理 ${handledMB} MB）",
                            )
                        },
                    )
                }
                if (kind == ModelKind.ASR) {
                    // VAD is its own download stage; expose progress instead of leaving the UI
                    // stuck on "installing" while a network request is running.
                    val vad = File(ModelFiles.asrDir(ctx), "silero_vad.onnx")
                    if (!ModelFiles.isValidVadModel(vad)) {
                        val vadUrls = listOf(
                            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                        )
                        for ((index, url) in vadUrls.withIndex()) {
                            state.value = ModelState(
                                status = ModelStatus.DOWNLOADING,
                                progress = 0,
                                totalMB = 1,
                                stageMessage = if (index == 0) "正在下载语音检测模型…" else "正在切换语音检测模型下载源…",
                            )
                            try {
                                downloadTo(
                                    url,
                                    vad,
                                    expectedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                                    onProgress = { got, total ->
                                        val expected = total.takeIf { it > 0L } ?: 643_854L
                                        state.value = state.value.copy(
                                            progress = if (expected > 0L) (got * 100L / expected).toInt().coerceIn(0, 99) else 0,
                                            downloadedMB = got / (1024L * 1024L),
                                            totalMB = 1,
                                            stageMessage = "正在下载语音检测模型…",
                                        )
                                    },
                                )
                                if (ModelFiles.isValidVadModel(vad)) break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                io.translatetube.local.util.AppLogStore.warning(
                                    ctx.applicationContext,
                                    "VADDownload",
                                    "语音检测 VAD 模型下载源失败",
                                    e,
                                    diagnosticContext = mapOf(
                                        "VAD 下载源序号" to (index + 1).toString(),
                                        "VAD 下载源主机" to (runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "未知"),
                                        "目标文件名" to vad.name,
                                        "当前完整性状态" to ModelFiles.isValidVadModel(vad).toString(),
                                    ),
                                )
                                Log.w("ModelDL", "VAD 下载源失败: ${e.message}")
                            }
                        }
                    }
                    if (!ModelFiles.isValidVadModel(vad)) {
                        pkg.delete()
                        state.value = ModelState(
                            status = ModelStatus.FAILED,
                            error = "本地转写主模型已安装，但语音检测模型完整性校验失败；点击重试只会补下载小型 VAD 模型。",
                        )
                        return
                    }
                }
                val installed = when {
                    kind == ModelKind.TTS -> ModelFiles.ttsReady(ctx)
                    ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(destDir.name) -> ModelFiles.senseVoiceReady(ctx)
                    else -> ModelFiles.whisperReady(ctx)
                }
                if (!installed) throw java.io.IOException("模型文件安装后完整性检查未通过")
                val installedArchiveBytes = pkg.length()
                pkg.delete()
                state.value = state.value.copy(
                    status = ModelStatus.READY,
                    progress = 100,
                    stageMessage = null,
                    error = null,
                )
                Log.i("ModelDL", "$kind 就绪")
                logModelInfo(
                    "模型下载、完整性校验与安装全部完成",
                    modelKindDetails(kind) + mapOf(
                        "最终状态" to state.value.status.name,
                        "最终进度百分比" to state.value.progress.toString(),
                        "下载压缩包大小 MB" to ((installedArchiveBytes + 1_048_575L) / 1_048_576L).toString(),
                        "目标目录" to destDir.name,
                        "目标目录顶层项目数" to destDir.listFiles().orEmpty().size.toString(),
                        "总耗时 ms" to (System.currentTimeMillis() - downloadStartedAtMs).coerceAtLeast(0L).toString(),
                        "最终下载源主机" to (runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "未知"),
                        "最终校验状态" to "模型文件完整性检查通过",
                        "压缩包已清理" to (!pkg.exists()).toString(),
                    ),
                )
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                pkg.delete()
                return
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                if (sourceIndex == 0) {
                    primarySourceWasSlow = e is SlowDownloadSourceException
                }
                io.translatetube.local.util.AppLogStore.warning(
                    ctx.applicationContext,
                    "ModelDownload",
                    "$kind 模型下载源失败，正在尝试下一源",
                    e,
                    diagnosticContext = mapOf(
                        "模型类别" to kind.name,
                        "下载源序号" to (sourceIndex + 1).toString(),
                        "下载源主机" to (runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "未知"),
                        "目标目录类型" to destDir.name,
                        "当前阶段" to state.value.stageMessage.orEmpty().ifBlank { state.value.status.name },
                        "下载进度百分比" to state.value.progress.toString(),
                        "本次失败是否属于主下载源慢响应" to (e is SlowDownloadSourceException).toString(),
                    ),
                )
                Log.w("ModelDL", "$kind 源失败: $lastError，尝试下一源")
            }
        }
        pkg.delete()
        state.value = state.value.copy(status = ModelStatus.FAILED, error = lastError ?: "全部下载源失败")
        logModelError(
            "模型全部下载源尝试失败",
            details = modelKindDetails(kind) + mapOf(
                "候选下载源数量" to urls.size.toString(),
                "所有下载源主机" to urls.map { runCatching { android.net.Uri.parse(it).host }.getOrNull() ?: "未知" }.distinct().joinToString(", "),
                "最后一次失败原因" to (lastError ?: "未提供失败原因").take(360),
                "最终阶段" to state.value.stageMessage.orEmpty().ifBlank { state.value.status.name },
                "累计耗时 ms" to (System.currentTimeMillis() - downloadStartedAtMs).coerceAtLeast(0L).toString(),
                "临时压缩包已清理" to (!pkg.exists()).toString(),
            ),
        )
    }

    /** Whisper 主模型已经存在时，只补下载缺失/损坏的 VAD 文件，避免重新下载近 200 MB 模型包。 */
    private suspend fun runVadOnly(state: MutableStateFlow<ModelState>) {
        val startedAtMs = System.currentTimeMillis()
        val vad = File(ModelFiles.asrDir(ctx), "silero_vad.onnx")
        logModelInfo(
            "转写模型主文件已存在，开始单独补齐 VAD 附属模型",
            mapOf(
                "附属模型文件" to vad.name,
                "当前转写模型" to ModelFiles.selectedLocalAsrModelId(ctx),
                "VAD 文件当前存在" to vad.exists().toString(),
                "VAD 当前完整性" to ModelFiles.isValidVadModel(vad).toString(),
                "候选下载源数量" to "2",
            ),
        )
        var lastError: String? = null
        val urls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        )
        for ((index, url) in urls.withIndex()) {
            state.value = ModelState(
                status = ModelStatus.DOWNLOADING,
                progress = 0,
                totalMB = 1,
                stageMessage = if (index == 0) "正在补下载语音检测模型…" else "正在切换语音检测模型下载源…",
            )
            try {
                downloadTo(
                    url,
                    vad,
                    expectedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                    onProgress = { got, total ->
                        val expected = total.takeIf { it > 0L } ?: 643_854L
                        state.value = state.value.copy(
                            progress = if (expected > 0L) (got * 100L / expected).toInt().coerceIn(0, 99) else 0,
                            downloadedMB = got / (1024L * 1024L),
                            totalMB = 1,
                            stageMessage = "正在下载语音检测模型…",
                        )
                    },
                )
                if (!ModelFiles.asrReady(ctx)) throw java.io.IOException("VAD 模型校验失败")
                state.value = ModelState(
                    status = ModelStatus.READY,
                    progress = 100,
                    downloadedMB = (vad.length() + 1_048_575L) / 1_048_576L,
                    totalMB = 1,
                )
                logModelInfo(
                    "VAD 附属模型补下载成功",
                    mapOf(
                        "附属模型文件" to vad.name,
                        "文件大小 bytes" to vad.length().toString(),
                        "SHA-256 完整性" to "通过",
                        "总耗时 ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L).toString(),
                        "转写模型已就绪" to "true",
                    ),
                )
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        state.value = ModelState(status = ModelStatus.FAILED, error = "补下载 VAD 模型失败：${lastError ?: "网络异常"}")
        logModelError(
            "VAD 附属模型补下载失败",
            details = mapOf(
                "附属模型文件" to vad.name,
                "最后一次失败原因" to (lastError ?: "网络异常").take(360),
                "最终完整性检查" to ModelFiles.isValidVadModel(vad).toString(),
                "总耗时 ms" to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L).toString(),
                "尝试过的下载源主机" to urls.map { runCatching { android.net.Uri.parse(it).host }.getOrNull() ?: "未知" }.distinct().joinToString(", "),
            ),
        )
    }

    private fun sha256(file: File, checkActive: () -> Unit = {}): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        checkActive()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 下载小型附属模型时先写临时文件，校验大小和（若有）SHA-256 后再替换。 */
    private suspend fun downloadTo(
        url: String,
        dest: File,
        onProgress: ((got: Long, total: Long) -> Unit)? = null,
        expectedSha256: String? = null,
    ) = withContext(Dispatchers.IO) {
        val parent = dest.parentFile ?: throw java.io.IOException("模型目录无效")
        if (!parent.exists() && !parent.mkdirs()) throw java.io.IOException("无法创建模型目录")
        val temp = File(parent, dest.name + ".download")
        temp.delete()
        try {
            awaitDownloadsResumed()
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            executeCancellable(http.newCall(req)) { resp ->
                if (!HttpDownloadResponsePolicy.isCompleteGetResponse(resp.code)) {
                    throw IllegalStateException("附属模型下载需要完整 HTTP 200 响应，收到 HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("下载响应为空")
                val maxBytes = 16L * 1024 * 1024
                val expectedBytes = body.contentLength()
                if (!HttpDownloadResponsePolicy.isValidExpectedLength(expectedBytes, maxBytes)) {
                    throw IllegalStateException("附属模型响应声明的文件大小无效或超过限制")
                }
                var written = 0L
                onProgress?.invoke(0L, expectedBytes)
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            awaitDownloadsResumed()
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxBytes) throw IllegalStateException("附属模型超过大小限制")
                            output.write(buffer, 0, count)
                            onProgress?.invoke(written, expectedBytes)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (!HttpDownloadResponsePolicy.isCompletePayload(expectedBytes, written, maxBytes)) {
                    throw java.io.IOException(
                        "附属模型下载不完整或大小异常：预期 ${expectedBytes} 字节，实际收到 ${written} 字节"
                    )
                }
            }
            val checksumJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            if (expectedSha256 != null &&
                !sha256(temp) { checksumJob?.ensureActive() }.equals(expectedSha256, ignoreCase = true)
            ) {
                throw java.io.IOException("附属模型 SHA-256 校验失败")
            }
            AtomicFileInstaller.install(temp, dest)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun isModelDirectoryReady(dir: File): Boolean = when {
        ModelDirectoryKindPolicy.isTtsDirectoryName(dir.name) -> ModelFiles.ttsDirectoryReady(dir)
        ModelDirectoryKindPolicy.isSenseVoiceDirectoryName(dir.name) -> ModelFiles.senseVoiceDirectoryCoreReady(dir)
        else -> ModelFiles.asrDirectoryCoreReady(dir)
    }

    private fun extractTarBz2(
        pkg: File,
        destDir: File,
        checkActive: () -> Unit = {},
        onProgress: ((Long) -> Unit)? = null,
    ) {
        checkActive()
        val parent = destDir.parentFile ?: throw java.io.IOException("模型目录无效")
        if (!parent.exists() && !parent.mkdirs()) throw java.io.IOException("无法创建模型父目录")
        val old = File(parent, destDir.name + ".old")
        // Recover a crash between moving the current model to .old and publishing the staged
        // directory. Never delete the only known-good copy when the destination is missing.
        if (old.exists()) {
            if (!old.isDirectory) throw java.io.IOException("模型备份路径不是目录，无法安全恢复")
            val destinationReady = destDir.exists() && isModelDirectoryReady(destDir)
            val backupReady = isModelDirectoryReady(old)
            when (
                ModelInstallRecoveryPolicy.decide(
                    destinationExists = destDir.exists(),
                    destinationReady = destinationReady,
                    backupExists = true,
                    backupReady = backupReady,
                )
            ) {
                ModelInstallRecoveryPolicy.Action.KEEP_DESTINATION -> {
                    // A fully validated destination wins; a stale backup is no longer required.
                    if (!old.deleteRecursively()) {
                        throw java.io.IOException("模型已就绪，但无法清理上次安装留下的旧备份")
                    }
                }
                ModelInstallRecoveryPolicy.Action.RESTORE_BACKUP -> {
                    val invalid = File(parent, destDir.name + ".invalid")
                    if (invalid.exists() && !invalid.deleteRecursively()) {
                        throw java.io.IOException("无法清理上次模型安装留下的不完整目录")
                    }
                    if (destDir.exists() && !destDir.renameTo(invalid)) {
                        throw java.io.IOException("无法暂存不完整模型以恢复旧版本")
                    }
                    if (!old.renameTo(destDir)) {
                        val restoreError = java.io.IOException("上次模型安装中断，无法恢复保留的模型目录")
                        if (invalid.exists() && !invalid.renameTo(destDir)) {
                            restoreError.addSuppressed(
                                java.io.IOException("恢复失败后的目标目录仍保留在 ${invalid.absolutePath}")
                            )
                        }
                        throw restoreError
                    }
                    // The old backup is now the destination. The invalid interrupted install is
                    // disposable, but failure to delete it must not destroy the recovered model.
                    runCatching { invalid.deleteRecursively() }
                }
                ModelInstallRecoveryPolicy.Action.REMOVE_INVALID_BACKUP,
                ModelInstallRecoveryPolicy.Action.INSTALL_FRESH -> {
                    if (!old.deleteRecursively()) {
                        throw java.io.IOException("无法清理不完整的模型备份，已停止覆盖以保护现有文件")
                    }
                }
            }
        }
        val staging = File(parent, destDir.name + ".extract")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw java.io.IOException("无法创建模型临时目录")
        val rootPath = staging.canonicalFile.path + File.separator
        val maxExtractedBytes = 1024L * 1024 * 1024
        var extractedBytes = 0L
        val progressStep = 8L * 1024 * 1024
        var nextProgressAt = progressStep

        // Reuse a large extraction buffer for the whole archive; BZip2 reads bits in small
        // increments, so buffering the underlying file avoids excessive low-level disk reads.
        val extractionBuffer = ByteArray(256 * 1024)
        val seenRegularFiles = HashSet<String>()
        var archiveRootName: String? = null
        var archiveEntryCount = 0
        try {
            TarArchiveInputStream(
                BZip2CompressorInputStream(
                    BufferedInputStream(FileInputStream(pkg), 256 * 1024)
                )
            ).use { tar ->
                while (true) {
                    checkActive()
                    val entry = tar.nextTarEntry ?: break
                    archiveEntryCount++
                    if (!ArchiveEntryLimitPolicy.isWithinLimit(archiveEntryCount)) {
                        throw java.io.IOException("模型压缩包条目数量超过安全上限")
                    }
                    if (entry.isSymbolicLink || entry.isLink) {
                        throw java.io.IOException("模型压缩包包含链接条目")
                    }
                    val safePath = ModelArchiveEntryPolicy.normalize(
                        entryName = entry.name,
                        expectedRootName = archiveRootName,
                    ) ?: throw java.io.IOException("模型压缩包包含绝对路径、路径穿越或多个顶层目录")
                    if (archiveRootName == null) archiveRootName = safePath.rootName
                    val relative = safePath.relativePath.replace('/', File.separatorChar)
                    if (relative.isEmpty()) {
                        if (!entry.isDirectory) throw java.io.IOException("模型压缩包根路径不是目录")
                        continue
                    }
                    // The official SenseVoice archive also ships test WAVs. Install only the
                    // recognizer model and vocabulary, not the example audio that is never used.
                    if (destDir.name == "sensevoice" &&
                        relative != "model.int8.onnx" && relative != "tokens.txt"
                    ) continue
                    val output = File(staging, relative).canonicalFile
                    if (!output.path.startsWith(rootPath)) {
                        throw java.io.IOException("模型压缩包路径越界")
                    }
                    if (entry.isDirectory) {
                        if (output.exists() && !output.isDirectory) {
                            throw java.io.IOException("模型压缩包将普通文件重复用作目录：$relative")
                        }
                        if (!output.exists() && !output.mkdirs()) throw java.io.IOException("无法创建模型子目录")
                        continue
                    }
                    if (!entry.isFile || entry.size < 0L) {
                        throw java.io.IOException("模型压缩包包含不支持的条目")
                    }
                    if (!seenRegularFiles.add(relative)) {
                        throw java.io.IOException("模型压缩包重复包含同一路径的文件：$relative")
                    }
                    // Subtract the already-bounded total instead of adding an untrusted tar
                    // entry size, which could overflow Long before the limit comparison.
                    if (entry.size > maxExtractedBytes - extractedBytes) {
                        throw java.io.IOException("解压后的模型超过大小限制")
                    }
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw java.io.IOException("无法创建模型子目录")
                    }
                    BufferedOutputStream(FileOutputStream(output), 256 * 1024).use { out ->
                        var fileBytes = 0L
                        while (true) {
                            checkActive()
                            val count = io.translatetube.local.util.ProgressSafeInput.read(tar, extractionBuffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (extractedBytes > maxExtractedBytes || fileBytes > entry.size) {
                                throw java.io.IOException("解压后的模型超过大小限制")
                            }
                            out.write(extractionBuffer, 0, count)
                            if (extractedBytes >= nextProgressAt) {
                                onProgress?.invoke(extractedBytes)
                                nextProgressAt = (extractedBytes / progressStep + 1L) * progressStep
                            }
                        }
                        if (fileBytes != entry.size) throw java.io.IOException("模型文件长度校验失败")
                    }
                }
            }

            checkActive()
            // 不完整或伪造的压缩包不能覆盖已经可用的模型。
            val required = when (destDir.name) {
                "tts" -> listOf(
                    "model.int8.onnx" to (1L * 1024 * 1024),
                    "voices.bin" to (1L * 1024 * 1024),
                )
                "sensevoice" -> listOf(
                    "model.int8.onnx" to SenseVoiceModelFilePolicy.MIN_MODEL_BYTES,
                    "tokens.txt" to SenseVoiceModelFilePolicy.MIN_TOKENS_BYTES,
                )
                else -> listOf(
                    "base-encoder.int8.onnx" to WhisperModelFilePolicy.MIN_ONNX_BYTES,
                    "base-decoder.int8.onnx" to WhisperModelFilePolicy.MIN_ONNX_BYTES,
                    "base-tokens.txt" to WhisperModelFilePolicy.MIN_TOKENS_BYTES,
                )
            }
            if (required.any { (name, minimumBytes) ->
                    val file = File(staging, name)
                    !file.isFile || file.length() < minimumBytes
                }) {
                throw java.io.IOException(
                    when (destDir.name) {
                        "tts" -> "模型包缺少必要文件或文件体积异常，已取消安装"
                        "sensevoice" -> "SenseVoice 模型包缺少 model.int8.onnx 或 tokens.txt，已取消安装"
                        else -> "Whisper 模型包缺少 encoder、decoder 或 base-tokens.txt 词表，已取消安装"
                    }
                )
            }

            when (destDir.name) {
                "sensevoice" -> if (!ModelFiles.senseVoiceDirectoryCoreReady(staging)) {
                    throw java.io.IOException("SenseVoice 模型核心文件校验失败：model.int8.onnx 或 tokens.txt 不完整")
                }
                "tts" -> Unit
                else -> if (!ModelFiles.asrDirectoryCoreReady(staging)) {
                    throw java.io.IOException("Whisper 模型核心文件校验失败：缺少 base-tokens.txt 或模型文件不完整")
                }
            }

            if (destDir.name == "tts") {
                ModelFiles.installBundledTtsSupportFiles(ctx, staging)
                val missing = ModelFiles.missingTtsRuntimeFiles(staging)
                if (missing.isNotEmpty()) {
                    throw java.io.IOException(
                        "Kokoro 模型包缺少运行文件：${missing.joinToString("、")}，已取消安装"
                    )
                }
            }

            checkActive()
            val hadOld = destDir.exists()
            if (hadOld && !destDir.renameTo(old)) throw java.io.IOException("无法备份现有模型目录")
            if (!staging.renameTo(destDir)) {
                val installError = java.io.IOException("无法安装新模型目录")
                if (hadOld && !old.renameTo(destDir)) {
                    installError.addSuppressed(java.io.IOException("安装失败后无法恢复旧模型目录；备份仍保留在 ${old.absolutePath}"))
                }
                throw installError
            }
            // The destination has been atomically published; an undeletable backup is safe and
            // will be reconciled at the start of the next installation attempt.
            runCatching { old.deleteRecursively() }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }
}
