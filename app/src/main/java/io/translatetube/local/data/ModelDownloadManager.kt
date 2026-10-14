package io.translatetube.local.data

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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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
    fun asrDir(ctx: Context) = File(ctx.filesDir, "models/whisper")

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

    fun asrCoreReady(ctx: Context) =
        validModel(File(asrDir(ctx), "base-encoder.int8.onnx")) &&
            validModel(File(asrDir(ctx), "base-decoder.int8.onnx"))
    fun asrReady(ctx: Context) =
        asrCoreReady(ctx) && validModel(File(asrDir(ctx), "silero_vad.onnx"), 512L * 1024)
    fun anyReady(ctx: Context) = ttsReady(ctx) || asrReady(ctx)
}

/**
 * 双模型独立下载管理器：
 * - TTS（Kokoro v1.1 int8，压缩包 140MB → 就绪 207MB）
 * - ASR（Whisper base int8，压缩包 198MB → 就绪 160MB）
 * 双源：GitHub 官方 → 镜像加速（国内直连 GitHub 不稳时自动切换）
 * 原子性：下载到 .tmp → 解压到 .tmp → 原子 rename，任何中断不污染旧文件
 */
class ModelDownloadManager(private val ctx: Context, private val scope: CoroutineScope) {

    private val _tts = MutableStateFlow(ModelState())
    val tts: StateFlow<ModelState> = _tts
    private val _asr = MutableStateFlow(ModelState())
    val asr: StateFlow<ModelState> = _asr

