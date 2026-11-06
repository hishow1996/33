package io.translatetube.local.tts

import android.content.Context
import io.translatetube.local.performance.DevicePerformanceManager
import io.translatetube.local.performance.DevicePerformancePolicy
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** TTS 统一输出：合成的 PCM 音频 */
data class TtsAudio(val samples: FloatArray, val sampleRate: Int)

/** Marker for on-device neural speech engines whose inference competes with video decoding. */
interface LocalCpuBoundTtsEngine

interface TtsEngine {
    val name: String
    /** Stable synthesis configuration identity, used to avoid stale PCM cache hits. */
    val cacheKey: String get() = name
    /** Whether speakerId can change the actual generated voice/audio for this engine. */
    val speakerIdAffectsOutput: Boolean get() = true
    fun synth(text: String, speed: Float = 1.0f, speakerId: Int = -1): TtsAudio

    /** Cancellation-aware path for post-processing work after a provider returns PCM. */
    fun synthCancellable(
        text: String,
        speed: Float = 1.0f,
        speakerId: Int = -1,
        checkActive: () -> Unit,
    ): TtsAudio {
        checkActive()
        val result = synth(text, speed, speakerId)
        checkActive()
        return result
    }

    fun close()
}

/**
 * Conservative CPU thread selection for local ONNX TTS on phones.
 * Larger phones get at most three workers, with one fewer during active video playback so
 * neural speech synthesis leaves CPU headroom for Media3 decoding and real-time audio.
 */
internal object TtsCpuThreadPolicy {
    fun recommendedThreads(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
        when {
            availableProcessors <= 2 -> 1
            availableProcessors >= 6 -> 3
            else -> 2
        }

    fun recommendedThreads(context: Context): Int =
        DevicePerformancePolicy.recommendedTtsThreads(
            DevicePerformanceManager.snapshot(context),
            DevicePerformanceManager.isPlaybackLoadActive(),
        )
}

/**
 * Optional direct timeline-speech contract for engines that explicitly support live utterances.
 * SystemTts uses this path only in immediate mode; generated mode uses the WAV-to-PCM path
 * below to share caching, normalization, timing, pause and cancellation with local neural models.
 */
interface TimelineTtsPlayback {
    /** True only when the user selected immediate, live Android TTS instead of generated PCM. */
    val useDirectTimelinePlayback: Boolean get() = true

    fun speakOnTimeline(
        text: String,
        speed: Float,
        speakerId: Int,
        volume: Float,
        utteranceId: String,
        onStarted: () -> Unit,
        onCompleted: (String?) -> Unit,
    ): Boolean

    fun stopTimelineSpeech(utteranceId: String? = null)
}

/** Kokoro v1.1 int8 本地神经 TTS（中文主力） */
class KokoroTts(
    private val ctx: Context,
    private val speakerId: Int = 14,
    private val numThreads: Int = TtsCpuThreadPolicy.recommendedThreads(ctx),
) : TtsEngine, LocalCpuBoundTtsEngine {
    override val name = "Kokoro"
    private val appCtx = ctx.applicationContext
    private val runtimeSignature by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        io.translatetube.local.data.ModelFiles.ttsCacheSignature(ctx)
    }
    override val cacheKey: String
        get() = "$name-default-${speakerId.coerceIn(0, 53)}-$runtimeSignature"
    private val tts: OfflineTts
    private val guard: NativeResourceGuard

