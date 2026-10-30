package io.translatetube.local.tts

import io.translatetube.local.util.HttpDownloadResponsePolicy
import io.translatetube.local.util.HttpDownloadSpeedPolicy
import io.translatetube.local.util.SlowDownloadSourceException
import io.translatetube.local.data.ResumableModelArchiveDownloader
import io.translatetube.local.asr.AtomicFileInstaller

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.translatetube.local.yt.executeCancellableHttpCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Piper（VITS 架构）多语种 TTS：补足 Kokoro 未覆盖的小语种。
 * 模型来自 k2-fsa/sherpa-onnx 官方 tts-models release（Apache-2.0），
 * 单模型 20~130MB，软件内下载，落盘 models/tts/piper/<id>/。
 *
 * 目录布局：
 *   model.onnx / tokens.txt / espeak-ng-data/
 */
class PiperTts(context: Context, private val modelId: String) : TtsEngine {
    override val name = "Piper"
    private val appCtx = context.applicationContext
    private val dir: File = PiperModels.modelDir(appCtx, modelId)
    private val definition: PiperModelDef = PiperModels.byId(modelId)
        ?: throw IllegalArgumentException("未知 Piper 模型：$modelId")
    private val model: File = dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("onnx", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?.firstOrNull()
        ?: File(dir, "model.onnx")

    private val modelCacheKey: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Preserve the historical identity for Chinese models. For other languages the active
        // runtime also consumes espeak-ng-data, so its recursive content signature must be part
        // of the cache key too; hashing is done once per engine, never for every cue lookup.
        val signature = dir.listFiles().orEmpty()
            .filter {
                it.isFile && (
                    it.extension.equals("onnx", true) ||
                        it.name in setOf("tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst")
                    )
            }
            .sortedBy { it.name }
            .joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }
        val espeakSignature = if (definition.supportsChineseOutput) {
            ""
        } else {
            "-espeak-${PiperTtsCacheIdentityPolicy.espeakDataSignature(File(dir, "espeak-ng-data"))}"
        }
        "$name-$modelId-$signature$espeakSignature"
    }

    override val cacheKey: String get() = modelCacheKey
    // PiperTts currently passes sid=0 to sherpa-onnx for every synthesis request.
    // Speaker labels therefore do not alter its output and must not fragment the PCM cache.
    override val speakerIdAffectsOutput: Boolean = false

    private val tts: OfflineTts
    private val guard: NativeResourceGuard

    init {
        if (!NativeTtsRuntimeGate.awaitDeferredReleases()) {
            android.util.Log.w("PiperTts", "Timed out waiting for retired native TTS releases")
        }
        val tokens = File(dir, "tokens.txt")
        val dataDir = File(dir, "espeak-ng-data")
        require(model.isFile) { "Piper 模型文件缺失：${model.absolutePath}" }
        require(model.length() >= PiperModelFilePolicy.MIN_ONNX_BYTES) {
            "Piper 模型文件可能未下载完整：路径=${model.absolutePath}，文件大小=${model.length()} 字节，最低要求=${PiperModelFilePolicy.MIN_ONNX_BYTES} 字节"
        }
        require(tokens.isFile && tokens.length() > 0L) {
            "Piper 词表文件缺失或为空：${tokens.absolutePath}，文件大小=${tokens.length()} 字节"
        }
        val lexicon = File(dir, "lexicon.txt")
        val ruleFsts = PiperModelFilePolicy.CHINESE_RULE_FST_NAMES.map { File(dir, it) }
        if (definition.supportsChineseOutput) {
            val missingOrEmptyResources = buildList {
                if (!lexicon.isFile || lexicon.length() <= 0L) {
                    add("中文词典 lexicon.txt 缺失或为空：${lexicon.absolutePath}（${lexicon.length()} 字节）")
                }
                ruleFsts.filter { !it.isFile || it.length() <= 0L }.forEach { file ->
                    add("中文发音规则文件 ${file.name} 缺失或为空：${file.absolutePath}（${file.length()} 字节）")
                }
            }
            require(missingOrEmptyResources.isEmpty()) {
                "中文 Piper 模型资源不完整（模型 ID=$modelId）：${missingOrEmptyResources.joinToString("；")}"
            }
        } else {
            require(dataDir.isDirectory) { "Piper 语音数据目录缺失：${dataDir.absolutePath}" }
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    lexicon = if (definition.supportsChineseOutput) lexicon.absolutePath else "",
                    dataDir = if (definition.supportsChineseOutput) "" else dataDir.absolutePath,
                ),
                numThreads = TtsCpuThreadPolicy.recommendedThreads(appCtx),
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = if (definition.supportsChineseOutput) {
                ruleFsts.joinToString(",") { it.absolutePath }
            } else "",
            maxNumSentences = 2,
        )
        // assets=null → use absolute filesystem paths just like Kokoro.
        val nativeTts = OfflineTts(null, config)
        tts = nativeTts
        guard = NativeResourceGuard("Piper") { nativeTts.release() }
    }

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text, speed, speakerId) {}

    override fun synthCancellable(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio =
        synthInternal(text, speed, speakerId, checkActive)

    private fun synthInternal(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio {
        checkActive()
        if (text.isBlank()) return TtsAudio(FloatArray(0), 22050)
        TtsTextPolicy.validate(text)
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) return TtsAudio(FloatArray(0), 22050)
        val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
        val sid = 0
        val audio = guard.use { tts.generate(normalizedText, sid, safeSpeed) }
        checkActive()
        val sampleRate = audio.sampleRate
        if (sampleRate !in 8_000..192_000 || audio.samples.isEmpty()) {
            throw IllegalStateException(
                "Piper 模型返回无效音频：采样率=${sampleRate} Hz，样本数=${audio.samples.size}；请检查模型文件和本次推理结果"
            )
        }
        val clean = TtsPcmSanitizer.sanitizeInPlace(audio.samples, checkActive)
        val trimmed = TtsEdgeSilenceTrimmer.trim(clean, sampleRate, checkActive)
        checkActive()
        return TtsAudio(trimmed, sampleRate)
    }

    override fun close() = guard.close()
}