    private var ttsJob: Job? = null
    private var asrJob: Job? = null

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
        when (kind) {
            ModelKind.TTS -> {
                if (ttsJob?.isActive == true ||
                    _tts.value.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING)) return
                runCatching {
                    if (ModelFiles.ttsCoreReady(ctx)) ModelFiles.installBundledTtsSupportFiles(ctx)
                }.onFailure {
                    Log.w("ModelDL", "无法修复 Kokoro 应用内附属文件，将检查完整模型包：${it.message}")
                }
                if (ModelFiles.ttsReady(ctx)) return
                if (!startBackgroundDownloadService()) {
                    _tts.value = ModelState(status = ModelStatus.FAILED, error = "无法启动后台下载服务，请检查系统后台运行权限后重试")
                    return
                }
                ttsJob = scope.launch {
                    try {
                        runDownload(ModelKind.TTS, _tts, ttsUrls, ModelFiles.ttsDir(ctx))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ModelDL", "TTS 下载任务异常", e)
                        _tts.value = ModelState(status = ModelStatus.FAILED, error = e.message ?: "下载任务异常")
                    }
                }
            }
            ModelKind.ASR -> {
                if (asrJob?.isActive == true ||
                    _asr.value.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING) ||
                    ModelFiles.asrReady(ctx)) return
                if (!startBackgroundDownloadService()) {
                    _asr.value = ModelState(status = ModelStatus.FAILED, error = "无法启动后台下载服务，请检查系统后台运行权限后重试")
                    return
                }
                asrJob = scope.launch {
                    try {
                        if (ModelFiles.asrCoreReady(ctx)) runVadOnly(_asr)
                        else runDownload(ModelKind.ASR, _asr, asrUrls, ModelFiles.asrDir(ctx))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ModelDL", "ASR 下载任务异常", e)
                        _asr.value = ModelState(status = ModelStatus.FAILED, error = e.message ?: "下载任务异常")
                    }
                }
            }
        }
    }

    fun cancel(kind: ModelKind) {
        when (kind) {
            ModelKind.TTS -> { ttsJob?.cancel(); _tts.value = ModelState(status = ModelStatus.FAILED, error = "已取消") }
            ModelKind.ASR -> { asrJob?.cancel(); _asr.value = ModelState(status = ModelStatus.FAILED, error = "已取消") }
        }
    }

    private suspend fun runDownload(
        kind: ModelKind,
        state: MutableStateFlow<ModelState>,
        urls: List<String>,
        destDir: File,
    ) {
        state.value = ModelState(status = ModelStatus.DOWNLOADING, totalMB = if (kind == ModelKind.TTS) 140 else 198)
        val pkg = File(ctx.cacheDir, "${kind.name}_model.tar.bz2.tmp")
        var lastError: String? = null
        for ((sourceIndex, url) in urls.withIndex()) {
            // Reset the phase before every fallback source. Otherwise an extraction error leaves
            // the UI stuck on "installing" while the manager is downloading the next mirror.
            state.value = ModelState(
                status = ModelStatus.DOWNLOADING,
                progress = 0,
                totalMB = if (kind == ModelKind.TTS) 140 else 198,
                stageMessage = if (sourceIndex == 0) "正在下载模型包…" else "正在切换下载源（${sourceIndex + 1}/${urls.size}）…",
            )
            try {
                withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    executeCancellable(http.newCall(req)) { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("空响应")
                        val total = body.contentLength().takeIf { it > 0 } ?: (state.value.totalMB * 1048576)
                        body.byteStream().use { ins ->
                            FileOutputStream(pkg).use { out ->
                                val buf = ByteArray(128 * 1024)
                                var got = 0L
                                val maxArchiveBytes = 512L * 1024 * 1024
                                if (total > maxArchiveBytes) throw IllegalStateException("模型压缩包超过大小限制")
                                while (true) {
                                    if (state.value.status == ModelStatus.FAILED) throw kotlinx.coroutines.CancellationException("用户取消")
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    got += n
                                    if (got > maxArchiveBytes) throw IllegalStateException("模型压缩包超过大小限制")
                                    out.write(buf, 0, n)
                                    val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                                    state.value = state.value.copy(
                                        progress = pct,
                                        downloadedMB = got / 1048576,
                                        totalMB = total / 1048576,
                                        stageMessage = "正在下载模型包…",
                                    )
                                }
                            }
                        }
                    }
                }
                state.value = state.value.copy(progress = 99, stageMessage = "正在校验模型压缩包…")
                // TTS 压缩包有 GitHub Release API 公布的 SHA-256；镜像下载也必须通过相同校验。
                // Whisper 压缩包当前上游没有提供可直接核验的 asset digest，因此仍依赖 HTTPS、大小限制和解压结构校验。
                val expectedArchiveSha256 = when (kind) {
                    ModelKind.TTS -> "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6"
                    ModelKind.ASR -> null
                }
                if (expectedArchiveSha256 != null) {
                    withContext(Dispatchers.IO) {
                        val checksumJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                        val actual = sha256(pkg) { checksumJob?.ensureActive() }
                        if (!actual.equals(expectedArchiveSha256, ignoreCase = true)) {
                            throw java.io.IOException("模型压缩包 SHA-256 校验失败")
                        }
                    }
                }

                // 解压（原子：先解到 .extract 再整体 rename 内容）。定期更新状态，避免大模型
                // 解压时看起来像完全卡住。
                state.value = state.value.copy(
                    status = ModelStatus.EXTRACTING,
                    progress = 0,
                    stageMessage = "正在解压并安装模型…",
                )
                withContext(Dispatchers.IO) {
                    destDir.mkdirs()
                    val extractionJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                    extractTarBz2(
                        pkg = pkg,
                        destDir = destDir,
                        checkActive = { extractionJob?.ensureActive() },
                        onProgress = { extractedBytes ->
                            val handledMB = extractedBytes / (1024L * 1024L)
                            state.value = state.value.copy(
                                stageMessage = "正在解压并安装模型（已处理 ${handledMB} MB）",
                            )
                        },
                    )
                }
                if (kind == ModelKind.ASR) {
                    // ASR 包附带 VAD 小模型（0.6MB，双源）
                    val vad = File(ModelFiles.asrDir(ctx), "silero_vad.onnx")
                    if (!vad.isFile || vad.length() < 512L * 1024) {
                        for (u in listOf(
                            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                        )) {
                            try {
                                downloadTo(u, vad, expectedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("ModelDL", "VAD 下载源失败: ${e.message}")
                            }
                        }
                    }
                    if (!vad.isFile || vad.length() < 512L * 1024) {
                        pkg.delete()
                        state.value = ModelState(
                            status = ModelStatus.FAILED,
                            error = "Whisper 主模型已安装，但语音检测模型下载失败；点击重试只会补下载小型 VAD 模型。",
                        )
                        return
                    }
                }
                val installed = if (kind == ModelKind.TTS) {
                    ModelFiles.ttsReady(ctx)
                } else {
                    ModelFiles.asrReady(ctx)
                }
                if (!installed) throw java.io.IOException("模型文件安装后完整性检查未通过")
                pkg.delete()
                state.value = state.value.copy(
                    status = ModelStatus.READY,
                    progress = 100,
                    stageMessage = null,
                    error = null,
                )
                Log.i("ModelDL", "$kind 就绪")
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                pkg.delete()
                return
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w("ModelDL", "$kind 源失败: $lastError，尝试下一源")
            }
        }
        pkg.delete()
        state.value = state.value.copy(status = ModelStatus.FAILED, error = lastError ?: "全部下载源失败")
    }

    /** Whisper 主模型已经存在时，只补下载缺失/损坏的 VAD 文件，避免重新下载近 200 MB 模型包。 */
    private suspend fun runVadOnly(state: MutableStateFlow<ModelState>) {
        state.value = ModelState(status = ModelStatus.DOWNLOADING, totalMB = 1)
        val vad = File(ModelFiles.asrDir(ctx), "silero_vad.onnx")
        var lastError: String? = null
        val urls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        )
        for (url in urls) {
            try {
                downloadTo(url, vad, expectedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")
                if (!ModelFiles.asrReady(ctx)) throw java.io.IOException("VAD 模型校验失败")
                state.value = ModelState(status = ModelStatus.READY, progress = 100, downloadedMB = vad.length() / 1048576, totalMB = vad.length() / 1048576)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        state.value = ModelState(status = ModelStatus.FAILED, error = "补下载 VAD 模型失败：${lastError ?: "网络异常"}")
    }

    private fun sha256(file: File, checkActive: () -> Unit = {}): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
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
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            executeCancellable(http.newCall(req)) { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("下载响应为空")
                val maxBytes = 16L * 1024 * 1024
                if (body.contentLength() > maxBytes) throw IllegalStateException("附属模型超过大小限制")
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxBytes) throw IllegalStateException("附属模型超过大小限制")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (written == 0L) throw IllegalStateException("下载文件为空")
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
            if (destDir.exists()) {
                if (!old.deleteRecursively()) throw java.io.IOException("无法清理上次模型安装留下的旧备份")
            } else if (!old.renameTo(destDir)) {
                throw java.io.IOException("上次模型安装中断，无法恢复保留的模型目录")
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

        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(pkg))).use { tar ->
                while (true) {
                    checkActive()
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isSymbolicLink || entry.isLink) {
                        throw java.io.IOException("模型压缩包包含链接条目")
                    }
                    val normalized = entry.name.replace("\\", "/")
                    val parts = normalized.split('/').filter { it.isNotEmpty() }
                    if (parts.isEmpty() || parts.any { it == "." || it == ".." }) {
                        throw java.io.IOException("模型压缩包包含非法路径")
                    }

                    // 模型官方压缩包带顶层目录；剥掉一层，匹配应用预期路径。
                    val relative = parts.drop(1).joinToString(File.separator)
                    if (relative.isEmpty()) continue
                    val output = File(staging, relative).canonicalFile
                    if (!output.path.startsWith(rootPath)) {
                        throw java.io.IOException("模型压缩包路径越界")
                    }
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw java.io.IOException("无法创建模型子目录")
                        continue
                    }
                    if (!entry.isFile || entry.size < 0L) {
                        throw java.io.IOException("模型压缩包包含不支持的条目")
                    }
                    if (extractedBytes + entry.size > maxExtractedBytes) {
                        throw java.io.IOException("解压后的模型超过大小限制")
                    }
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw java.io.IOException("无法创建模型子目录")
                    }
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            checkActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (extractedBytes > maxExtractedBytes || fileBytes > entry.size) {
                                throw java.io.IOException("解压后的模型超过大小限制")
                            }
                            out.write(buffer, 0, count)
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
            val required = if (destDir.name == "tts") {
                listOf("model.int8.onnx", "voices.bin")
            } else {
                listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx")
            }
            if (required.any { name ->
                    val file = File(staging, name)
                    // 这些模型文件正常体积均远大于 1 MB；过小通常意味着下载/解压不完整。
                    !file.isFile || file.length() < 1L * 1024 * 1024
                }) {
                throw java.io.IOException("模型包缺少必要文件或文件体积异常，已取消安装")
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