    init {
        if (!NativeTtsRuntimeGate.awaitDeferredReleases()) {
            android.util.Log.w("KokoroTts", "Timed out waiting for retired native TTS releases")
        }
        io.translatetube.local.data.ModelFiles.ensureTtsRuntimeFiles(ctx)
        val m = io.translatetube.local.data.ModelFiles.ttsDir(ctx).absolutePath + "/"
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "${m}model.int8.onnx",
                    voices = "${m}voices.bin",
                    tokens = "${m}tokens.txt",
                    dataDir = "${m}espeak-ng-data",
                    lexicon = "${m}lexicon-us-en.txt,${m}lexicon-zh.txt",
                    dictDir = "${m}dict",
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = "${m}phone-zh.fst,${m}date-zh.fst,${m}number-zh.fst",
            maxNumSentences = 2,
        )
        // assets=null → sherpa-onnx 走文件系统绝对路径加载（方案 B）
        val nativeTts = OfflineTts(null, config)
        tts = nativeTts
        guard = NativeResourceGuard("Kokoro") { nativeTts.release() }
    }

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text, speed, speakerId) {}

    override fun synthCancellable(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio =
        synthInternal(text, speed, speakerId, checkActive)

    private fun synthInternal(
        text: String, speed: Float, speakerId: Int, checkActive: () -> Unit,
    ): TtsAudio {
        checkActive()
        if (text.isBlank()) return TtsAudio(FloatArray(0), 24000)
        TtsTextPolicy.validate(text)
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) return TtsAudio(FloatArray(0), 24000)
        val selectedSpeaker = (if (speakerId >= 0) speakerId else this.speakerId).coerceIn(0, 53)
        val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        val audio = try {
            guard.use { tts.generate(normalizedText, selectedSpeaker, safeSpeed) }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logKokoroFailure(
                "Kokoro 本地神经 TTS 推理失败",
                failure,
                "Sherpa-ONNX Kokoro 原生推理",
                normalizedText.length,
                selectedSpeaker,
                safeSpeed,
                android.os.SystemClock.elapsedRealtime() - startedAtMs,
            )
            throw failure
        }
        checkActive()
        val sampleRate = audio.sampleRate
        val rawSamples = audio.samples.size
        if (sampleRate !in 8_000..192_000 || rawSamples == 0) {
            val failure = IllegalStateException("Kokoro 模型返回无效音频：采样率=$sampleRate Hz，样本数=$rawSamples")
            android.util.Log.w("KokoroTts", "Invalid generated audio: rate=$sampleRate samples=$rawSamples")
            logKokoroFailure(
                "Kokoro 推理完成但返回无效 PCM",
                failure,
                "原生推理输出格式校验",
                normalizedText.length,
                selectedSpeaker,
                safeSpeed,
                android.os.SystemClock.elapsedRealtime() - startedAtMs,
                sampleRate,
                rawSamples,
            )
            return TtsAudio(FloatArray(0), sampleRate.coerceIn(8_000, 192_000))
        }
        val clean: FloatArray
        val trimmed: FloatArray
        try {
            clean = TtsPcmSanitizer.sanitizeInPlace(audio.samples, checkActive)
            trimmed = TtsEdgeSilenceTrimmer.trim(clean, sampleRate, checkActive)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logKokoroFailure(
                "Kokoro PCM 后处理失败",
                failure,
                "PCM 数值清理/首尾静音裁剪",
                normalizedText.length,
                selectedSpeaker,
                safeSpeed,
                android.os.SystemClock.elapsedRealtime() - startedAtMs,
                sampleRate,
                rawSamples,
            )
            throw failure
        }
        checkActive()
        if (trimmed.isEmpty()) {
            val failure = IllegalStateException("Kokoro PCM 后处理后没有剩余可播放样本")
            logKokoroFailure(
                "Kokoro 音频后处理结果为空",
                failure,
                "PCM 后处理结果校验",
                normalizedText.length,
                selectedSpeaker,
                safeSpeed,
                android.os.SystemClock.elapsedRealtime() - startedAtMs,
                sampleRate,
                rawSamples,
                trimmed.size,
            )
        }
        return TtsAudio(trimmed, sampleRate)
    }

    private fun logKokoroFailure(
        message: String,
        failure: Throwable,
        stage: String,
        textLength: Int,
        speaker: Int,
        speed: Float,
        elapsedMs: Long,
        sampleRate: Int? = null,
        sampleCount: Int? = null,
        outputSampleCount: Int? = null,
    ) {
        val dir = io.translatetube.local.data.ModelFiles.ttsDir(appCtx)
        val requiredFiles = listOf(
            "model.int8.onnx",
            "voices.bin",
            "tokens.txt",
            "lexicon-us-en.txt",
            "lexicon-zh.txt",
            "phone-zh.fst",
            "date-zh.fst",
            "number-zh.fst",
        )
        runCatching {
            io.translatetube.local.util.AppLogStore.error(
                appCtx,
                "KokoroTTS",
                message,
                failure,
                diagnosticContext = mapOf(
                    "配音服务商" to "Kokoro / Sherpa-ONNX",
                    "失败阶段" to stage,
                    "模型版本" to "Kokoro v1.1 int8",
                    "音色 ID" to speaker.toString(),
                    "ONNX 推理线程数" to numThreads.toString(),
                    "推理设备" to "CPU",
                    "当前语速" to speed.toString(),
                    "当前文本字符数" to textLength.toString(),
                    "当前字幕正文是否写入日志" to "否",
                    "模型目录是否存在" to dir.isDirectory.toString(),
                    "模型必需文件状态" to requiredFiles.joinToString(",") { name ->
                        val file = java.io.File(dir, name)
                        "$name:${file.isFile && file.length() > 0L}(${file.takeIf { it.isFile }?.length() ?: 0L} bytes)"
                    },
                    "多语言语音数据目录是否存在" to java.io.File(dir, "espeak-ng-data").isDirectory.toString(),
                    "模型目录可用空间 bytes" to dir.usableSpace.toString(),
                    "推理/处理耗时 ms" to elapsedMs.coerceAtLeast(0L).toString(),
                    "输出采样率 Hz" to (sampleRate?.toString() ?: "未知"),
                    "原始 PCM 样本数" to (sampleCount?.toString() ?: "未知"),
                    "后处理 PCM 样本数" to (outputSampleCount?.toString() ?: "未执行"),
                    "异常类型" to failure.javaClass.name,
                    "异常摘要" to (failure.message ?: "无异常消息").take(360),
                ),
            )
        }
    }

    override fun close() = guard.close()
}

