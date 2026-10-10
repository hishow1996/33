package io.translatetube.local.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
                                val buf = ByteArray(256 * 1024)
                                var got = 0L
                                while (true) {
                                    if (state.value.status == ModelStatus.FAILED) throw kotlinx.coroutines.CancellationException("用户取消")
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n); got += n
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
        val staging = File(destDir.parentFile, destDir.name + ".extract").apply {
            deleteRecursively(); mkdirs()
        }
        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(pkg))).use { tar ->
            var e = tar.nextTarEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty() && !e.isDirectory) {
                    val out = File(staging, rel)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
                e = tar.nextTarEntry
            }
        }
        // 原子替换：旧目录改名换新
        val old = File(destDir.parentFile, destDir.name + ".old")
        old.deleteRecursively()
        if (destDir.exists()) destDir.renameTo(old)
        if (!staging.renameTo(destDir)) {
            // 极端情况 rename 失败：直接拷贝
            staging.copyRecursively(destDir, overwrite = true)
            staging.deleteRecursively()
        }
        old.deleteRecursively()
    }
}