/** Piper 模型定义（k2-fsa 官方 release 直链）。 */
data class PiperModelDef(
    val id: String,
    val langLabel: String,
    val voiceLabel: String,
    val archive: String,
    val sizeMB: Int,
    /** The current translation pipeline speaks Chinese, so only these models are compatible. */
    val supportsChineseOutput: Boolean = false,
    /** SHA-256 published by the upstream GitHub release API; null only when upstream omits it. */
    val archiveSha256: String? = null,
)

enum class PiperModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    FAILED,
    CANCELLED,
}

data class PiperModelDownloadState(
    val status: PiperModelDownloadStatus = PiperModelDownloadStatus.NOT_DOWNLOADED,
    val progress: Int = 0,
    val stageMessage: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = status == PiperModelDownloadStatus.DOWNLOADING ||
            status == PiperModelDownloadStatus.EXTRACTING
}

/** Piper 模型清单、安装状态与下载解压管理。 */
object PiperModels {
    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    val catalog: List<PiperModelDef> = listOf(
        PiperModelDef("en_us_amy_low", "英语(美)", "Amy 女声", "vits-piper-en_US-amy-low.tar.bz2", 64, archiveSha256 = "c70f5284a09a7fd4ed203b39b2ff51cac1432b422b852eb647b481dade3cf639"),
        PiperModelDef("en_us_lessac_medium", "英语(美)", "Lessac 女声·高清", "vits-piper-en_US-lessac-medium.tar.bz2", 64, archiveSha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"),
        PiperModelDef("en_gb_alba_medium", "英语(英)", "Alba 女声", "vits-piper-en_GB-alba-medium.tar.bz2", 64, archiveSha256 = "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1"),
        PiperModelDef("fr_siwis_low", "法语", "Siwis 女声", "vits-piper-fr_FR-siwis-low.tar.bz2", 26, archiveSha256 = "3d69170c160c8375c4123901a72a3845222b39456d39ab74f5bbd7310952b5af"),
        PiperModelDef("de_thorsten_low", "德语", "Thorsten 男声", "vits-piper-de_DE-thorsten-low.tar.bz2", 64, archiveSha256 = "41fab35910fdcec4696b031951d8fd6c262e594cf77b35e1068fadbeb5a091a6"),
        PiperModelDef("es_mls_10246_low", "西班牙语", "DaveFX 音色", "vits-piper-es_ES-davefx-medium.tar.bz2", 64, archiveSha256 = "a3f6beb54a9cb893279f72978a22f807a4d9fc9c7848157b524d5cc7b7f58b22"),
        PiperModelDef("ru_irina_medium", "俄语", "Irina 女声", "vits-piper-ru_RU-irina-medium.tar.bz2", 64, archiveSha256 = "1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008"),
        PiperModelDef("it_riccardo_xlow", "意大利语", "Riccardo 男声", "vits-piper-it_IT-riccardo-x_low.tar.bz2", 26, archiveSha256 = "8ad0791558ae2b3e96054fc5756ae3ae2f6457fb1765bc01f43bbab85e0a7021"),
        PiperModelDef("pt_edresson_low", "葡萄牙语(巴西)", "Edresson 男声", "vits-piper-pt_BR-edresson-low.tar.bz2", 64, archiveSha256 = "670569e755cc48cda0a2f6a3ccba0698815566297069961edcd5f7daaf3d47a5"),
        PiperModelDef("pl_darkman_medium", "波兰语", "Darkman 男声", "vits-piper-pl_PL-darkman-medium.tar.bz2", 64, archiveSha256 = "444727aa46eb6db645a2bc88fe73868e4bd7596b7f56ca39fad5ef53c41210d4"),
        PiperModelDef("uk_ukrainian_tts", "乌克兰语", "Ukrainian-TTS 女声", "vits-piper-uk_UA-ukrainian_tts-medium.tar.bz2", 77, archiveSha256 = "92be6ff21ebb1cbcc0c71121a6437db8d060cee5785287aaa6a19a2548f59c03"),
        PiperModelDef("fa_amir_medium", "波斯语", "Amir 男声", "vits-piper-fa_IR-amir-medium.tar.bz2", 64, archiveSha256 = "46af65c1cdd86ec300db3998f2bf31a68d9e38da930c0655c1ff6f610e84f864"),
        PiperModelDef(
            "zh_cn_chaowen_medium", "中文", "超文女声",
            "vits-piper-zh_CN-chaowen-medium.tar.bz2", 58,
            supportsChineseOutput = true,
            archiveSha256 = "1b69bdcf10bb6331c7f8a7591940febeb175c6bb86caa3802d96531f8e4ad211",
        ),
        PiperModelDef(
            "zh_cn_xiao_ya_medium", "中文", "晓雅女声",
            "vits-piper-zh_CN-xiao_ya-medium.tar.bz2", 58,
            supportsChineseOutput = true,
            archiveSha256 = "9396a3dffbb95b037acaa18094500f58d0db9a7c4f2689554e2539717cf0db65",
        ),
    )

    fun byId(id: String): PiperModelDef? = catalog.firstOrNull { it.id == id }

    fun piperRoot(ctx: Context): File = File(io.translatetube.local.data.ModelFiles.ttsDir(ctx), "piper")

    fun modelDir(ctx: Context, id: String): File = File(piperRoot(ctx), id)

    fun installedIds(ctx: Context): Set<String> =
        piperRoot(ctx).listFiles { f ->
            if (!f.isDirectory) return@listFiles false
            val definition = byId(f.name) ?: return@listFiles false
            complete(f, definition)
        }?.map { it.name }?.toSet() ?: emptySet()

    /** 校验目录内三个关键资源齐全。 */
    /** File names alone are not enough: an interrupted download can leave a truncated ONNX. */
    private fun complete(dir: File, definition: PiperModelDef): Boolean {
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.associate { it.name to it.length() }
            .orEmpty()
        return PiperModelFilePolicy.isComplete(
            rootFiles = files,
            hasEspeakData = File(dir, "espeak-ng-data").isDirectory,
            requiresChineseAssets = definition.supportsChineseOutput,
        )
    }

    fun modelReady(ctx: Context, id: String): Boolean {
        val definition = byId(id) ?: return false
        return complete(modelDir(ctx, id), definition)
    }

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed

    private val downloadStateLock = Any()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val _downloadStates = MutableStateFlow<Map<String, PiperModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, PiperModelDownloadState>> = _downloadStates

    val hasActiveDownloads: Boolean
        get() = PiperTaskActivityPolicy.isActive(
            stateActive = _downloadStates.value.values.any { it.isActive },
            jobPendingOrRunning = synchronized(downloadStateLock) {
                downloadJobs.values.any { !it.isCompleted && !it.isCancelled }
            },
            downloadCount = _activeDownloadCount.value,
        )

    private val pauseLock = Any()
    @Volatile private var resumeSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val isPaused: Boolean get() = synchronized(pauseLock) { resumeSignal != null }

    // Counts queued and active downloads so cache cleanup cannot delete an archive in use.
    private val installMutex = Mutex()
    private val activeDownloadLock = Any()
    private var activeDownloadRequests = 0
    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount

    private fun updateDownloadState(id: String, transform: (PiperModelDownloadState) -> PiperModelDownloadState) {
        synchronized(downloadStateLock) {
            val current = _downloadStates.value[id] ?: PiperModelDownloadState()
            _downloadStates.value = _downloadStates.value + (id to transform(current))
        }
    }

    private fun publishProgress(id: String, progress: Int) {
        updateDownloadState(id) { current ->
            if (current.status == PiperModelDownloadStatus.READY) current
            else current.copy(progress = progress.coerceIn(0, 100), error = null)
        }
    }

    private fun publishStage(id: String, stage: String) {
        val status = PiperDownloadStagePolicy.statusFor(stage)
        updateDownloadState(id) { current ->
            current.copy(
                status = status,
                progress = when (status) {
                    PiperModelDownloadStatus.READY -> 100
                    PiperModelDownloadStatus.EXTRACTING -> 0
                    else -> current.progress
                },
                stageMessage = stage,
                error = null,
            )
        }
    }

    fun startDownload(ctx: Context, id: String, scope: CoroutineScope): Boolean {
        val definition = byId(id) ?: throw IllegalArgumentException("未知 Piper 模型：$id")
        if (modelReady(ctx, id)) {
            refreshInstalled(ctx)
            updateDownloadState(id) {
                PiperModelDownloadState(
                    status = PiperModelDownloadStatus.READY,
                    progress = 100,
                    stageMessage = "Piper 模型已就绪",
                )
            }
            return false
        }

        val job = synchronized(downloadStateLock) {
            if (downloadJobs[id]?.let { !it.isCompleted && !it.isCancelled } == true) return false
            _downloadStates.value = _downloadStates.value + (
                id to PiperModelDownloadState(
                    status = PiperModelDownloadStatus.DOWNLOADING,
                    progress = 0,
                    stageMessage = "正在准备下载…",
                )
            )
            scope.launch(context = Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val ownerJob = currentCoroutineContext()[Job]
                try {
                    download(
                        ctx = ctx.applicationContext,
                        id = definition.id,
                        onProgress = { progress -> publishProgress(definition.id, progress) },
                        checkActive = { ownerJob?.ensureActive() },
                        onStage = { stage -> publishStage(definition.id, stage) },
                    )
                    refreshInstalled(ctx)
                    updateDownloadState(definition.id) {
                        PiperModelDownloadState(
                            status = PiperModelDownloadStatus.READY,
                            progress = 100,
                            stageMessage = "Piper 模型已就绪",
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    updateDownloadState(definition.id) {
                        it.copy(
                            status = PiperModelDownloadStatus.CANCELLED,
                            stageMessage = "下载已取消",
                            error = "下载已取消",
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    updateDownloadState(definition.id) {
                        it.copy(
                            status = PiperModelDownloadStatus.FAILED,
                            stageMessage = null,
                            error = error.message ?: "模型下载失败",
                        )
                    }
                } finally {
                    synchronized(downloadStateLock) {
                        if (downloadJobs[definition.id] === ownerJob) {
                            downloadJobs.remove(definition.id)
                        }
                    }
                    refreshInstalled(ctx)
                }
            }.also { downloadJobs[id] = it }
        }

        // Keep the model task alive across settings navigation and app backgrounding.
        runCatching {
            val intent = android.content.Intent(
                ctx.applicationContext,
                io.translatetube.local.data.ModelDownloadForegroundService::class.java,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
        }.onFailure {
            android.util.Log.w("PiperModels", "无法启动模型下载前台服务：${it.message}")
        }
        job.start()
        return true
    }

    fun cancelDownload(id: String) {
        val job = synchronized(downloadStateLock) { downloadJobs[id] }
        if (job == null) return
        updateDownloadState(id) {
            it.copy(
                status = PiperModelDownloadStatus.CANCELLED,
                stageMessage = "正在取消…",
                error = "下载已取消",
            )
        }
        job.cancel(kotlinx.coroutines.CancellationException("用户取消 Piper 模型下载"))
    }

    fun cancelAllDownloads() {
        val ids = synchronized(downloadStateLock) { downloadJobs.keys.toList() }
        ids.forEach(::cancelDownload)
        resumeDownloads()
    }

    fun setPaused(paused: Boolean) {
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

    private fun resumeDownloads() {
        synchronized(pauseLock) {
            resumeSignal?.complete(Unit)
            resumeSignal = null
        }
    }

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
                if (job?.isActive != false && !signal.isCompleted) {
                    try {
                        waiter.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw kotlinx.coroutines.CancellationException("Piper 模型等待已中断").also {
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

    fun refreshInstalled(ctx: Context) {
        val installedIds = installedIds(ctx)
        _installed.value = installedIds
        synchronized(downloadStateLock) {
            var states = _downloadStates.value
            for (definition in catalog) {
                if (downloadJobs[definition.id]?.isActive == true) continue
                val old = states[definition.id]
                states = when {
                    definition.id in installedIds ->
                        states + (definition.id to PiperModelDownloadState(
                            status = PiperModelDownloadStatus.READY,
                            progress = 100,
                            stageMessage = "Piper 模型已就绪",
                        ))
                    old?.status == PiperModelDownloadStatus.READY ->
                        states + (definition.id to PiperModelDownloadState())
                    else -> states
                }
            }
            _downloadStates.value = states
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并安装一个 Piper 模型包。
     * @param onProgress 0..100
     * @param checkActive 协作式取消检查点（与 SpeakerDiarizer 相同约定）
     */
    suspend fun download(
        ctx: Context,
        id: String,
        onProgress: (Int) -> Unit,
        checkActive: () -> Unit = {},
        onStage: (String) -> Unit = {},
    ) {
        val def = byId(id) ?: throw IllegalArgumentException("未知 Piper 模型：$id")
        val callingJob = currentCoroutineContext()[Job]
        val guardedCheck: () -> Unit = {
            awaitDownloadsResumedBlocking(callingJob)
            checkActive()
            callingJob?.ensureActive()
        }
        synchronized(activeDownloadLock) {
            activeDownloadRequests++
            _activeDownloadCount.value = activeDownloadRequests
        }
        try {
            // Archive and extraction paths are deterministic. Serialize installs to avoid
            // concurrent callers truncating the same download or deleting each other's staging dir.
            installMutex.withLock {
                guardedCheck()
                download(ctx, def, onProgress, onStage, guardedCheck)
            }
        } finally {
            synchronized(activeDownloadLock) {
                activeDownloadRequests = (activeDownloadRequests - 1).coerceAtLeast(0)
                _activeDownloadCount.value = activeDownloadRequests
            }
        }
    }

    private suspend fun download(
        ctx: Context,
        def: PiperModelDef,
        onProgress: (Int) -> Unit,
        onStage: (String) -> Unit,
        checkActive: () -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        checkActive()
        val root = piperRoot(ctx)
        if (!root.isDirectory && !root.mkdirs()) {
            throw java.io.IOException("无法创建 Piper 模型目录：${root.absolutePath}")
        }
        val dest = modelDir(ctx, def.id)
        val archive = File(ctx.cacheDir, "piper-model-${def.id}.tar.bz2.tmp")
        val staging = File(root, "${def.id}.extract")
        val backup = File(root, "${def.id}.backup")
        if (dest.exists() && !dest.isDirectory) {
            throw java.io.IOException("Piper 模型目标路径不是目录，已停止覆盖以保护现有文件")
        }
        if (complete(dest, def)) {
            // A crash after publishing a complete model may leave a redundant backup behind.
            if (backup.exists()) {
                runCatching { if (backup.isDirectory) backup.deleteRecursively() else backup.delete() }
            }
            refreshInstalled(ctx)
            archive.delete()
            ResumableModelArchiveDownloader.cleanupPartialFiles(archive)
            return@withContext
        }

        onStage("正在检查模型下载和安装所需空间…")
        val estimatedArchiveBytes = def.sizeMB.toLong() * io.translatetube.local.data.ModelStoragePreflightPolicy.MIB
        val archiveUrls = PiperDownloadSourcePolicy.urls("$BASE/${def.archive}")
        val alreadyDownloadedBytes = maxOf(
            archive.length(),
            archiveUrls.maxOfOrNull { url -> ResumableModelArchiveDownloader.partFileFor(archive, url).length() } ?: 0L,
        ).coerceAtMost(estimatedArchiveBytes)
        val requiredStorageBytes = io.translatetube.local.data.ModelStoragePreflightPolicy.requiredBytes(
            archiveBytes = (estimatedArchiveBytes - alreadyDownloadedBytes).coerceAtLeast(0L),
            extractedBytes = def.sizeMB.toLong() * 2L * io.translatetube.local.data.ModelStoragePreflightPolicy.MIB,
        )
        val usableSpace = io.translatetube.local.data.ModelStoragePreflightPolicy.minimumKnownUsableSpace(
            ctx.cacheDir.usableSpace,
            root.usableSpace,
        )
        if (!io.translatetube.local.data.ModelStoragePreflightPolicy.hasEnoughSpace(
                usableSpace,
                requiredStorageBytes,
            )
        ) {
            throw java.io.IOException(
                "存储空间不足：下载和安装该 Piper 模型预计至少需要 ${io.translatetube.local.data.ModelStoragePreflightPolicy.roundedUpMiB(requiredStorageBytes)} MB 可用空间。"
            )
        }

        try {
            // Restore before any network work. A failed retry must not strand the last usable
            // model in .backup merely because the download source is temporarily unavailable.
            if (backup.exists()) {
                if (!backup.isDirectory) {
                    throw java.io.IOException("Piper 模型备份路径不是目录，无法安全恢复")
                }
                val destinationIsComplete = complete(dest, def)
                val backupIsComplete = complete(backup, def)
                if (PiperModelFilePolicy.shouldRestoreBackup(
                        backupExists = true,
                        backupIsComplete = backupIsComplete,
                        destinationIsComplete = destinationIsComplete,
                    )
                ) {
                    val invalid = File(root, "${def.id}.invalid")
                    if (invalid.exists() && !invalid.deleteRecursively()) {
                        throw java.io.IOException("无法清理上次损坏的 Piper 模型副本")
                    }
                    if (dest.exists() && !dest.renameTo(invalid)) {
                        throw java.io.IOException("无法暂存损坏的 Piper 模型以恢复旧版本")
                    }
                    if (!backup.renameTo(dest)) {
                        if (invalid.exists()) runCatching { invalid.renameTo(dest) }
                        throw java.io.IOException("上次 Piper 模型安装中断，无法恢复原模型")
                    }
                    if (invalid.exists()) runCatching { invalid.deleteRecursively() }
                } else if (!backup.deleteRecursively()) {
                    // A backup is not trusted merely because it exists: an interrupted old download
                    // may have left a truncated model there. Delete only that unusable backup and
                    // download/replace the destination from a newly validated staging directory.
                    throw java.io.IOException("无法清理不完整的 Piper 模型备份")
                }
            }
            downloadArchiveWithFallback(
                archive = archive,
                definition = def,
                onProgress = onProgress,
                onStage = onStage,
                checkActive = checkActive,
            )

            checkActive()
            if (staging.exists() && !staging.deleteRecursively()) {
                throw java.io.IOException("无法清理上次未完成的 Piper 解压目录")
            }
            if (!staging.mkdirs()) throw java.io.IOException("无法创建 Piper 临时解压目录")
            onProgress(70)
            onStage("正在解压并校验 Piper 模型…")

            extractArchive(archive, staging, checkActive) { extractedBytes ->
                onStage("正在解压并安装模型（已处理 ${extractedBytes / (1024L * 1024L)} MB）…")
            }
            if (!complete(staging, def)) {
                throw java.io.IOException("Piper 模型包不完整：ONNX（至少 1 MB）、tokens.txt 及所需语言资源均须完整")
            }
            checkActive()
            onStage("模型文件校验通过，正在安装…")

            val hadDestination = dest.exists()
            if (hadDestination && !dest.renameTo(backup)) {
                throw java.io.IOException("无法备份现有 Piper 模型，旧模型仍保留")
            }
            if (!staging.renameTo(dest)) {
                val installError = java.io.IOException("无法发布新的 Piper 模型")
                if (hadDestination && backup.exists() && !backup.renameTo(dest)) {
                    installError.addSuppressed(
                        java.io.IOException("新模型发布失败，旧模型仍保留在 ${backup.absolutePath}")
                    )
                }
                throw installError
            }
            if (backup.exists()) runCatching { backup.deleteRecursively() }
            onProgress(100)
            onStage("Piper 模型已就绪")
            refreshInstalled(ctx)
            archive.delete()
            ResumableModelArchiveDownloader.cleanupPartialFiles(archive)
        } catch (error: Exception) {
            // Never delete the published model on a failed download or extraction. Only staging is disposable.
            runCatching { staging.deleteRecursively() }
            throw error
        }
    }

    /**
     * Try the official release first, then mirrors. Each source has a separate resumable part file;
     * transient failures preserve the partial download, while failed integrity checks discard it.
     */
    private suspend fun downloadArchiveWithFallback(
        archive: File,
        definition: PiperModelDef,
        onProgress: (Int) -> Unit,
        onStage: (String) -> Unit,
        checkActive: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val urls = PiperDownloadSourcePolicy.urls("$BASE/${definition.archive}")
        if (urls.isEmpty()) throw java.io.IOException("Piper 模型下载地址为空")

        if (ResumableModelArchiveDownloader.isReusableArchive(
                archive, MAX_ARCHIVE_BYTES, definition.archiveSha256, checkActive,
            )
        ) {
            onProgress(100)
            onStage("模型包已下载并校验通过，继续解压安装…")
            return@withContext
        }
        if (archive.exists() && !archive.delete()) {
            throw java.io.IOException("无法清理校验失败的 Piper 模型压缩包")
        }

        var lastError: Exception? = null
        var primarySourceWasSlow = false
        val orderedSources = ResumableModelArchiveDownloader.orderSourcesByResumeProgress(archive, urls)
        for ((sourceIndex, url) in orderedSources) {
            checkActive()
            val partFile = ResumableModelArchiveDownloader.partFileFor(archive, url)
            val priorBytes = partFile.length()
            val attemptLabel = orderedSources.indexOf(sourceIndex to url) + 1
            onProgress(
                if (priorBytes > 0L && definition.sizeMB > 0) {
                    (priorBytes * 100L / (definition.sizeMB.toLong() * 1024L * 1024L)).toInt().coerceIn(0, 99)
                } else 0
            )
            onStage(
                when {
                    priorBytes > 0L -> "正在续传上次未完成的 Piper 模型包（已下载 " + (priorBytes / (1024L * 1024L)) + " MB）…"
                    sourceIndex == 0 -> "正在连接 Piper 官方模型源…"
                    primarySourceWasSlow -> "官方源下载速度较慢，正在切换加速源（" + attemptLabel + "/" + urls.size + "）…"
                    else -> "官方源不可用，正在切换下载源（" + attemptLabel + "/" + urls.size + "）…"
                }
            )
            try {
                val result = ResumableModelArchiveDownloader.download(
                    client = http,
                    url = url,
                    partFile = partFile,
                    maxBytes = MAX_ARCHIVE_BYTES,
                    expectedSha256 = definition.archiveSha256,
                    checkActive = checkActive,
                    shouldFallbackToMirror = { received, elapsed ->
                        HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(sourceIndex, received, elapsed)
                    },
                    onProgress = { downloaded, total ->
                        val percent = if (total > 0L) {
                            (downloaded.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(0, 99)
                        } else {
                            (downloaded.toDouble() * 100.0 /
                                (definition.sizeMB.toLong() * 1024.0 * 1024.0)).toInt().coerceIn(0, 99)
                        }
                        onProgress(percent)
                    },
                )
                if (definition.archiveSha256 != null &&
                    !result.sha256.equals(definition.archiveSha256, ignoreCase = true)
                ) {
                    throw java.io.IOException("Piper 模型压缩包 SHA-256 校验失败")
                }
                AtomicFileInstaller.install(partFile, archive)
                ResumableModelArchiveDownloader.clearResumeMetadata(partFile)
                ResumableModelArchiveDownloader.cleanupPartialFiles(archive)
                onProgress(100)
                onStage("模型包下载完成并通过校验，准备解压…")
                return@withContext
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (sourceIndex == 0) primarySourceWasSlow = error is SlowDownloadSourceException
                android.util.Log.w(
                    "PiperModels",
                    "模型源失败（保留可续传断点）：" + (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }
        }
        throw java.io.IOException(
            "Piper 模型全部下载源均失败：" + (lastError?.message ?: "网络异常"),
            lastError,
        )
    }

    private fun extractArchive(
        archive: File,
        destination: File,
        checkActive: () -> Unit,
        onProgress: (Long) -> Unit = {},
    ) {
        if (!destination.isDirectory) throw java.io.IOException("Piper 临时解压目录不存在")
        val rootPath = destination.canonicalFile.path + File.separator
        val buffer = ByteArray(64 * 1024)
        var archiveRootName: String? = null
        var extractedBytes = 0L
        val progressStepBytes = 8L * 1024L * 1024L
        var nextProgressBytes = progressStepBytes

        // BZip2 performs many small reads internally; buffering its file input avoids a syscall
        // for each low-level read on slower Android storage.
        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(archive), 256 * 1024)
            )
        ).use { tar ->
            while (true) {
                checkActive()
                val entry = tar.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) {
                    throw java.io.IOException("模型包包含不安全的链接")
                }
                if (!entry.isDirectory && !entry.isFile) {
                    throw java.io.IOException("模型包包含不支持的特殊条目")
                }

                val normalized = entry.name.replace('\\', '/')
                val firstPart = normalized.split('/').firstOrNull { it.isNotEmpty() }
                    ?: throw java.io.IOException("模型包包含空路径")
                val expectedRoot = archiveRootName
                if (expectedRoot == null) archiveRootName = firstPart
                else if (expectedRoot != firstPart) {
                    throw java.io.IOException("模型包包含多个顶层目录")
                }

                val relative = PiperModelFilePolicy.normalizeArchiveEntryName(
                    entry.name,
                    archiveRootName ?: firstPart,
                ) ?: throw java.io.IOException("模型包包含非法或越界路径")
                if (relative.isEmpty()) {
                    if (!entry.isDirectory) throw java.io.IOException("模型包根路径不是目录")
                    continue
                }

                val output = File(destination, relative).canonicalFile
                if (!output.path.startsWith(rootPath)) {
                    throw java.io.IOException("模型包路径越界")
                }
                if (entry.isDirectory) {
                    if (!output.isDirectory && !output.mkdirs()) {
                        throw java.io.IOException("无法创建 Piper 模型子目录")
                    }
                    continue
                }

                if (!PiperModelFilePolicy.isWithinLimit(extractedBytes, entry.size, MAX_EXTRACTED_BYTES)) {
                    throw java.io.IOException("Piper 模型解压内容超过 512 MB 安全上限")
                }
                output.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw java.io.IOException("无法创建 Piper 模型子目录")
                    }
                }

                var written = 0L
                BufferedOutputStream(FileOutputStream(output), 256 * 1024).use { out ->
                    while (written < entry.size) {
                        checkActive()
                        val wanted = minOf(buffer.size.toLong(), entry.size - written).toInt()
                        val count = io.translatetube.local.util.ProgressSafeInput.read(tar, buffer, 0, wanted)
                        if (count < 0) throw java.io.IOException("Piper 模型文件解压不完整：${entry.name}")
                        if (count == 0) continue
                        if (!PiperModelFilePolicy.isWithinLimit(extractedBytes, count.toLong(), MAX_EXTRACTED_BYTES) ||
                            !PiperModelFilePolicy.isWithinLimit(written, count.toLong(), entry.size)
                        ) {
                            throw java.io.IOException("Piper 模型文件长度超过声明或总解压上限")
                        }
                        out.write(buffer, 0, count)
                        written += count
                        extractedBytes += count
                        if (extractedBytes >= nextProgressBytes) {
                            onProgress(extractedBytes)
                            nextProgressBytes = (extractedBytes / progressStepBytes + 1L) * progressStepBytes
                        }
                    }
                    // Closing the buffered stream flushes its contents. An fd.sync() for every
                    // extracted file stalls low-end flash storage without improving model-level
                    // atomicity: only the completed staging directory is published below.
                }
                if (!PiperModelFilePolicy.hasExpectedEntryLength(entry.size, written)) {
                    throw java.io.IOException("Piper 模型文件长度校验失败：${entry.name}")
                }
            }
        }
    }

    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L

}
