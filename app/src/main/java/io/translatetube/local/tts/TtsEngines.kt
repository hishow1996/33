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
 * Android's own live-speech route for subtitle-timed playback. This avoids relying on an OEM
 * AudioTrack accepting PCM written by synthesizeToFile on older Android devices.
 */
interface TimelineTtsPlayback {
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
class KokoroTts(private val ctx: Context, private val speakerId: Int = 14, private val numThreads: Int = 2) : TtsEngine {
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

/** 系统 TTS：等待合成回调完成后再读取 WAV，避免读到尚未写完的文件。 */
class SystemTts(context: Context) : TtsEngine, TimelineTtsPlayback {
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    @Volatile private var defaultChineseVoice: android.speech.tts.Voice? = null
    @Volatile private var chineseVoiceCatalog: List<android.speech.tts.Voice> = emptyList()
    @Volatile private var liveSpeechAttributesReady: Boolean = false

    val chineseVoiceCount: Int get() = chineseVoiceCatalog.size
    val supportsMultipleChineseVoices: Boolean get() = chineseVoiceCount > 1

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
    /**
     * Makes utterance-id validation, latch publication, callbacks and close atomic with respect
     * to one another. Utterance callbacks may arrive on different threads.
     */
    private val lifecycleLock = Any()
    private data class TimelineCallbacks(
        val onStarted: () -> Unit,
        val onCompleted: (String?) -> Unit,
    )
    private val timelineCallbacks = HashMap<String, TimelineCallbacks>()
    @Volatile private var activeTimelineUtteranceId: String? = null
    private val timelineCommandGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val timelineSpeechExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "SystemTtsTimeline").apply { isDaemon = true }
        }

    private fun isCurrentTimelineRequest(
        utteranceId: String,
        engine: TextToSpeech,
        generation: Long,
    ): Boolean = synchronized(lifecycleLock) {
        !closed &&
            ok &&
            ttsRef.get() === engine &&
            activeTimelineUtteranceId == utteranceId &&
            timelineCommandGeneration.get() == generation &&
            timelineCallbacks.containsKey(utteranceId)
    }

