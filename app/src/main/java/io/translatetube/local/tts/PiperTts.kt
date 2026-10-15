package io.translatetube.local.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import io.translatetube.local.yt.executeCancellableHttpCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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

    override val cacheKey: String
        get() {
            val signature = dir.listFiles().orEmpty()
                .filter { it.isFile && (it.extension.equals("onnx", true) || it.name in setOf("tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst")) }
                .sortedBy { it.name }
                .joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }
            return "$name-$modelId-$signature"
        }

    private val tts: OfflineTts

    init {
        val tokens = File(dir, "tokens.txt")
        val dataDir = File(dir, "espeak-ng-data")
        require(model.isFile) { "Piper 模型文件缺失：${model.absolutePath}" }
        require(tokens.isFile) { "Piper 词表缺失：${tokens.absolutePath}" }
        val lexicon = File(dir, "lexicon.txt")
        val ruleFsts = PiperModelFilePolicy.CHINESE_RULE_FST_NAMES.map { File(dir, it) }
        if (definition.supportsChineseOutput) {
            require(lexicon.isFile && ruleFsts.all { it.isFile }) {
                "中文 Piper 模型资源不完整：需要 lexicon.txt、phone.fst、date.fst 和 number.fst"
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
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = if (definition.supportsChineseOutput) {
                ruleFsts.joinToString(",") { it.absolutePath }
            } else "",
            maxNumSentences = 2,
        )
        // assets=null → use absolute filesystem paths just like Kokoro.
        tts = OfflineTts(null, config)
    }

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        if (text.isBlank()) return TtsAudio(FloatArray(0), 22050)
        TtsTextPolicy.validate(text)
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) return TtsAudio(FloatArray(0), 22050)
        val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
        // Piper models in this catalog are single-speaker. Passing a UI speaker index > 0
        // to a one-speaker native model can cause invalid speaker selection or synthesis failure.
        val sid = 0
        val audio = tts.generate(normalizedText, sid, safeSpeed)
        val sampleRate = audio.sampleRate
        if (sampleRate !in 8_000..192_000 || audio.samples.isEmpty()) {
            android.util.Log.w("PiperTts", "Invalid generated audio: rate=$sampleRate samples=${audio.samples.size}")
            return TtsAudio(FloatArray(0), sampleRate.coerceIn(8_000, 192_000))
        }
        val clean = TtsPcmSanitizer.sanitizeInPlace(audio.samples)
        // 裁掉句首句尾静音，让 Piper 配音起点贴近字幕起点
        return TtsAudio(TtsEdgeSilenceTrimmer.trim(clean, sampleRate), sampleRate)
    }

    override fun close() = tts.release()
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
)