enum class SystemTtsPlaybackMode {
    GENERATED,
    INSTANT;

    companion object {
        fun fromPreference(value: String?): SystemTtsPlaybackMode =
            if (value.equals("instant", ignoreCase = true)) INSTANT else GENERATED
    }
}

private object SystemTtsCallbackLogDispatcher {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "system-tts-callback-log").apply { isDaemon = true }
    }

    fun error(
        context: Context,
        stage: String,
        utteranceId: String,
        failure: Throwable,
    ) {
        runCatching {
            executor.execute {
                runCatching {
                    io.translatetube.local.util.AppLogStore.error(
                        context,
                        "SystemTTS",
                        "系统 TTS 即时朗读回调处理异常：$stage",
                        failure,
                        diagnosticContext = mapOf(
                            "回调阶段" to stage,
                            "朗读任务 ID" to utteranceId.take(120),
                            "播放模式" to "INSTANT",
                            "异常类型" to failure.javaClass.name,
                            "异常摘要" to (failure.message ?: "无异常消息").take(360),
                        ) + io.translatetube.local.util.AudioOutputDiagnostics.snapshot(context),
                    )
                }
            }
        }
    }
}

/** 系统 TTS：支持预生成 PCM 和即时朗读两种模式，复用同一个系统中文音色目录。 */
class SystemTts(
    context: Context,
    private val playbackMode: SystemTtsPlaybackMode = SystemTtsPlaybackMode.GENERATED,
) : TtsEngine, TimelineTtsPlayback {
    override val useDirectTimelinePlayback: Boolean
        get() = playbackMode == SystemTtsPlaybackMode.INSTANT
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    @Volatile private var defaultChineseVoice: android.speech.tts.Voice? = null
    @Volatile private var chineseVoiceCatalog: List<android.speech.tts.Voice> = emptyList()

    val chineseVoiceCount: Int get() = chineseVoiceCatalog.size
    val chineseVoiceNames: List<String> get() = chineseVoiceCatalog.map { it.name }
    val supportsMultipleChineseVoices: Boolean get() = chineseVoiceCount > 1

    // A single installed Chinese voice produces identical PCM for every detected speaker.
    // Avoid duplicating the same disk/RAM cache once per speaker in that case.
    override val speakerIdAffectsOutput: Boolean
        get() = SystemTtsSpeakerVoicePolicy.speakerIdAffectsOutput(chineseVoiceCount)

    private data class TimelineCallbacks(
        val onStarted: () -> Unit,
        val onCompleted: (String?) -> Unit,
        val state: TimelineTtsCallbackState = TimelineTtsCallbackState(),
    )

    private val timelineCallbacks =
        java.util.concurrent.ConcurrentHashMap<String, TimelineCallbacks>()
    @Volatile private var activeTimelineUtteranceId: String? = null

    // Keep this identity stable while synth() switches per-speaker voices. The selected default
    // voice and complete Chinese voice catalogue make PCM cache entries invalid when capabilities change.
    override val cacheKey: String
        get() {
            val engine = ttsRef.get()
            return listOf(
                name,
                engine?.defaultEngine.orEmpty(),
                SystemTtsOutputAudioPolicy.CACHE_SIGNATURE,
                defaultChineseVoice?.name.orEmpty(),
                chineseVoiceCatalog.joinToString(",") { "${it.name}@${it.locale}" },
            ).joinToString("|")
        }
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var closed = false
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var activeFailure: String? = null
    @Volatile private var activeSynthesisStartedAtNanos: Long = 0L
    /**
     * Makes utterance-id validation, latch publication, callbacks and close atomic with respect
     * to one another. Utterance callbacks may arrive on different threads.
     */
    private val lifecycleLock = Any()

    init {
        var initializationStatus = Int.MIN_VALUE
        val tts = TextToSpeech(appCtx) { status ->
            initializationStatus = status
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        ttsRef.set(tts)
        fun disposeFailedEngine() {
            ttsRef.compareAndSet(tts, null)
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        fun logInitializationFailure(
            stage: String,
            failure: Throwable? = null,
            extra: Map<String, String> = emptyMap(),
        ) {
            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
            val chineseVoices = voices.filter { it.locale.language.equals("zh", ignoreCase = true) }
            runCatching {
                io.translatetube.local.util.AppLogStore.error(
                    appCtx,
                    "SystemTTS",
                    "系统 TTS 初始化失败：$stage",
                    failure,
                    diagnosticContext = mapOf(
                        "失败阶段" to stage,
                        "播放模式" to playbackMode.name,
                        "系统 TTS 引擎包名" to runCatching { tts.defaultEngine }.getOrNull().orEmpty().ifBlank { "未知" },
                        "Android SDK" to android.os.Build.VERSION.SDK_INT.toString(),
                        "Android 版本" to android.os.Build.VERSION.RELEASE.orEmpty(),
                        "系统默认语言" to Locale.getDefault().toLanguageTag(),
                        "系统 TTS 初始化回调状态码" to initializationStatus.toString(),
                        "系统 TTS 是否初始化成功" to ok.toString(),
                        "系统已安装语音总数" to voices.size.toString(),
                        "系统中文音色数量" to chineseVoices.size.toString(),
                        "系统中文音色名称" to chineseVoices.take(16).joinToString(",") { it.name }.ifBlank { "无" },
                        "当前选择的中文音色" to defaultChineseVoice?.name.orEmpty().ifBlank { "尚未选择" },
                        "失败时是否已释放 TTS 实例" to (ttsRef.get() !== tts).toString(),
                    ) + extra,
                )
            }
        }
        val initialized = try {
            ready.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            logInitializationFailure("等待系统初始化回调时被中断", interrupted, mapOf("等待时长上限 ms" to "15000"))
            disposeFailedEngine()
            Thread.currentThread().interrupt()
            throw IllegalStateException("等待手机系统 TTS 初始化时被中断", interrupted)
        }
        if (!initialized) {
            logInitializationFailure(
                "初始化回调超时",
                extra = mapOf("等待时长上限 ms" to "15000", "是否收到初始化回调" to "否"),
            )
            disposeFailedEngine()
            throw IllegalStateException("手机系统 TTS 初始化超时（15 秒），请检查默认文字转语音服务")
        }
        if (!ok) {
            logInitializationFailure("系统 TTS 初始化回调返回失败")
            disposeFailedEngine()
            throw IllegalStateException("手机系统文字转语音服务启动失败，请检查系统 TTS 引擎")
        }

        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            // This mode must remain the selected system TTS engine. Do not silently switch
            // to another locale or another engine when Chinese voice data is missing.
            logInitializationFailure(
                "系统 TTS 不支持简体中文或缺少中文语音数据",
                extra = mapOf(
                    "请求语言" to Locale.SIMPLIFIED_CHINESE.toLanguageTag(),
                    "setLanguage 返回码" to languageResult.toString(),
                    "LANG_AVAILABLE 最低值" to TextToSpeech.LANG_AVAILABLE.toString(),
                    "LANG_COUNTRY_AVAILABLE 值" to TextToSpeech.LANG_COUNTRY_AVAILABLE.toString(),
                    "LANG_COUNTRY_VAR_AVAILABLE 值" to TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE.toString(),
                ),
            )
            disposeFailedEngine()
            throw IllegalStateException("系统 TTS 未安装或不支持中文语音数据")
        }

        val allChineseVoices = tts.voices.orEmpty()
            .filter { it.locale.language.equals("zh", ignoreCase = true) }
            .distinctBy { it.name }
            .sortedBy { it.name }
        // Expose every installed Chinese voice, including network-backed voices, so the user can
        // explicitly choose any voice supported by the currently selected system TTS engine.
        val availableChineseVoices = allChineseVoices
        val currentChineseVoice = tts.voice?.takeIf {
            it.locale.language.equals("zh", ignoreCase = true)
        }
        val configuredVoiceName = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("system_tts_voice_name", null)
        val defaultVoice = SystemTtsSpeakerVoicePolicy.chooseConfiguredVoice(
            configuredVoiceName,
            currentChineseVoice,
            availableChineseVoices,
        ) { it.name }
        if (defaultVoice == null) {
            logInitializationFailure(
                "系统 TTS 初始化成功，但没有可用中文音色",
                extra = mapOf(
                    "中文音色候选数量" to availableChineseVoices.size.toString(),
                    "配置的音色是否存在" to configuredVoiceName.isNullOrBlank().not().toString(),
                    "当前系统音色语言" to tts.voice?.locale?.toLanguageTag().orEmpty().ifBlank { "未知" },
                ),
            )
            disposeFailedEngine()
            throw IllegalStateException("系统 TTS 没有可用的中文音色")
        }
        val catalogue = (listOf(defaultVoice) + availableChineseVoices)
            .distinctBy { it.name }
        if (tts.voice?.name != defaultVoice.name) {
            val voiceResult = tts.setVoice(defaultVoice)
            if (voiceResult != TextToSpeech.SUCCESS) {
                logInitializationFailure(
                    "无法切换到用户选定的中文音色",
                    extra = mapOf(
                        "目标音色名称" to defaultVoice.name,
                        "目标音色语言" to defaultVoice.locale.toLanguageTag(),
                        "setVoice 返回码" to voiceResult.toString(),
                    ),
                )
                disposeFailedEngine()
                throw IllegalStateException("系统 TTS 无法切换到可用中文音色：${defaultVoice.name}")
            }
        }
        defaultChineseVoice = defaultVoice
        chineseVoiceCatalog = catalogue
        runCatching {
            io.translatetube.local.util.AppLogStore.info(
                appCtx,
                "SystemTTS",
                "系统 TTS 初始化完成",
                diagnosticContext = mapOf(
                    "播放模式" to playbackMode.name,
                    "系统 TTS 引擎包名" to runCatching { tts.defaultEngine }.getOrNull().orEmpty().ifBlank { "未知" },
                    "系统 TTS 初始化回调状态码" to initializationStatus.toString(),
                    "请求语言" to Locale.SIMPLIFIED_CHINESE.toLanguageTag(),
                    "当前默认中文音色" to defaultVoice.name,
                    "当前默认音色语言" to defaultVoice.locale.toLanguageTag(),
                    "中文音色数量" to catalogue.size.toString(),
                    "可用中文音色" to catalogue.take(16).joinToString(",") { it.name },
                    "是否支持多人物音色输出" to SystemTtsSpeakerVoicePolicy.speakerIdAffectsOutput(catalogue.size).toString(),
                ),
            )
        }
        // The player UI must list the same ordered catalogue that synth() indexes. This avoids
        // pretending that the Kokoro 0-53 palette maps 1:1 to OEM-provided system voices.
        appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString(
                SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY,
                SystemTtsSpeakerVoicePolicy.encodeVoiceCatalog(catalogue.map { it.name }),
            )
            .apply()

        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId?.takeIf { it.isNotBlank() } ?: return
                val callbacks = synchronized(lifecycleLock) {
                    val current = timelineCallbacks[id] ?: return
                    if (closed || activeTimelineUtteranceId != id || !current.state.beginStart()) return
                    current
                }

                val callbackFailure = runCatching { callbacks.onStarted() }.exceptionOrNull()
                if (callbackFailure != null) {
                    android.util.Log.e("SystemTts", "Instant TTS start callback failed", callbackFailure)
                    SystemTtsCallbackLogDispatcher.error(
                        appCtx,
                        "onStart",
                        id,
                        callbackFailure,
                    )
                }

                val completion = synchronized(lifecycleLock) {
                    val decision = callbacks.state.finishStart(callbackFailure)
                    if (decision.completionReady) {
                        timelineCallbacks.remove(id, callbacks)
                        if (activeTimelineUtteranceId == id) activeTimelineUtteranceId = null
                    }
                    decision
                }
                if (completion.completionReady) {
                    dispatchTimelineCompletion(id, callbacks, completion.error)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (finishTimelineUtterance(utteranceId, null)) return
                val elapsedMs = synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeLatch?.countDown()
                        ((System.nanoTime() - activeSynthesisStartedAtNanos).coerceAtLeast(0L) / 1_000_000L)
                    } else {
                        null
                    }
                }
                if (elapsedMs != null) {
                    android.util.Log.d("SystemTts", "WAV synthesis callback completed in ${elapsedMs} ms")
                }
            }

            override fun onError(utteranceId: String?) {
                if (finishTimelineUtterance(utteranceId, "系统 TTS 即时朗读失败")) return
                val matched = synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = "系统 TTS 合成回调返回错误"
                        activeLatch?.countDown()
                        true
                    } else false
                }
                if (matched) android.util.Log.e("SystemTts", "WAV synthesis callback failed")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (finishTimelineUtterance(utteranceId, "系统 TTS 即时朗读失败（错误码 $errorCode）")) return
                val matched = synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = "系统 TTS 合成失败（错误码 $errorCode）"
                        activeLatch?.countDown()
                        true
                    } else false
                }
                if (matched) android.util.Log.e("SystemTts", "WAV synthesis callback failed, code=$errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (finishTimelineUtterance(
                        utteranceId,
                        if (interrupted) "系统 TTS 即时朗读被中断" else "系统 TTS 即时朗读被队列取消",
                    )
                ) return
                val matched = synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = if (interrupted) {
                            "系统 TTS 合成过程中被中断"
                        } else {
                            "系统 TTS 合成在开始前被取消或从队列中移除"
                        }
                        activeLatch?.countDown()
                        true
                    } else false
                }
                if (matched) {
                    android.util.Log.w(
                        "SystemTts",
                        "WAV synthesis stopped: interrupted=$interrupted",
                    )
                }
            }
        })
    }

    private fun dispatchTimelineCompletion(
        utteranceId: String,
        callbacks: TimelineCallbacks,
        error: String?,
    ) {
        runCatching { callbacks.onCompleted(error) }
            .onFailure { failure ->
                android.util.Log.e("SystemTts", "Instant TTS completion callback failed", failure)
                SystemTtsCallbackLogDispatcher.error(
                    appCtx,
                    "onCompleted",
                    utteranceId,
                    failure,
                )
            }
    }

    private fun finishTimelineUtterance(utteranceId: String?, error: String?): Boolean {
        if (utteranceId.isNullOrBlank()) return false
        val result = synchronized(lifecycleLock) {
            val callbacks = timelineCallbacks[utteranceId] ?: return@synchronized null
            val decision = callbacks.state.requestFinish(error)
            if (!decision.accepted) return@synchronized null
            if (decision.completionReady) {
                timelineCallbacks.remove(utteranceId, callbacks)
                if (activeTimelineUtteranceId == utteranceId) activeTimelineUtteranceId = null
            }
            callbacks to decision
        } ?: return false

        if (result.second.completionReady) {
            dispatchTimelineCompletion(utteranceId, result.first, result.second.error)
        }
        return true
    }

    @Synchronized
    override fun speakOnTimeline(
        text: String,
        speed: Float,
        speakerId: Int,
        volume: Float,
        utteranceId: String,
        onStarted: () -> Unit,
        onCompleted: (String?) -> Unit,
    ): Boolean {
        if (!useDirectTimelinePlayback || text.isBlank() || utteranceId.isBlank()) return false
        var callbackRegistered = false
        return try {
            TtsTextPolicy.validate(text)
            val normalizedText = text.replace(Regex("\\s+"), " ").trim()
            if (normalizedText.isEmpty()) return false
            val engine = synchronized(lifecycleLock) {
                if (closed || !ok) {
                    throw IllegalStateException("系统 TTS 已关闭或尚未初始化成功")
                }
                ttsRef.get() ?: throw IllegalStateException("系统 TTS 引擎实例不可用")
            }
            val voices = chineseVoiceCatalog
            val voiceIndex = SystemTtsSpeakerVoicePolicy.indexForSpeaker(speakerId, voices.size)
            val targetVoice = voices.getOrNull(voiceIndex) ?: defaultChineseVoice
                ?: throw IllegalStateException("系统 TTS 没有可用的中文音色")
            if (engine.voice?.name != targetVoice.name && engine.setVoice(targetVoice) != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 无法切换到所选中文音色：${targetVoice.name}")
            }
            val safeSpeed = TtsSpeechRatePolicy.normalizeTimeline(speed)
            if (engine.setSpeechRate(safeSpeed) != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 无法设置即时朗读语速")
            }
            stopTimelineSpeech()
            val safeVolume = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, safeVolume)
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            synchronized(lifecycleLock) {
                if (closed || ttsRef.get() !== engine) {
                    throw IllegalStateException("系统 TTS 在提交朗读前已关闭或被替换")
                }
                activeTimelineUtteranceId = utteranceId
                timelineCallbacks[utteranceId] = TimelineCallbacks(onStarted, onCompleted)
                callbackRegistered = true
            }
            val result = engine.speak(
                normalizedText,
                TextToSpeech.QUEUE_FLUSH,
                params,
                utteranceId,
            )
            if (result != TextToSpeech.SUCCESS) {
                finishTimelineUtterance(utteranceId, "系统 TTS 无法接受即时朗读请求（状态码 $result）")
                false
            } else {
                android.util.Log.d(
                    "SystemTts",
                    "Instant speak queued: chars=${normalizedText.length} voice=${targetVoice.name} speed=$safeSpeed",
                )
                true
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            if (callbackRegistered) {
                finishTimelineUtterance(utteranceId, "系统 TTS 即时朗读请求提交期间被取消")
            }
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("SystemTts", "Instant TTS request failed", error)
            val detail = "系统 TTS 即时朗读请求异常：${error.message ?: error.javaClass.simpleName}"
            if (callbackRegistered) {
                finishTimelineUtterance(utteranceId, detail)
            } else {
                runCatching { onCompleted(detail) }
                    .onFailure { callbackFailure ->
                        SystemTtsCallbackLogDispatcher.error(
                            appCtx,
                            "请求失败后的 onCompleted",
                            utteranceId,
                            callbackFailure,
                        )
                    }
            }
            false
        }
    }

    @Synchronized
    override fun stopTimelineSpeech(utteranceId: String?) {
        val engine = synchronized(lifecycleLock) {
            val active = activeTimelineUtteranceId
            if (active == null || (utteranceId != null && utteranceId != active)) {
                null
            } else {
                timelineCallbacks.remove(active)?.state?.cancel()
                activeTimelineUtteranceId = null
                ttsRef.get()
            }
        } ?: return
        runCatching { engine.stop() }
            .onFailure { android.util.Log.w("SystemTts", "Unable to stop instant TTS speech", it) }
    }

    @Synchronized
    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text, speed, speakerId) {}

    @Synchronized
    override fun synthCancellable(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio =
        synthInternal(text, speed, speakerId, checkActive)

    private fun synthInternal(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio {
        checkActive()
        if (text.isBlank()) return TtsAudio(FloatArray(0), SystemTtsOutputAudioPolicy.TARGET_SAMPLE_RATE)
        // Validate the original payload before normalization so pathological input cannot allocate
        // another large string. Collapse line breaks/repeated whitespace because system voices on
        // some OEMs insert long pauses or produce unusable audio for raw multiline subtitle text.
        TtsTextPolicy.validate(text)
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) {
            return TtsAudio(FloatArray(0), SystemTtsOutputAudioPolicy.TARGET_SAMPLE_RATE)
        }

        // Create the temporary file before publishing a request. If close() races with this
        // allocation, the closed check below prevents a request from waiting on an unobserved latch.
        val tmp = java.io.File.createTempFile("tts_", ".wav", contextCacheDir())
        val utteranceId = "system-tts-" + System.nanoTime()
        val completed = java.util.concurrent.CountDownLatch(1)
        var failureStage = "创建系统 TTS 合成请求"
        var selectedVoiceName = defaultChineseVoice?.name.orEmpty().ifBlank { "未知" }
        var selectedSpeedForLog = Float.NaN
        var callbackFailureForLog: String? = null
        var decodedSampleRateForLog = 0
        var decodedSampleCountForLog = 0
        var normalizedSampleRateForLog = 0
        var normalizedSampleCountForLog = 0
        try {
            val tts = synchronized(lifecycleLock) {
                if (closed) throw IllegalStateException("系统 TTS 实例已经关闭")
                val instance = ttsRef.get()
                    ?: throw IllegalStateException("系统 TTS 实例已经关闭")
                if (!ok) throw IllegalStateException("系统 TTS 尚未初始化成功")
                activeUtteranceId = utteranceId
                activeLatch = completed
                activeFailure = null
                activeSynthesisStartedAtNanos = System.nanoTime()
                instance
            }
            val defaultVoice = defaultChineseVoice
                ?: throw IllegalStateException("系统 TTS 没有可用的中文音色")
            val voices = chineseVoiceCatalog
            val voiceIndex = SystemTtsSpeakerVoicePolicy.indexForSpeaker(speakerId, voices.size)
            val targetVoice = voices.getOrNull(voiceIndex) ?: defaultVoice
            selectedVoiceName = targetVoice.name
            failureStage = "切换系统中文音色"
            if (tts.voice?.name != targetVoice.name) {
                val voiceResult = tts.setVoice(targetVoice)
                if (voiceResult != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("系统 TTS 无法切换到人物音色：${targetVoice.name}")
                }
            }
            val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
            selectedSpeedForLog = safeSpeed
            failureStage = "设置系统 TTS 语速"
            if (tts.setSpeechRate(safeSpeed) != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 无法设置配音语速（" + safeSpeed + "×）")
            }
            failureStage = "提交系统 TTS WAV 合成请求"
            val result = tts.synthesizeToFile(normalizedText, android.os.Bundle(), tmp, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                android.util.Log.e(
                    "SystemTts",
                    "synthesizeToFile rejected request: result=$result chars=${normalizedText.length} voice=${targetVoice.name}",
                )
                throw IllegalStateException("系统 TTS 拒绝了本次合成（状态码 $result）")
            }
            val timeoutMs = SystemTtsTimeoutPolicy.timeoutMs(normalizedText, safeSpeed)
            failureStage = "等待系统 TTS 合成回调"
            val timeoutDeadlineNanos = System.nanoTime() +
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                var completedInTime = false
                while (!completedInTime) {
                    checkActive()
                    val remainingNanos = timeoutDeadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        android.util.Log.e(
                            "SystemTts",
                            "WAV synthesis timed out after ${timeoutMs} ms; chars=${normalizedText.length} voice=${targetVoice.name}",
                        )
                        runCatching { tts.stop() }
                        throw IllegalStateException("系统 TTS 合成超时（${timeoutMs / 1000} 秒）")
                    }
                    val waitMs = minOf(
                        100L,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    )
                    completedInTime = completed.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                runCatching { tts.stop() }
                throw cancelled
            } catch (interrupted: InterruptedException) {
                runCatching { tts.stop() }
                try {
                    checkActive()
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                }
                Thread.currentThread().interrupt()
                throw IllegalStateException("等待系统 TTS 合成结果时线程被中断", interrupted)
            }
            checkActive()
            if (closed) throw IllegalStateException("系统 TTS 已关闭，本次合成已取消")
            activeFailure?.let { failure ->
                callbackFailureForLog = failure
                throw IllegalStateException(failure)
            }
            failureStage = "解码系统 TTS 输出 WAV"
            val checkTtsActive = {
                checkActive()
                if (closed) throw java.util.concurrent.CancellationException("系统 TTS 已关闭，本次合成已取消")
            }
            val audio = SystemTtsWavDecoder.decode(tmp, checkTtsActive)
            decodedSampleRateForLog = audio.sampleRate
            decodedSampleCountForLog = audio.samples.size
            checkTtsActive()
            if (audio.samples.isEmpty()) {
                throw IllegalStateException("系统 TTS 已返回，但生成的 WAV 没有可播放音频")
            }
            failureStage = "标准化系统 TTS PCM 输出"
            val normalized = SystemTtsOutputAudioPolicy.prepareForPlayback(
                audio,
                checkActive = checkTtsActive,
            )
            normalizedSampleRateForLog = normalized.sampleRate
            normalizedSampleCountForLog = normalized.samples.size
            val mediaVolume = runCatching {
                (appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                    ?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            }.getOrNull()
            val elapsedMs = ((System.nanoTime() - activeSynthesisStartedAtNanos).coerceAtLeast(0L) / 1_000_000L)
            android.util.Log.d(
                "SystemTts",
                "PCM ready: chars=${normalizedText.length} voice=${targetVoice.name} speed=$safeSpeed " +
                    "elapsed=${elapsedMs}ms wav=${tmp.length()}B mediaVolume=$mediaVolume " +
                    "input=${audio.sampleRate}Hz/${audio.samples.size} samples, " +
                    "output=${normalized.sampleRate}Hz/${normalized.samples.size} samples",
            )
            if (mediaVolume == 0) {
                android.util.Log.w("SystemTts", "STREAM_MUSIC volume is 0; generated PCM will follow media-volume routing")
            }
            return normalized
        } catch (cancelled: java.util.concurrent.CancellationException) {
            android.util.Log.d(
                "SystemTts",
                "WAV synthesis cancelled: chars=${normalizedText.length}",
            )
            throw cancelled
        } catch (error: Exception) {
            val elapsedMs = if (activeSynthesisStartedAtNanos > 0L) {
                (System.nanoTime() - activeSynthesisStartedAtNanos).coerceAtLeast(0L) / 1_000_000L
            } else 0L
            android.util.Log.e(
                "SystemTts",
                "WAV synthesis failed: stage=$failureStage chars=${normalizedText.length} detail=${error.message ?: error.javaClass.simpleName}",
                error,
            )
            runCatching {
                io.translatetube.local.util.AppLogStore.error(
                    appCtx,
                    "SystemTTS",
                    "系统 TTS 配音合成失败：$failureStage",
                    error,
                    diagnosticContext = mapOf(
                        "失败阶段" to failureStage,
                        "系统 TTS 引擎包名" to runCatching { ttsRef.get()?.defaultEngine }.getOrNull().orEmpty().ifBlank { "未知" },
                        "播放模式" to playbackMode.name,
                        "人物音色索引" to speakerId.toString(),
                        "实际选用的系统音色" to selectedVoiceName,
                        "当前语速" to selectedSpeedForLog.toString(),
                        "当前文本字符数" to normalizedText.length.toString(),
                        "当前字幕正文是否写入日志" to "否",
                        "WAV 临时文件是否存在" to tmp.exists().toString(),
                        "WAV 临时文件大小 bytes" to tmp.takeIf { it.isFile }?.length()?.toString().orEmpty().ifBlank { "不存在" },
                        "系统 TTS 回调失败原因" to callbackFailureForLog.orEmpty().ifBlank { "无" },
                        "WAV 解码采样率 Hz" to decodedSampleRateForLog.toString(),
                        "WAV 解码 PCM 样本数" to decodedSampleCountForLog.toString(),
                        "标准化后采样率 Hz" to normalizedSampleRateForLog.toString(),
                        "标准化后 PCM 样本数" to normalizedSampleCountForLog.toString(),
                        "系统 TTS 超时策略 ms" to if (selectedSpeedForLog.isFinite()) {
                            SystemTtsTimeoutPolicy.timeoutMs(normalizedText, selectedSpeedForLog).toString()
                        } else "未计算",
                        "当前合成耗时 ms" to elapsedMs.toString(),
                        "异常类型" to error.javaClass.name,
                        "异常摘要" to (error.message ?: "无异常消息").take(360),
                    ),
                )
            }
            throw error
        } finally {
            synchronized(lifecycleLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    activeLatch = null
                    activeFailure = null
                    activeSynthesisStartedAtNanos = 0L
                }
            }
            tmp.delete()
        }
    }

    private val tempWavDirectory: java.io.File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        java.io.File(appCtx.cacheDir, "tts_tmp").apply {
            if (mkdirs() || isDirectory) TtsTempFilePolicy.cleanupStale(this)
        }
    }

    private fun contextCacheDir(): java.io.File = tempWavDirectory

    private fun readWav(file: java.io.File): TtsAudio = SystemTtsWavDecoder.decode(file)

    override fun close() {
        // Publish closure and wake the active synthesis under the same lock used by request setup
        // and callbacks. This prevents close() from missing the brief setup window before a latch
        // is installed, and avoids a stale callback waking the next utterance's latch.
        val engine = synchronized(lifecycleLock) {
            if (closed) {
                null
            } else {
                closed = true
                activeLatch?.countDown()
                timelineCallbacks.values.forEach { it.state.cancel() }
                timelineCallbacks.clear()
                activeTimelineUtteranceId = null
                ttsRef.getAndSet(null)
            }
        }
        if (engine != null) {
            runCatching { engine.stop() }
                .onFailure { android.util.Log.w("SystemTts", "Could not stop system TTS during close", it) }
            runCatching { engine.shutdown() }
                .onFailure { android.util.Log.w("SystemTts", "Could not shut down system TTS cleanly", it) }
        }
    }
}
