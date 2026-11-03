package io.translatetube.local.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.performance.DevicePerformancePolicy
import io.translatetube.local.player.SpeakerChunkIdentityPolicy
import io.translatetube.local.player.SpeakerRecognitionProgressPolicy
import io.translatetube.local.player.SpeakerRecognitionProgressUpdate
import io.translatetube.local.player.SpeakerRecognitionStage
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
    const val MODEL_VERSION = "pyannote-3-0-3dspeaker-16k-sherpa-1.13.8-chunk-v3-2min"

    data class Segment(val startSec: Float, val endSec: Float, val speaker: Int, val confidence: Float)

    /**
     * Bound synchronous native inference to a smaller window so cancellation is checked more
     * often. A 2-minute 16 kHz FloatArray is about 7.68 MB; the existing overlap is retained to
     * keep cross-window speaker identity reconciliation conservative.
     * Native sherpa inference itself is synchronous and cannot be interrupted mid-window.
     */
    internal const val DEFAULT_CHUNK_DURATION_MS = 2L * 60L * 1_000L
    internal const val DEFAULT_OVERLAP_MS = 20L * 1_000L

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

    private data class ModelFiles(
        val segmentation: File,
        val embedding: File,
    )

    /**
     * A single native instance can process multiple independent windows. Keeping it alive for
     * the complete file avoids reloading both ONNX models for every two-minute window on lower-memory phones.
     */
    private class NativeDiarizerSession(
        private val instance: Any,
        private val processMethod: java.lang.reflect.Method,
        private val processWithCallbackMethod: java.lang.reflect.Method?,
        private val callbackType: Class<*>?,
        private val releaseMethod: java.lang.reflect.Method,
        val sampleRate: Int,
    ) {
        val supportsInferenceProgress: Boolean
            get() = callbackNativeMethodAvailable &&
                processWithCallbackMethod != null &&
                callbackType != null

        private var callbackNativeMethodAvailable = true

        fun process(
            samples: FloatArray,
            checkActive: () -> Unit,
            onInferenceProgress: ((Long, Long) -> Unit)? = null,
        ): List<Segment> {
            require(samples.isNotEmpty()) { "没有可用于人物识别的音频" }
            checkActive()
            val raw = if (
                processWithCallbackMethod != null &&
                callbackType != null &&
                callbackNativeMethodAvailable &&
                onInferenceProgress != null
            ) {
                val callback: Any = if (callbackType.name == "kotlin.jvm.functions.Function3") {
                    // A Kotlin lambda exposes both the erased Function3 bridge and the typed
                    // invoke(int, int, long): Integer method required by sherpa-onnx JNI.
                    val kotlinCallback: (Int, Int, Long) -> Int = { processed, total, _ ->
                        // JNI callback return values are ignored by this sherpa API. Do not throw
                        // CancellationException across JNI; check cancellation after native return.
                        runCatching {
                            onInferenceProgress(processed.toLong(), total.toLong())
                        }
                        0
                    }
                    kotlinCallback
                } else {
                    val callbackLoader = callbackType.classLoader ?: instance.javaClass.classLoader
                    java.lang.reflect.Proxy.newProxyInstance(
                        callbackLoader,
                        arrayOf(callbackType),
                    ) { proxy, method, args ->
                        when (method.name) {
                            "invoke" -> {
                                val processed = (args?.getOrNull(0) as? Number)?.toLong() ?: 0L
                                val total = (args?.getOrNull(1) as? Number)?.toLong() ?: 0L
                                runCatching { onInferenceProgress(processed, total) }
                                0
                            }
                            "toString" -> "SpeakerRecognitionProgressCallback"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            else -> null
                        }
                    }
                }
                try {
                    if (processWithCallbackMethod.parameterCount == 3) {
                        processWithCallbackMethod.invoke(instance, samples, callback, 0L)
                    } else {
                        processWithCallbackMethod.invoke(instance, samples, callback)
                    }
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    if (failure.targetException is UnsatisfiedLinkError) {
                        // Some older AARs expose the Java overload but ship JNI symbols without
                        // callback support. Disable it for this session and use the stable API.
                        callbackNativeMethodAvailable = false
                        processMethod.invoke(instance, samples)
                    } else {
                        throw failure
                    }
                }
            } else {
                processMethod.invoke(instance, samples)
            } ?: return emptyList()
            checkActive()
            val count = java.lang.reflect.Array.getLength(raw)
            if (count == 0) return emptyList()

            val first = java.lang.reflect.Array.get(raw, 0)
            val segmentClass = first.javaClass
            val startGetter = segmentClass.getMethod("getStart")
            val endGetter = segmentClass.getMethod("getEnd")
            val speakerGetter = segmentClass.getMethod("getSpeaker")
            val confidenceGetter = runCatching {
                segmentClass.getMethod("getConfidence")
            }.getOrNull()

            val durationSec = samples.size.toFloat() / sampleRate.toFloat()
            val out = ArrayList<Segment>(count)
            for (i in 0 until count) {
                checkActive()
                val item = java.lang.reflect.Array.get(raw, i)
                val start = (startGetter.invoke(item) as Number).toFloat()
                val end = (endGetter.invoke(item) as Number).toFloat()
                val speaker = (speakerGetter.invoke(item) as Number).toInt()
                val confidence = confidenceGetter?.let { getter ->
                    runCatching { (getter.invoke(item) as Number).toFloat() }.getOrNull()
                } ?: SpeakerSegmentPolicy.UNKNOWN_CONFIDENCE
                SpeakerSegmentPolicy.normalize(start, end, speaker, confidence, durationSec)?.let(out::add)
            }
            return out
        }

        fun close() {
            runCatching { releaseMethod.invoke(instance) }
        }
    }

    private fun checkSpeakerRuntimeAvailable() {
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization", true, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "当前内置 sherpa-onnx.aar 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色",
                e,
            )
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试",
                e,
            )
        }
    }

    private fun prepareModelFiles(
        context: Context,
        ownerJob: Job?,
        checkActive: () -> Unit,
        onProgress: (SpeakerRecognitionProgressUpdate) -> Unit,
    ): ModelFiles {
        checkActive()
        checkSpeakerRuntimeAvailable()
        onProgress(
            SpeakerRecognitionProgressUpdate(
                stage = SpeakerRecognitionStage.CHECKING_MODELS,
                message = "正在检查人物识别模型…",
            ),
        )
        val dir = File(context.filesDir, "models/speaker")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw java.io.IOException("无法创建人物识别模型目录，请检查设备可用存储空间")
        }

        val segmentation = File(dir, "segmentation.onnx")
        val embedding = File(dir, "embedding.onnx")
        if (!SpeakerModelDownloadPolicy.isValidCachedSegmentationModel(
                segmentation.isFile,
                segmentation.length(),
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
                    progressStage = SpeakerRecognitionStage.DOWNLOADING_MODELS,
                    progressLabel = "正在下载人物分离模型",
                    onProgress = onProgress,
                )
                checkActive()
                onProgress(
                    SpeakerRecognitionProgressUpdate(
                        stage = SpeakerRecognitionStage.EXTRACTING_MODELS,
                        message = "正在解压人物分离模型…",
                    ),
                )
                extractSegmentation(archive, segmentation, checkActive)
                checkActive()
            } finally {
                archive.delete()
            }
        }

        if (!SpeakerModelDownloadPolicy.isValidCachedEmbeddingModel(
                embedding.isFile,
                embedding.length(),
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
                    progressStage = SpeakerRecognitionStage.DOWNLOADING_MODELS,
                    progressLabel = "正在下载人物声纹模型",
                    onProgress = onProgress,
                )
                checkActive()
                if (temp.length() < SpeakerModelDownloadPolicy.MIN_EMBEDDING_MODEL_BYTES) {
                    throw java.io.IOException("人物声纹模型文件不完整")
                }
                AtomicFileInstaller.install(temp, embedding)
            } finally {
                temp.delete()
            }
        }
        checkActive()
        return ModelFiles(segmentation, embedding)
    }

    /**
     * CPU threads for each of the two ONNX models. Both were hard-coded to 1, which left most
     * cores of an eight-core phone idle during a job that runs for minutes. Falls back to the old
     * single thread if the device snapshot cannot be read.
     */
    private fun speakerThreads(context: Context): Int = runCatching {
        DevicePerformancePolicy.recommendedSpeakerThreads(
            DevicePerformanceManager.snapshot(context),
            playbackActive = DevicePerformanceManager.isPlaybackLoadActive(),
        )
    }.getOrDefault(1).coerceIn(1, 4)

    private fun createNativeSession(
        models: ModelFiles,
        numThreads: Int,
        checkActive: () -> Unit,
    ): NativeDiarizerSession {
        try {
            val loader = javaClass.classLoader
                ?: throw IllegalStateException("无法初始化人物识别运行环境")
            fun type(name: String) = Class.forName("com.k2fsa.sherpa.onnx." + name, true, loader)
            val pyannoteClass = type("OfflineSpeakerSegmentationPyannoteModelConfig")
            val segmentationClass = type("OfflineSpeakerSegmentationModelConfig")
            val embeddingClass = type("SpeakerEmbeddingExtractorConfig")
            val clusteringClass = type("FastClusteringConfig")
            val configClass = type("OfflineSpeakerDiarizationConfig")
            val diarizerClass = type("OfflineSpeakerDiarization")

            val pyannote = pyannoteClass.constructors.first { it.parameterCount == 2 }
                .newInstance(models.segmentation.absolutePath, 0.1f)
            val segmentationConfig = segmentationClass.constructors.first { it.parameterCount == 4 }
                .newInstance(pyannote, numThreads, false, "cpu")
            val embeddingConfig = embeddingClass.constructors.first { it.parameterCount == 4 }
                .newInstance(models.embedding.absolutePath, numThreads, false, "cpu")
            val clustering = clusteringClass.constructors.firstOrNull { it.parameterCount == 3 }
                ?.newInstance(-1, 0.5f, true)
                ?: clusteringClass.constructors.first { it.parameterCount == 2 }
                    .newInstance(-1, 0.5f)
            val config = configClass.constructors.first { it.parameterCount == 5 }
                .newInstance(segmentationConfig, embeddingConfig, clustering, 0.2f, 0.5f)

            checkActive()
            val instance = diarizerClass.constructors.first { it.parameterCount == 2 }
                .newInstance(null, config)
            try {
                val sampleRate = diarizerClass.getMethod("sampleRate").invoke(instance) as Int
                require(sampleRate == 16_000) {
                    "人物识别模型要求 16kHz 音频，当前为 " + sampleRate + "Hz"
                }
                val processMethod = diarizerClass.getMethod("process", FloatArray::class.java)
                // v1.13.8 exposes the Kotlin Function3 callback with an extra Long arg.
                // Also accept the Java callback interface when an AAR packages that API instead.
                val callbackApi = runCatching {
                    val callback = type("kotlin.jvm.functions.Function3")
                    val method = diarizerClass.getMethod(
                        "processWithCallback",
                        FloatArray::class.java,
                        callback,
                        java.lang.Long.TYPE,
                    )
                    callback to method
                }.getOrNull() ?: runCatching {
                    val callback = type("OfflineSpeakerDiarizationCallback")
                    val method = diarizerClass.getMethod(
                        "processWithCallback",
                        FloatArray::class.java,
                        callback,
                    )
                    callback to method
                }.getOrNull()
                val callbackType = callbackApi?.first
                val processWithCallbackMethod = callbackApi?.second
                val releaseMethod = diarizerClass.getMethod("release")
                checkActive()
                return NativeDiarizerSession(
                    instance = instance,
                    processMethod = processMethod,
                    processWithCallbackMethod = processWithCallbackMethod,
                    callbackType = callbackType,
                    releaseMethod = releaseMethod,
                    sampleRate = sampleRate,
                )
            } catch (failure: Throwable) {
                runCatching { diarizerClass.getMethod("release").invoke(instance) }
                throw failure
            }
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "当前内置 sherpa-onnx AAR 不包含说话人分离模块，需要更新 sherpa-onnx.aar 后才能自动识别；可先手动切换字幕音色",
                e,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "说话人分离模型初始化失败：" + (e.targetException.message ?: e.targetException.javaClass.simpleName),
                e.targetException,
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(
                "当前 sherpa-onnx AAR 的说话人分离 API 与应用不兼容，请更新内置运行库后重试",
                e,
            )
        } catch (e: NoSuchElementException) {
            throw IllegalStateException(
                "当前 sherpa-onnx AAR 缺少所需的说话人分离构造函数，请更新内置运行库后重试",
                e,
            )
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试",
                e,
            )
        }
    }

    private fun processWithNativeSession(
        session: NativeDiarizerSession,
        samples: FloatArray,
        checkActive: () -> Unit,
        onInferenceProgress: ((Long, Long) -> Unit)? = null,
    ): List<Segment> {
        try {
            return session.process(samples, checkActive, onInferenceProgress)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "说话人分离推理失败：" + (e.targetException.message ?: e.targetException.javaClass.simpleName),
                e.targetException,
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(
                "当前 sherpa-onnx AAR 的说话人分离 API 与应用不兼容，请更新内置运行库后重试",
                e,
            )
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "当前 sherpa-onnx.aar 的原生库不支持说话人分离，请更新内置 AAR 后重试",
                e,
            )
        }
    }

    suspend fun diarize(
        context: Context,
        samples: FloatArray,
        onProgress: (SpeakerRecognitionProgressUpdate) -> Unit = {},
    ): List<Segment> = diarizeMutex.withLock {
        val ownerJob = currentCoroutineContext()[Job]
        val checkActive: () -> Unit = { ownerJob?.ensureActive() }
        withContext(Dispatchers.IO) {
            require(samples.isNotEmpty()) { "没有可用于人物识别的音频" }
            val models = prepareModelFiles(context, ownerJob, checkActive, onProgress)
            onProgress(
                SpeakerRecognitionProgressUpdate(
                    stage = SpeakerRecognitionStage.INITIALIZING_MODEL,
                    message = "正在初始化人物识别模型…",
                ),
            )
            val session = createNativeSession(models, speakerThreads(context), checkActive)
            try {
                onProgress(
                    SpeakerRecognitionProgressUpdate(
                        stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
                        message = "正在分析人物声音…",
                    ),
                )
                processWithNativeSession(session, samples, checkActive)
            } finally {
                session.close()
            }
        }
    }

    /**
     * Analyze a complete decoded audio file in bounded windows.
     *
     * Only one 2-minute FloatArray (about 7.68 MB) is resident at a time, reducing the longest
     * non-interruptible native inference window. The native ONNX models and diarizer
     * instance are initialized once per file, then reused for every window. Adjacent windows
     * overlap so local speaker IDs can be reconciled without rereading the entire prior timeline.
     */
    suspend fun diarizePcmFile(
        context: Context,
        pcmFile: AudioExtract.PcmFile,
        chunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
        onProgress: (SpeakerRecognitionProgressUpdate) -> Unit = {},
    ): List<Segment> = diarizeMutex.withLock {
        require(pcmFile.sampleRate == 16_000) {
            "人物识别需要 16 kHz PCM，当前采样率为 " + pcmFile.sampleRate + " Hz"
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
        val checkActive: () -> Unit = { ownerJob?.ensureActive() }

        withContext(Dispatchers.IO) {
            val models = prepareModelFiles(context, ownerJob, checkActive, onProgress)
            onProgress(
                SpeakerRecognitionProgressUpdate(
                    stage = SpeakerRecognitionStage.INITIALIZING_MODEL,
                    message = "正在初始化人物识别模型…",
                ),
            )
            val numThreads = speakerThreads(context)
            val playbackLoadActive = DevicePerformanceManager.isPlaybackLoadActive()
            val initStartedNs = System.nanoTime()
            val session = createNativeSession(models, numThreads, checkActive)
            val initMs = (System.nanoTime() - initStartedNs) / 1_000_000L
            val inferenceStartedNs = System.nanoTime()
            try {
                val merged = ArrayList<SpeakerChunkIdentityPolicy.Segment>()
                var previousWindowSegments = emptyList<SpeakerChunkIdentityPolicy.Segment>()
                var nextSpeakerId = 0
                var chunkIndex = 0L
                var chunkStartSample = 0L
                var previousChunkEndSample = 0L

                pcmFile.openReader { checkActive() }.use { reader ->
                    while (chunkStartSample < pcmFile.sampleCount) {
                        checkActive()
                        val chunkEndSample = minOf(
                            pcmFile.sampleCount,
                            chunkStartSample + chunkSamples,
                        )
                        val sampleCount = (chunkEndSample - chunkStartSample).toInt()
                        if (sampleCount <= 0) break
                        chunkIndex++
                        onProgress(
                            SpeakerRecognitionProgressPolicy.analysisChunkStarted(
                                currentChunk = chunkIndex,
                                totalChunks = totalChunks,
                            ),
                        )
                        val samples = reader.readFloatSamples(
                            startSample = chunkStartSample,
                            count = sampleCount,
                        )
                        var lastInferenceProgressPercent = -1
                        val localSegments = try {
                            processWithNativeSession(
                                session = session,
                                samples = samples,
                                checkActive = checkActive,
                            ) { processedUnits, totalUnits ->
                                val update = SpeakerRecognitionProgressPolicy.analysisInferenceProgress(
                                    completedChunks = chunkIndex - 1L,
                                    totalChunks = totalChunks,
                                    processedInferenceUnits = processedUnits,
                                    totalInferenceUnits = totalUnits,
                                )
                                val percent = update.progressPercent
                                // JNI may invoke this once per voice segment. Publish only when
                                // the visible percentage changes, keeping UI and disk writes bounded.
                                if (percent != null && percent != lastInferenceProgressPercent) {
                                    onProgress(update)
                                    lastInferenceProgressPercent = percent
                                }
                            }
                        } finally {
                            samples.fill(0f)
                        }
                        checkActive()

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
                            previousWindowSegments = normalized.windowSegments
                            nextSpeakerId = normalized.nextSpeakerId
                        } else {
                            val overlapStartMs = chunkStartSample * 1_000L / rate
                            val overlapEndMs = previousChunkEndSample * 1_000L / rate
                            val reconciled = SpeakerChunkIdentityPolicy.mergeWindow(
                                previous = previousWindowSegments,
                                current = absoluteSegments,
                                overlapStartMs = overlapStartMs,
                                overlapEndMs = overlapEndMs,
                                nextSpeakerId = nextSpeakerId,
                            )
                            merged.addAll(reconciled.segments)
                            previousWindowSegments = reconciled.windowSegments
                            nextSpeakerId = reconciled.nextSpeakerId
                        }

                        previousChunkEndSample = chunkEndSample
                        onProgress(
                            SpeakerRecognitionProgressPolicy.analysisChunkCompleted(
                                completedChunks = chunkIndex,
                                totalChunks = totalChunks,
                            ),
                        )
                        if (chunkEndSample >= pcmFile.sampleCount) break
                        chunkStartSample = (chunkEndSample - overlapSamples)
                            .coerceAtLeast(chunkStartSample + 1L)
                    }
                }

                checkActive()
                val inferenceMs = (System.nanoTime() - inferenceStartedNs) / 1_000_000L
                val audioSec = pcmFile.sampleCount.toDouble() / pcmFile.sampleRate.toDouble()
                // Timing breakdown so a slow run shows whether model loading or inference dominates.
                runCatching {
                    io.translatetube.local.util.AppLogStore.info(
                        context,
                        "SpeakerDiarizer",
                        "人物识别推理耗时统计",
                        diagnosticContext = mapOf(
                            "推理线程数（每个模型）" to numThreads.toString(),
                            "视频播放负载是否活跃" to playbackLoadActive.toString(),
                            "音频时长（秒）" to audioSec.toInt().toString(),
                            "音频分段数" to totalChunks.toString(),
                            "模型初始化 ms" to initMs.toString(),
                            "模型内部进度回调是否可用" to session.supportsInferenceProgress.toString(),
                            "分段推理总 ms" to inferenceMs.toString(),
                            "推理耗时÷音频时长" to
                                String.format(java.util.Locale.US, "%.2f", inferenceMs / 1000.0 / audioSec.coerceAtLeast(1.0)),
                            "识别出的说话片段数" to merged.size.toString(),
                        ),
                    )
                }
                merged.map { segment ->
                    Segment(
                        startSec = segment.startMs / 1_000f,
                        endSec = segment.endMs / 1_000f,
                        speaker = segment.speakerId,
                        confidence = segment.confidence,
                    )
                }
            } finally {
                session.close()
            }
        }
    }

    private fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        checkActive: () -> Unit,
        ownerJob: kotlinx.coroutines.Job?,
        progressStage: SpeakerRecognitionStage,
        progressLabel: String,
        onProgress: (SpeakerRecognitionProgressUpdate) -> Unit,
    ) {
        checkActive()
        onProgress(
            SpeakerRecognitionProgressUpdate(
                stage = progressStage,
                message = "$progressLabel · 正在连接…",
                progressItem = progressLabel,
            ),
        )
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
                                onProgress(
                                    SpeakerRecognitionProgressUpdate(
                                        stage = progressStage,
                                        message = progressLabel,
                                        progressPercent = percent,
                                        completedUnits = total,
                                        totalUnits = expectedBytes.takeIf { it > 0L },
                                        progressItem = progressLabel,
                                    ),
                                )
                                lastProgressPercent = percent
                            } else if (
                                percent == null &&
                                total - lastUnknownLengthReportBytes >= 512L * 1024L
                            ) {
                                onProgress(
                                    SpeakerRecognitionProgressUpdate(
                                        stage = progressStage,
                                        message = "$progressLabel · 已下载 ${total / (1024L * 1024L)} MB",
                                        completedUnits = total,
                                        totalUnits = expectedBytes.takeIf { it > 0L },
                                        progressItem = progressLabel,
                                    ),
                                )
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
                onProgress(
                    SpeakerRecognitionProgressUpdate(
                        stage = progressStage,
                        message = progressLabel,
                        progressPercent = 100,
                        completedUnits = total,
                        totalUnits = expectedBytes.takeIf { it > 0L },
                        progressItem = progressLabel,
                    ),
                )
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