/** Piper 模型清单、安装状态与下载解压管理。 */
object PiperModels {
    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    val catalog: List<PiperModelDef> = listOf(
        PiperModelDef("en_us_amy_low", "英语(美)", "Amy 女声", "vits-piper-en_US-amy-low.tar.bz2", 64),
        PiperModelDef("en_us_lessac_medium", "英语(美)", "Lessac 女声·高清", "vits-piper-en_US-lessac-medium.tar.bz2", 121),
        PiperModelDef("en_gb_alba_medium", "英语(英)", "Alba 女声", "vits-piper-en_GB-alba-medium.tar.bz2", 122),
        PiperModelDef("fr_siwis_low", "法语", "Siwis 女声", "vits-piper-fr_FR-siwis-low.tar.bz2", 63),
        PiperModelDef("de_thorsten_low", "德语", "Thorsten 男声", "vits-piper-de_DE-thorsten-low.tar.bz2", 63),
        PiperModelDef("es_mls_10246_low", "西班牙语", "MLS 女声", "vits-piper-es_ES-mls_10246-low.tar.bz2", 63),
        PiperModelDef("ru_irina_medium", "俄语", "Irina 女声", "vits-piper-ru_RU-irina-medium.tar.bz2", 122),
        PiperModelDef("it_riccardo_xlow", "意大利语", "Riccardo 男声", "vits-piper-it_IT-riccardo-x_low.tar.bz2", 28),
        PiperModelDef("pt_edresson_low", "葡萄牙语(巴西)", "Edresson 男声", "vits-piper-pt_BR-edresson-low.tar.bz2", 63),
        PiperModelDef("pl_darkman_medium", "波兰语", "Darkman 男声", "vits-piper-pl_PL-darkman-medium.tar.bz2", 122),
        PiperModelDef("uk_ukrainian_tts", "乌克兰语", "Ukrainian-TTS 女声", "vits-uk_UA-ukrainian_tts-medium.tar.bz2", 120),
        PiperModelDef("fa_amir_medium", "波斯语", "Amir 男声", "vits-piper-fa_IR-amir-medium.tar.bz2", 122),
        PiperModelDef(
            "zh_cn_chaowen_medium", "中文", "超文女声",
            "vits-piper-zh_CN-chaowen-medium.tar.bz2", 64, supportsChineseOutput = true,
        ),
        PiperModelDef(
            "zh_cn_xiao_ya_medium", "中文", "晓雅女声",
            "vits-piper-zh_CN-xiao_ya-medium.tar.bz2", 64, supportsChineseOutput = true,
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

    fun refreshInstalled(ctx: Context) {
        _installed.value = installedIds(ctx)
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
    ) {
        val def = byId(id) ?: throw IllegalArgumentException("未知 Piper 模型：$id")
        download(ctx, def, onProgress, checkActive)
    }

    private suspend fun download(
        ctx: Context,
        def: PiperModelDef,
        onProgress: (Int) -> Unit,
        checkActive: () -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        checkActive()
        val root = piperRoot(ctx)
        if (!root.isDirectory && !root.mkdirs()) {
            throw java.io.IOException("无法创建 Piper 模型目录：${root.absolutePath}")
        }
        val dest = modelDir(ctx, def.id)
        if (complete(dest, def)) {
            refreshInstalled(ctx)
            return@withContext
        }

        val archive = File(ctx.cacheDir, "piper-model-${def.id}.tar.bz2.tmp")
        val staging = File(root, "${def.id}.extract")
        val backup = File(root, "${def.id}.backup")
        try {
            if (archive.exists() && !archive.delete()) {
                throw java.io.IOException("无法清理上次未完成的 Piper 下载文件")
            }

            val request = Request.Builder().url("$BASE/${def.archive}").build()
            val ownerJob = currentCoroutineContext()[Job]
            executeCancellableHttpCall(http.newCall(request), ownerJob) { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("模型下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw java.io.IOException("模型下载失败：空响应")
                val total = body.contentLength()
                if (total > MAX_ARCHIVE_BYTES) {
                    throw java.io.IOException("Piper 模型压缩包超过 256 MB 安全上限")
                }
                var done = 0L
                body.byteStream().use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            ownerJob?.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (!PiperModelFilePolicy.isWithinLimit(done, n.toLong(), MAX_ARCHIVE_BYTES)) {
                                throw java.io.IOException("Piper 模型压缩包超过 256 MB 安全上限")
                            }
                            done += n
                            output.write(buffer, 0, n)
                            if (total > 0L) {
                                onProgress(((done * 100L) / total).toInt().coerceIn(0, 99))
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (done == 0L || (total >= 0L && done != total)) {
                    throw java.io.IOException("Piper 模型下载不完整：预期 ${total} 字节，实际 ${done} 字节")
                }
            }

            checkActive()
            // Recover a process stopped between moving the old directory and publishing staging.
            if (backup.exists()) {
                if (dest.exists()) {
                    if (!backup.deleteRecursively()) {
                        throw java.io.IOException("无法清理上次 Piper 模型安装留下的备份")
                    }
                } else if (!backup.renameTo(dest)) {
                    throw java.io.IOException("上次 Piper 模型安装中断，无法恢复原模型")
                }
            }
            if (staging.exists() && !staging.deleteRecursively()) {
                throw java.io.IOException("无法清理上次未完成的 Piper 解压目录")
            }
            if (!staging.mkdirs()) throw java.io.IOException("无法创建 Piper 临时解压目录")

            extractArchive(archive, staging, checkActive)
            if (!complete(staging, def)) {
                throw java.io.IOException("Piper 模型包不完整：ONNX（至少 1 MB）、tokens.txt 及所需语言资源均须完整")
            }
            checkActive()

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
            refreshInstalled(ctx)
        } catch (error: Exception) {
            // Never delete the published model on a failed download or extraction. Only staging is disposable.
            runCatching { staging.deleteRecursively() }
            throw error
        } finally {
            runCatching { archive.delete() }
        }
    }

    private fun extractArchive(
        archive: File,
        destination: File,
        checkActive: () -> Unit,
    ) {
        if (!destination.isDirectory) throw java.io.IOException("Piper 临时解压目录不存在")
        val rootPath = destination.canonicalFile.path + File.separator
        val buffer = ByteArray(64 * 1024)
        var archiveRootName: String? = null
        var extractedBytes = 0L

        TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { tar ->
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
                FileOutputStream(output).use { out ->
                    while (written < entry.size) {
                        checkActive()
                        val wanted = minOf(buffer.size.toLong(), entry.size - written).toInt()
                        val count = tar.read(buffer, 0, wanted)
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
                    }
                    out.fd.sync()
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
