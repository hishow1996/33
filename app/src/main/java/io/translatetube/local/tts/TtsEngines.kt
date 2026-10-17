package io.translatetube.local.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** TTS 统一输出：合成的 PCM 音频 */
data class TtsAudio(val samples: FloatArray, val sampleRate: Int)

interface TtsEngine {
    val name: String
    /** Stable synthesis configuration identity, used to avoid stale PCM cache hits. */
    val cacheKey: String get() = name
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
 * Small CPUs keep two or fewer workers; larger big.LITTLE devices get at most three so
 * inference can use more cores without aggressively oversubscribing a thermally limited phone.
 */
internal object TtsCpuThreadPolicy {
    fun recommendedThreads(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
        when {
            availableProcessors <= 2 -> 1
            availableProcessors >= 6 -> 3
            else -> 2
        }
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
    private val numThreads: Int = TtsCpuThreadPolicy.recommendedThreads(),
) : TtsEngine {
    override val name = "Kokoro"
    private val runtimeSignature by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        io.translatetube.local.data.ModelFiles.ttsCacheSignature(ctx)
    }
    override val cacheKey: String
        get() = "$name-default-${speakerId.coerceIn(0, 53)}-$runtimeSignature"
    private val tts: OfflineTts

    init {
        io.translatetube.local.data.ModelFiles.ensureTtsRuntimeFiles(ctx)
        val m = io.translatetube.local.data.ModelFiles.ttsDir(ctx).absolutePath + "/"
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "${m}model.int8.onnx",
                    voices = "${m}voices.bin",
                    tokens = "${m}tokens.txt",
                    dataDir = "${m}espeak-ng-data",
                    lexicon = "${m}lexicon-zh.txt",
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
        tts = OfflineTts(null, config)
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
        val audio = tts.generate(normalizedText, selectedSpeaker, safeSpeed)
        checkActive()
        val sampleRate = audio.sampleRate
        if (sampleRate !in 8_000..192_000 || audio.samples.isEmpty()) {
            android.util.Log.w("KokoroTts", "Invalid generated audio: rate=$sampleRate samples=${audio.samples.size}")
            return TtsAudio(FloatArray(0), sampleRate.coerceIn(8_000, 192_000))
        }
        val clean = TtsPcmSanitizer.sanitizeInPlace(audio.samples, checkActive)
        val trimmed = TtsEdgeSilenceTrimmer.trim(clean, sampleRate, checkActive)
        checkActive()
        return TtsAudio(trimmed, sampleRate)
    }

    override fun close() = tts.release()
}

enum class SystemTtsPlaybackMode {
    GENERATED,
    INSTANT;

