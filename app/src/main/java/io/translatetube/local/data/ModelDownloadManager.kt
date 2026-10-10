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
)

/** 模型文件就绪判定与路径（engine 加载的单一事实来源） */
object ModelFiles {
    fun ttsDir(ctx: Context) = File(ctx.filesDir, "models/tts")
    fun asrDir(ctx: Context) = File(ctx.filesDir, "models/whisper")

    fun ttsReady(ctx: Context) =
        File(ttsDir(ctx), "model.int8.onnx").exists() && File(ttsDir(ctx), "voices.bin").exists()

    fun asrReady(ctx: Context) =
        File(asrDir(ctx), "base-encoder.int8.onnx").exists() &&
            File(asrDir(ctx), "base-decoder.int8.onnx").exists()

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

    fun download(kind: ModelKind) {
        when (kind) {
            ModelKind.TTS -> {
                if (_tts.value.status == ModelStatus.DOWNLOADING || ModelFiles.ttsReady(ctx)) return
                ttsJob = scope.launch { runDownload(ModelKind.TTS, _tts, ttsUrls, ModelFiles.ttsDir(ctx)) }
            }
            ModelKind.ASR -> {
                if (_asr.value.status == ModelStatus.DOWNLOADING || ModelFiles.asrReady(ctx)) return
                asrJob = scope.launch { runDownload(ModelKind.ASR, _asr, asrUrls, ModelFiles.asrDir(ctx)) }
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
        for (url in urls) {
            try {
                withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    http.newCall(req).execute().use { resp ->
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
                                        progress = pct, downloadedMB = got / 1048576, totalMB = total / 1048576,
                                    )
                                }
                            }
                        }
                    }
                }
                // 解压（原子：先解到 .extract 再整体 rename 内容）
                state.value = state.value.copy(status = ModelStatus.EXTRACTING, progress = 100)
                withContext(Dispatchers.IO) {
                    destDir.mkdirs()
                    extractTarBz2(pkg, destDir)
                }
                if (kind == ModelKind.ASR) {
                    // ASR 包附带 VAD 小模型（0.6MB，双源）
                    val vad = File(ModelFiles.asrDir(ctx), "silero_vad.onnx")
                    if (!vad.exists()) {
                        for (u in listOf(
                            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                        )) {
                            try {
                                downloadTo(u, vad)
                                break
                            } catch (_: Exception) {}
                        }
                    }
                }
                pkg.delete()
                state.value = state.value.copy(status = ModelStatus.READY, progress = 100)
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

    /** .tar.bz2 → 目标目录（剥掉顶层目录名，原子落位） */
    private suspend fun downloadTo(url: String, dest: File, onProgress: ((got: Long, total: Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
        }
    }

    private fun extractTarBz2(pkg: File, destDir: File) {
        val parent = destDir.parentFile ?: throw java.io.IOException("模型目录无效")
        if (!parent.exists() && !parent.mkdirs()) throw java.io.IOException("无法创建模型父目录")
        val staging = File(parent, destDir.name + ".extract")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw java.io.IOException("无法创建模型临时目录")
        val rootPath = staging.canonicalFile.path + File.separator
        val maxExtractedBytes = 1024L * 1024 * 1024
        var extractedBytes = 0L

        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(pkg))).use { tar ->
                while (true) {
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
                            val count = tar.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (extractedBytes > maxExtractedBytes || fileBytes > entry.size) {
                                throw java.io.IOException("解压后的模型超过大小限制")
                            }
                            out.write(buffer, 0, count)
                        }
                        if (fileBytes != entry.size) throw java.io.IOException("模型文件长度校验失败")
                    }
                }
            }

            // 不完整或伪造的压缩包不能覆盖已经可用的模型。
            val required = if (destDir.name == "tts") {
                listOf("model.int8.onnx", "voices.bin")
            } else {
                listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx")
            }
            if (required.any { name ->
                    val file = File(staging, name)
                    !file.isFile || file.length() <= 0L
                }) {
                throw java.io.IOException("模型包缺少必要文件，已取消安装")
            }

            val old = File(parent, destDir.name + ".old")
            old.deleteRecursively()
            val hadOld = destDir.exists()
            if (hadOld && !destDir.renameTo(old)) throw java.io.IOException("无法备份现有模型目录")
            if (!staging.renameTo(destDir)) {
                if (hadOld) old.renameTo(destDir)
                throw java.io.IOException("无法安装新模型目录")
            }
            old.deleteRecursively()
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }
}
