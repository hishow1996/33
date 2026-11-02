package io.translatetube.local.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.translatetube.local.player.SpeakerChunkIdentityPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Optional local speaker diarization using the OfflineSpeakerDiarization API bundled in
 * sherpa-onnx. Reflection keeps the app compilable with older AARs; if its JNI/API is too old,
 * the UI reports that the bundled runtime must be updated instead of crashing playback.
 */
object SpeakerDiarizer {
    /** Bump whenever the diarization models or chunk-reconciliation rules change. */
    const val MODEL_VERSION = "pyannote-3-0-3dspeaker-16k-sherpa-1.13.8-chunk-v2"

    data class Segment(val startSec: Float, val endSec: Float, val speaker: Int, val confidence: Float)

    private const val SEGMENTATION_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
    private const val EMBEDDING_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    // Model download/extraction writes shared files; serialize it with native diarizer instances.
    private val diarizeMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun diarize(
        context: Context,
        samples: FloatArray,
        onProgress: (String) -> Unit = {},
    ): List<Segment> = diarizeMutex.withLock {
        val ownerJob = currentCoroutineContext()[Job]
        val checkActive: () -> Unit = { ownerJob?.ensureActive() }
        withContext(Dispatchers.IO) {
            checkActive()
            require(samples.isNotEmpty()) { "没有可用于人物识别的音频" }
            // Check the bundled AAR before downloading ~45 MB of models.
            try {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization", true, javaClass.classLoader)
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("当前内置 sherpa-onnx.aar 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色", e)
            } catch (e: LinkageError) {
                throw IllegalStateException("当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试", e)
            }
            onProgress("下载人物识别模型…")
            val dir = File(context.filesDir, "models/speaker")
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw java.io.IOException("无法创建人物识别模型目录，请检查设备可用存储空间")
            }
            val segmentation = File(dir, "segmentation.onnx")
            val embedding = File(dir, "embedding.onnx")
            if (!SpeakerModelDownloadPolicy.isValidCachedSegmentationModel(
                    segmentation.isFile, segmentation.length(),
                )
            ) {
                val archive = File(context.cacheDir, "speaker-segmentation.tar.bz2")
                try {
                    checkActive()
                    download(
                        url = SEGMENTATION_URL,
                        destination = archive,
                        maxBytes = 32L * 1024 * 1024,
                        checkActive = checkActive,
                        ownerJob = ownerJob,
                        progressLabel = "下载人物分离模型",
                        onProgress = onProgress,
                    )
                    checkActive()
                    onProgress("正在解压人物分离模型…")
                    extractSegmentation(archive, segmentation, checkActive)
                    checkActive()
                } finally {
                    archive.delete()
                }
            }
            if (!SpeakerModelDownloadPolicy.isValidCachedEmbeddingModel(
                    embedding.isFile, embedding.length(),
                )
            ) {
                val temp = File(dir, "embedding.onnx.download")
                try {
                    checkActive()
                    download(
                        url = EMBEDDING_URL,
                        destination = temp,
                        maxBytes = 100L * 1024 * 1024,
                        checkActive = checkActive,
                        ownerJob = ownerJob,
                        progressLabel = "下载人物声纹模型",
                        onProgress = onProgress,
                    )
                    checkActive()
                    if (temp.length() < 5_000_000L) throw java.io.IOException("人物声纹模型文件不完整")
                    AtomicFileInstaller.install(temp, embedding)
                } finally {
                    temp.delete()
                }
            }

            checkActive()
            onProgress("识别人物说话片段…")
            try {
                val loader = javaClass.classLoader
                    ?: throw IllegalStateException("无法初始化人物识别运行环境")
                fun type(name: String) = Class.forName("com.k2fsa.sherpa.onnx.$name", true, loader)
                val pyannoteClass = type("OfflineSpeakerSegmentationPyannoteModelConfig")
                val segmentationClass = type("OfflineSpeakerSegmentationModelConfig")
                val embeddingClass = type("SpeakerEmbeddingExtractorConfig")
                val clusteringClass = type("FastClusteringConfig")
                val configClass = type("OfflineSpeakerDiarizationConfig")
                val diarizerClass = type("OfflineSpeakerDiarization")

                val pyannote = pyannoteClass.constructors.first { it.parameterCount == 2 }
                    .newInstance(segmentation.absolutePath, 0.1f)
                val segmentationConfig = segmentationClass.constructors.first { it.parameterCount == 4 }
                    .newInstance(pyannote, 1, false, "cpu")
                val embeddingConfig = embeddingClass.constructors.first { it.parameterCount == 4 }
                    .newInstance(embedding.absolutePath, 1, false, "cpu")
                val clustering = clusteringClass.constructors.firstOrNull { it.parameterCount == 3 }
                    ?.newInstance(-1, 0.5f, true)
                    ?: clusteringClass.constructors.first { it.parameterCount == 2 }
                        .newInstance(-1, 0.5f)
                val config = configClass.constructors.first { it.parameterCount == 5 }
                    .newInstance(segmentationConfig, embeddingConfig, clustering, 0.2f, 0.5f)
                val instance = diarizerClass.constructors.first { it.parameterCount == 2 }
                    .newInstance(null, config)
                try {
                    val sampleRate = diarizerClass.getMethod("sampleRate").invoke(instance) as Int
                    require(sampleRate == 16000) { "人物识别模型要求 16kHz 音频，当前为 ${sampleRate}Hz" }
                    checkActive()
                    // AudioExtract caps analysis at 3 minutes on low-memory devices.
                    // Avoid copyOf(): duplicating a multi-minute FloatArray can cause OOM on older phones.
                    val raw = diarizerClass.getMethod("process", FloatArray::class.java).invoke(instance, samples)
                    checkActive()
                    val count = java.lang.reflect.Array.getLength(raw)
                    val out = ArrayList<Segment>(count)
                    val durationSec = samples.size.toFloat() / sampleRate.toFloat()
                    for (i in 0 until count) {
                        checkActive()
                        val item = java.lang.reflect.Array.get(raw, i)
                        val cls = item.javaClass
                        val start = (cls.getMethod("getStart").invoke(item) as Number).toFloat()
                        val end = (cls.getMethod("getEnd").invoke(item) as Number).toFloat()
                        val speaker = (cls.getMethod("getSpeaker").invoke(item) as Number).toInt()
                        val confidence = (runCatching {
                            cls.getMethod("getConfidence").invoke(item) as Number
                        }.getOrNull()?.toFloat() ?: SpeakerSegmentPolicy.UNKNOWN_CONFIDENCE)
                        SpeakerSegmentPolicy.normalize(start, end, speaker, confidence, durationSec)?.let(out::add)
                    }
                    out
                } finally {
                    runCatching { diarizerClass.getMethod("release").invoke(instance) }
                }
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "当前内置 sherpa-onnx AAR 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色",
                    e,
                )
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw IllegalStateException("说话人分离模型初始化或推理失败：${e.targetException.message ?: e.targetException.javaClass.simpleName}", e.targetException)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("当前 sherpa-onnx AAR 的说话人分离 API 与应用不兼容，请更新内置运行库后重试", e)
            } catch (e: NoSuchElementException) {
                throw IllegalStateException("当前 sherpa-onnx AAR 缺少所需的说话人分离构造函数，请更新内置运行库后重试", e)
            } catch (e: LinkageError) {
                throw IllegalStateException("当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试", e)
            }
        }
    }

    /**
     * Analyze a complete decoded audio file in bounded windows.
     *
     * Only one 3-minute FloatArray is resident at a time. Adjacent windows overlap so speaker
     * labels can be reconciled using shared speech instead of assuming raw sherpa IDs are stable.
     */
    suspend fun diarizePcmFile(
        context: Context,
        pcmFile: AudioExtract.PcmFile,
        chunkDurationMs: Long = 3L * 60L * 1_000L,
        overlapMs: Long = 20L * 1_000L,
        onProgress: (String) -> Unit = {},
    ): List<Segment> {
        require(pcmFile.sampleRate == 16_000) {
            "人物识别需要 16 kHz PCM，当前采样率为 ${pcmFile.sampleRate} Hz"
        }
        require(pcmFile.sampleCount >= pcmFile.sampleRate.toLong()) {
            "转写音频不足 1 秒，无法可靠识别说话人"
        }
        val rate = pcmFile.sampleRate.toLong()
        val chunkSamples = (chunkDurationMs.coerceAtLeast(60_000L) * rate / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
        val overlapSamples = (overlapMs.coerceAtLeast(1_000L) * rate / 1_000L)
            .coerceAtMost(chunkSamples / 3L)
        require(chunkSamples > overlapSamples && chunkSamples * 4L <= 16L * 1024L * 1024L) {
            "人物识别分段大小超过设备内存安全范围"
        }
        val strideSamples = chunkSamples - overlapSamples
        val totalChunks = if (pcmFile.sampleCount <= chunkSamples) {
            1L
        } else {
            (pcmFile.sampleCount - chunkSamples + strideSamples - 1L) / strideSamples + 1L
        }
        val ownerJob = currentCoroutineContext()[Job]
        val merged = ArrayList<SpeakerChunkIdentityPolicy.Segment>()
        var nextSpeakerId = 0
        var chunkIndex = 0L
        var chunkStartSample = 0L
        var previousChunkEndSample = 0L

        withContext(Dispatchers.IO) {
            pcmFile.openReader { ownerJob?.ensureActive() }.use { reader ->
                while (chunkStartSample < pcmFile.sampleCount) {
                    ownerJob?.ensureActive()
                    val chunkEndSample = minOf(
                        pcmFile.sampleCount,
                        chunkStartSample + chunkSamples,
                    )
                    val sampleCount = (chunkEndSample - chunkStartSample).toInt()
                    if (sampleCount <= 0) break
                    chunkIndex++
                    onProgress("正在分析音频分段 $chunkIndex/$totalChunks…")
                    val samples = reader.readFloatSamples(
                        startSample = chunkStartSample,
                        count = sampleCount,
                    )
                    val localSegments = try {
                        diarize(context, samples) { message ->
                            onProgress("音频分段 $chunkIndex/$totalChunks · $message")
                        }
                    } finally {
                        samples.fill(0f)
                    }
                    ownerJob?.ensureActive()

                    val offsetMs = chunkStartSample * 1_000L / rate
                    val absoluteSegments = ArrayList<SpeakerChunkIdentityPolicy.Segment>(localSegments.size)
                    localSegments.forEach { segment ->
                        val localStartMs = (segment.startSec * 1_000f).toLong().coerceAtLeast(0L)
                        val localEndMs = (segment.endSec * 1_000f).toLong().coerceAtLeast(localStartMs)
                        val absoluteStart = offsetMs + localStartMs
                        val absoluteEnd = offsetMs + localEndMs
                        if (segment.speaker >= 0 && absoluteEnd > absoluteStart) {
                            absoluteSegments += SpeakerChunkIdentityPolicy.Segment(
                                speakerId = segment.speaker,
                                startMs = absoluteStart,
                                endMs = absoluteEnd,
                                confidence = segment.confidence,
                            )
                        }
                    }

                    if (chunkIndex == 1L) {
                        val normalized = SpeakerChunkIdentityPolicy.normalizeFirstWindow(absoluteSegments)
                        merged.addAll(normalized.segments)
                        nextSpeakerId = normalized.nextSpeakerId
                    } else {
                        val overlapStartMs = chunkStartSample * 1_000L / rate
                        val overlapEndMs = previousChunkEndSample * 1_000L / rate
                        val reconciled = SpeakerChunkIdentityPolicy.mergeWindow(
                            previous = merged,
                            current = absoluteSegments,
                            overlapStartMs = overlapStartMs,
                            overlapEndMs = overlapEndMs,
                            nextSpeakerId = nextSpeakerId,
                        )
                        merged.addAll(reconciled.segments)
                        nextSpeakerId = reconciled.nextSpeakerId
                    }

                    previousChunkEndSample = chunkEndSample
                    if (chunkEndSample >= pcmFile.sampleCount) break
                    chunkStartSample = (chunkEndSample - overlapSamples)
                        .coerceAtLeast(chunkStartSample + 1L)
                }
            }
        }

        return merged.map { segment ->
            Segment(
                startSec = segment.startMs / 1_000f,
                endSec = segment.endMs / 1_000f,
                speaker = segment.speakerId,
                confidence = segment.confidence,
            )
        }
    }

    private fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        checkActive: () -> Unit,
        ownerJob: kotlinx.coroutines.Job?,
        progressLabel: String,
        onProgress: (String) -> Unit,
    ) {
        checkActive()
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        try {
            val request = Request.Builder().url(url).header("User-Agent", "TranslateTube/1.0").build()
            io.translatetube.local.yt.executeCancellableHttpCall(http.newCall(request), ownerJob) { response ->
                if (!SpeakerModelDownloadPolicy.isFullResponse(response.code)) {
                    throw java.io.IOException("模型下载需要完整响应（HTTP 200），服务器返回 HTTP ${response.code}")
                }
                val body = response.body ?: throw java.io.IOException("模型下载响应为空")
                val expectedBytes = body.contentLength()
                if (!SpeakerModelDownloadPolicy.isValidExpectedLength(expectedBytes, maxBytes)) {
                    throw java.io.IOException("模型响应声明的文件大小无效或超过限制")
                }
                var total = 0L
                var lastProgressPercent = -1
                var lastUnknownLengthReportBytes = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            val n = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw java.io.IOException("模型文件超过大小限制")
                            output.write(buffer, 0, n)

                            val percent = SpeakerModelDownloadPolicy.progressPercent(total, expectedBytes)
                            if (percent != null && percent != lastProgressPercent) {
                                onProgress("$progressLabel · $percent%")
                                lastProgressPercent = percent
                            } else if (
                                percent == null &&
                                total - lastUnknownLengthReportBytes >= 512L * 1024L
                            ) {
                                onProgress("$progressLabel · 已下载 ${total / (1024L * 1024L)} MB")
                                lastUnknownLengthReportBytes = total
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (!SpeakerModelDownloadPolicy.isCompletePayload(expectedBytes, total, maxBytes)) {
                    throw java.io.IOException(
                        "模型下载不完整或大小异常：响应声明 ${expectedBytes} 字节，实际收到 ${total} 字节"
                    )
                }
                onProgress("$progressLabel · 100%")
            }
            AtomicFileInstaller.install(temp, destination)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun extractSegmentation(
        archive: File,
        destination: File,
        checkActive: () -> Unit,
    ) {
        checkActive()
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        var found = false
        var archiveEntryCount = 0
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(archive))).use { tar ->
                while (true) {
                    checkActive()
                    val entry = tar.nextTarEntry ?: break
                    archiveEntryCount++
                    if (!ArchiveEntryLimitPolicy.isWithinLimit(archiveEntryCount)) {
                        throw java.io.IOException("人物识别模型压缩包条目数量超过安全上限")
                    }
                    if (entry.isSymbolicLink || entry.isLink) throw java.io.IOException("模型包包含不安全的链接")
                    if (entry.isDirectory || !entry.name.replace("\\", "/").endsWith("/model.onnx") &&
                        entry.name != "model.onnx") continue
                    if (found) throw java.io.IOException("模型包包含多个候选模型")
                    if (entry.size !in 1L..SpeakerModelDownloadPolicy.MAX_SEGMENTATION_MODEL_BYTES) {
                        throw java.io.IOException("分离模型的压缩包元数据大小无效或超过限制")
                    }
                    var total = 0L
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            val n = io.translatetube.local.util.ProgressSafeInput.read(tar, buffer)
                            if (n < 0) break
                            total += n
                            if (total > 30L * 1024 * 1024) throw java.io.IOException("分离模型超过大小限制")
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                    if (!SpeakerModelDownloadPolicy.isCompleteArchiveEntry(
                            entry.size,
                            total,
                            SpeakerModelDownloadPolicy.MAX_SEGMENTATION_MODEL_BYTES,
                        )
                    ) {
                        throw java.io.IOException(
                            "分离模型解压不完整：压缩包声明 ${entry.size} 字节，实际读取 ${total} 字节"
                        )
                    }
                    if (total < 1_000_000L) throw java.io.IOException("分离模型文件不完整")
                    found = true
                }
            }
            if (!found) throw java.io.IOException("模型包中未找到 model.onnx")
            AtomicFileInstaller.install(temp, destination)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
}