    companion object {
        fun fromPreference(value: String?): SystemTtsPlaybackMode =
            if (value.equals("instant", ignoreCase = true)) INSTANT else GENERATED
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

    private data class TimelineCallbacks(
        val onStarted: () -> Unit,
        val onCompleted: (String?) -> Unit,
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
        val tts = TextToSpeech(appCtx) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        ttsRef.set(tts)
        fun disposeFailedEngine() {
            ttsRef.compareAndSet(tts, null)
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        val initialized = try {
            ready.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            disposeFailedEngine()
            Thread.currentThread().interrupt()
            throw IllegalStateException("等待手机系统 TTS 初始化时被中断", interrupted)
        }
        if (!initialized) {
            disposeFailedEngine()
            throw IllegalStateException("手机系统 TTS 初始化超时（15 秒），请检查默认文字转语音服务")
        }
        if (!ok) {
            disposeFailedEngine()
            throw IllegalStateException("手机系统文字转语音服务启动失败，请检查系统 TTS 引擎")
        }

        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            // This mode must remain the selected system TTS engine. Do not silently switch
            // to another locale or another engine when Chinese voice data is missing.
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
            disposeFailedEngine()
            throw IllegalStateException("系统 TTS 没有可用的中文音色")
        }
        val catalogue = (listOf(defaultVoice) + availableChineseVoices)
            .distinctBy { it.name }
        if (tts.voice?.name != defaultVoice.name) {
            val voiceResult = tts.setVoice(defaultVoice)
            if (voiceResult != TextToSpeech.SUCCESS) {
                disposeFailedEngine()
                throw IllegalStateException("系统 TTS 无法切换到可用中文音色：${defaultVoice.name}")
            }
        }
        defaultChineseVoice = defaultVoice
        chineseVoiceCatalog = catalogue
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
                val callbacks = utteranceId?.let { timelineCallbacks[it] } ?: return
                runCatching { callbacks.onStarted() }
                    .onFailure { android.util.Log.w("SystemTts", "Instant TTS start callback failed", it) }
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

    private fun finishTimelineUtterance(utteranceId: String?, error: String?): Boolean {
        if (utteranceId.isNullOrBlank()) return false
        val callbacks = timelineCallbacks.remove(utteranceId) ?: return false
        synchronized(lifecycleLock) {
            if (activeTimelineUtteranceId == utteranceId) activeTimelineUtteranceId = null
        }
        runCatching { callbacks.onCompleted(error) }
            .onFailure { android.util.Log.w("SystemTts", "Instant TTS completion callback failed", it) }
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
        return try {
            TtsTextPolicy.validate(text)
            val normalizedText = text.replace(Regex("\\s+"), " ").trim()
            if (normalizedText.isEmpty()) return false
            val engine = synchronized(lifecycleLock) {
                if (closed || !ok) return false
                ttsRef.get() ?: return false
            }
            val voices = chineseVoiceCatalog
            val voiceIndex = SystemTtsSpeakerVoicePolicy.indexForSpeaker(speakerId, voices.size)
            val targetVoice = voices.getOrNull(voiceIndex) ?: defaultChineseVoice
                ?: return false
            if (engine.voice?.name != targetVoice.name && engine.setVoice(targetVoice) != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 无法切换到所选中文音色：${targetVoice.name}")
            }
            val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
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
                if (closed || ttsRef.get() !== engine) return false
                activeTimelineUtteranceId = utteranceId
                timelineCallbacks[utteranceId] = TimelineCallbacks(onStarted, onCompleted)
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
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("SystemTts", "Instant TTS request failed", error)
            runCatching { onCompleted(error.message ?: error.javaClass.simpleName) }
            false
        }
    }

    override fun stopTimelineSpeech(utteranceId: String?) {
        val engine = synchronized(lifecycleLock) {
            val active = activeTimelineUtteranceId
            if (active == null || (utteranceId != null && utteranceId != active)) {
                null
            } else {
                timelineCallbacks.remove(active)
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
            if (tts.voice?.name != targetVoice.name) {
                val voiceResult = tts.setVoice(targetVoice)
                if (voiceResult != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("系统 TTS 无法切换到人物音色：${targetVoice.name}")
                }
            }
            val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
            if (tts.setSpeechRate(safeSpeed) != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 无法设置配音语速（" + safeSpeed + "×）")
            }
            val result = tts.synthesizeToFile(normalizedText, android.os.Bundle(), tmp, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                android.util.Log.e(
                    "SystemTts",
                    "synthesizeToFile rejected request: result=$result chars=${normalizedText.length} voice=${targetVoice.name}",
                )
                throw IllegalStateException("系统 TTS 拒绝了本次合成（状态码 $result）")
            }
            val timeoutMs = SystemTtsTimeoutPolicy.timeoutMs(normalizedText, safeSpeed)
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
                throw IllegalStateException(failure)
            }
            val checkTtsActive = {
                checkActive()
                if (closed) throw java.util.concurrent.CancellationException("系统 TTS 已关闭，本次合成已取消")
            }
            val audio = SystemTtsWavDecoder.decode(tmp, checkTtsActive)
            checkTtsActive()
            if (audio.samples.isEmpty()) {
                throw IllegalStateException("系统 TTS 已返回，但生成的 WAV 没有可播放音频")
            }
            val normalized = SystemTtsOutputAudioPolicy.prepareForPlayback(
                audio,
                checkActive = checkTtsActive,
            )
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
            android.util.Log.e(
                "SystemTts",
                "WAV synthesis failed: chars=${normalizedText.length} detail=${error.message ?: error.javaClass.simpleName}",
                error,
            )
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