    init {
        val tts = TextToSpeech(appCtx) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        ttsRef.set(tts)
        if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS) || !ok) {
            runCatching { tts.shutdown() }
            throw IllegalStateException("系统 TTS 初始化失败")
        }

        // Subtitle dubbing is part of the video mix, not TalkBack/accessibility narration.
        // On Android 9/OEM ROMs, ACCESSIBILITY usage can follow a separate accessibility volume
        // slider and appear silent even while media volume is audible. Use the same media route as
        // the video, and also pass STREAM_MUSIC per utterance as an OEM compatibility hint below.
        val liveSpeechAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        liveSpeechAttributesReady =
            tts.setAudioAttributes(liveSpeechAttributes) == TextToSpeech.SUCCESS
        if (!liveSpeechAttributesReady) {
            android.util.Log.w("SystemTts", "System TTS rejected speech audio attributes; using STREAM_MUSIC parameters")
        }

        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            // This mode must remain the selected system TTS engine. Do not silently switch
            // to another locale or another engine when Chinese voice data is missing.
            runCatching { tts.shutdown() }
            throw IllegalStateException("系统 TTS 未安装或不支持中文语音数据")
        }

        val allChineseVoices = tts.voices.orEmpty()
            .filter { it.locale.language.equals("zh", ignoreCase = true) }
            .distinctBy { it.name }
            .sortedBy { it.name }
        val availableChineseVoices = SystemTtsSpeakerVoicePolicy
            .preferOfflineVoices(allChineseVoices) { it.isNetworkConnectionRequired }
        val currentChineseVoice = tts.voice?.takeIf {
            it.locale.language.equals("zh", ignoreCase = true)
        }
        // Prefer a listed offline voice, but preserve the currently selected Chinese voice on
        // engines whose voices catalogue is empty or incomplete (common on some older ROMs).
        val defaultVoice = SystemTtsSpeakerVoicePolicy.chooseDefaultVoice(
            currentChineseVoice,
            availableChineseVoices,
        ) { it.name }
        if (defaultVoice == null) {
            runCatching { tts.shutdown() }
            throw IllegalStateException("系统 TTS 没有可用的中文音色")
        }
        val catalogue = (listOf(defaultVoice) + availableChineseVoices)
            .distinctBy { it.name }
        if (tts.voice?.name != defaultVoice.name) {
            val voiceResult = tts.setVoice(defaultVoice)
            if (voiceResult != TextToSpeech.SUCCESS) {
                runCatching { tts.shutdown() }
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
                if (utteranceId == null) return
                android.util.Log.d("SystemTts", "Live subtitle utterance started: $utteranceId")
                val callback = synchronized(lifecycleLock) {
                    if (utteranceId == activeTimelineUtteranceId) {
                        timelineCallbacks[utteranceId]?.onStarted
                    } else {
                        null
                    }
                }
                runCatching { callback?.invoke() }
            }

            override fun onDone(utteranceId: String?) {
                android.util.Log.d("SystemTts", "Live subtitle utterance completed: $utteranceId")
                synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) activeLatch?.countDown()
                }
                finishTimelineUtterance(utteranceId, null)
            }

            override fun onError(utteranceId: String?) {
                android.util.Log.e("SystemTts", "Live subtitle utterance error: $utteranceId")
                synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = "系统 TTS 合成回调返回错误"
                        activeLatch?.countDown()
                    }
                }
                finishTimelineUtterance(utteranceId, "系统 TTS 朗读回调返回错误")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                android.util.Log.e("SystemTts", "Live subtitle utterance error: $utteranceId, code=$errorCode")
                synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = "系统 TTS 合成失败（错误码 $errorCode）"
                        activeLatch?.countDown()
                    }
                }
                finishTimelineUtterance(utteranceId, "系统 TTS 朗读失败（错误码 $errorCode）")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                synchronized(lifecycleLock) {
                    if (utteranceId == activeUtteranceId) {
                        activeFailure = if (interrupted) {
                            "系统 TTS 合成过程中被中断"
                        } else {
                            "系统 TTS 合成在开始前被取消或从队列中移除"
                        }
                        activeLatch?.countDown()
                    }
                }
                finishTimelineUtterance(
                    utteranceId,
                    if (interrupted) "系统 TTS 朗读被中断" else "系统 TTS 朗读被取消",
                )
            }
        })
    }

    private fun finishTimelineUtterance(utteranceId: String?, error: String?) {
        if (utteranceId.isNullOrBlank()) return
        val callback = synchronized(lifecycleLock) {
            val entry = timelineCallbacks.remove(utteranceId)
            if (activeTimelineUtteranceId == utteranceId) activeTimelineUtteranceId = null
            entry?.onCompleted
        }
        runCatching { callback?.invoke(error) }
    }

    override fun speakOnTimeline(
        text: String,
        speed: Float,
        speakerId: Int,
        volume: Float,
        utteranceId: String,
        onStarted: () -> Unit,
        onCompleted: (String?) -> Unit,
    ): Boolean {
        if (text.isBlank()) {
            onCompleted("字幕译文为空")
            return false
        }
        val engine = synchronized(lifecycleLock) {
            if (closed || !ok) null else ttsRef.get()
        } ?: run {
            onCompleted("手机系统 TTS 尚未就绪")
            return false
        }

        val spokenText = text.replace(Regex("""\s+"""), " ").trim()
        try {
            TtsTextPolicy.validate(spokenText)
        } catch (error: Exception) {
            onCompleted(error.message ?: "字幕文本无法朗读")
            return false
        }
        val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
        val normalizedVolume = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f

        // Publish the newest request before dispatch. Rapid seeks/subtitle changes invalidate
        // earlier queued work, so a stale sentence can never start after a newer one.
        val generation = synchronized(lifecycleLock) {
            if (closed || !ok || ttsRef.get() !== engine) {
                null
            } else {
                activeTimelineUtteranceId?.let(timelineCallbacks::remove)
                timelineCallbacks[utteranceId] = TimelineCallbacks(onStarted, onCompleted)
                activeTimelineUtteranceId = utteranceId
                timelineCommandGeneration.incrementAndGet()
            }
        } ?: run {
            onCompleted("手机系统 TTS 已关闭或尚未就绪")
            return false
        }

        return try {
            timelineSpeechExecutor.execute {
                try {
                    // TextToSpeech is a Binder service. Do voice/rate/speak calls on a dedicated
                    // serial worker rather than the player's 100 ms main-thread timeline ticker.
                    // The same monitor serializes live speech against synthesizeToFile().
                    synchronized(this@SystemTts) {
                        if (isCurrentTimelineRequest(utteranceId, engine, generation)) {
                            val voices = chineseVoiceCatalog
                            val voiceIndex = SystemTtsSpeakerVoicePolicy.indexForSpeaker(speakerId, voices.size)
                            val targetVoice = voices.getOrNull(voiceIndex) ?: defaultChineseVoice
                                ?: throw IllegalStateException("系统 TTS 没有可用的中文音色")
                            if (engine.voice?.name != targetVoice.name) {
                                val voiceResult = engine.setVoice(targetVoice)
                                if (voiceResult != TextToSpeech.SUCCESS) {
                                    throw IllegalStateException("系统 TTS 无法切换到中文音色：${targetVoice.name}")
                                }
                            }
                            if (engine.setSpeechRate(safeSpeed) != TextToSpeech.SUCCESS) {
                                throw IllegalStateException("系统 TTS 无法设置朗读语速")
                            }

                            val params = android.os.Bundle().apply {
                                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, normalizedVolume)
                                // Keep the legacy stream hint even when setAudioAttributes succeeds.
                                // Some Android 9/OEM TTS services ignore the attributes set on init.
                                putInt(
                                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                                    android.media.AudioManager.STREAM_MUSIC,
                                )
                            }
                            val result = synchronized(lifecycleLock) {
                                if (
                                    closed ||
                                    ttsRef.get() !== engine ||
                                    activeTimelineUtteranceId != utteranceId ||
                                    timelineCommandGeneration.get() != generation ||
                                    !timelineCallbacks.containsKey(utteranceId)
                                ) {
                                    null
                                } else {
                                    engine.speak(
                                        spokenText,
                                        TextToSpeech.QUEUE_FLUSH,
                                        params,
                                        utteranceId,
                                    )
                                }
                            }
                            if (result != null) {
                                android.util.Log.d(
                                    "SystemTts",
                                    "Live subtitle request: id=$utteranceId chars=${spokenText.length} " +
                                        "voice=${targetVoice.name} speed=$safeSpeed volume=$normalizedVolume " +
                                        "attributes=$liveSpeechAttributesReady result=$result",
                                )
                                if (result != TextToSpeech.SUCCESS) {
                                    throw IllegalStateException("手机系统 TTS 拒绝朗读本句（状态码 $result）")
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    val stillCurrent = synchronized(lifecycleLock) {
                        !closed &&
                            activeTimelineUtteranceId == utteranceId &&
                            timelineCommandGeneration.get() == generation &&
                            timelineCallbacks.containsKey(utteranceId)
                    }
                    if (stillCurrent) {
                        android.util.Log.e("SystemTts", "Unable to start live subtitle utterance $utteranceId", error)
                        finishTimelineUtterance(
                            utteranceId,
                            error.message ?: error.javaClass.simpleName,
                        )
                    } else {
                        android.util.Log.d("SystemTts", "Skipped stale subtitle utterance $utteranceId")
                    }
                }
            }
            true
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            finishTimelineUtterance(utteranceId, "系统 TTS 朗读队列已关闭")
            false
        }
    }

    override fun stopTimelineSpeech(utteranceId: String?) {
        val stopRequest = synchronized(lifecycleLock) {
            val activeId = activeTimelineUtteranceId
            if (utteranceId != null && activeId != utteranceId) {
                return
            }
            if (utteranceId == null) {
                timelineCallbacks.clear()
            } else {
                timelineCallbacks.remove(utteranceId)
            }
            activeTimelineUtteranceId = null
            val generation = timelineCommandGeneration.incrementAndGet()
            val engine = if (closed) null else ttsRef.get()
            if (engine == null) null else engine to generation
        } ?: return

        val (engine, generation) = stopRequest
        try {
            timelineSpeechExecutor.execute {
                // A seek/resume may enqueue a new line before this stop command runs. Let that
                // newer QUEUE_FLUSH request replace the previous speech instead of stopping it.
                val shouldStop = synchronized(lifecycleLock) {
                    !closed &&
                        ttsRef.get() === engine &&
                        activeTimelineUtteranceId == null &&
                        timelineCommandGeneration.get() == generation
                }
                if (shouldStop) {
                    synchronized(this@SystemTts) {
                        runCatching { engine.stop() }
                            .onFailure {
                                android.util.Log.w("SystemTts", "Could not stop live subtitle speech", it)
                            }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { engine.stop() }
        }
    }

    @Synchronized
    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio =
        synthInternal(text, speed, speakerId) {}

    @Synchronized
    override fun synthCancellable(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio =
        synthInternal(text, speed, speakerId, checkActive)

    private fun synthInternal(text: String, speed: Float, speakerId: Int, checkActive: () -> Unit): TtsAudio {
        checkActive()
        if (text.isBlank()) return TtsAudio(FloatArray(0), 16000)
        TtsTextPolicy.validate(text)

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
            val result = tts.synthesizeToFile(text, android.os.Bundle(), tmp, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                throw IllegalStateException("系统 TTS 拒绝了本次合成（状态码 $result）")
            }
            val timeoutMs = SystemTtsTimeoutPolicy.timeoutMs(text, safeSpeed)
            val timeoutDeadlineNanos = System.nanoTime() +
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                var completedInTime = false
                while (!completedInTime) {
                    checkActive()
                    val remainingNanos = timeoutDeadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        runCatching { tts.stop() }
                        throw IllegalStateException("系统 TTS 合成超时")
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
            android.util.Log.d(
                "SystemTts",
                "PCM output: input=${audio.sampleRate}Hz/${audio.samples.size} samples, " +
                    "output=${normalized.sampleRate}Hz/${normalized.samples.size} samples",
            )
            return normalized
        } finally {
            synchronized(lifecycleLock) {
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                    activeLatch = null
                    activeFailure = null
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
        timelineCommandGeneration.incrementAndGet()
        timelineSpeechExecutor.shutdownNow()
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
