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
    fun close()
}

/** Kokoro v1.1 int8 本地神经 TTS（中文主力） */
class KokoroTts(private val ctx: Context, private val speakerId: Int = KokoroVoiceRange.DEFAULT_SPEAKER_ID, private val numThreads: Int = 2) : TtsEngine {
    override val name = "Kokoro"

    // Model assets can be refreshed independently of the app version. Hash every file's
    // metadata once (including nested eSpeak and dictionaries), then reuse only the compact digest
    // while walking subtitle rows. This keeps cache-key construction cheap on older phones.
    private val modelAssetsFingerprint: String by lazy {
        val dir = io.translatetube.local.data.ModelFiles.ttsDir(ctx)
        if (!dir.isDirectory) {
            "missing-kokoro-assets"
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .forEach { file ->
                    val record = "${file.relativeTo(dir).invariantSeparatorsPath}:${file.length()}:${file.lastModified()}\n"
                    digest.update(record.toByteArray(Charsets.UTF_8))
                }
            digest.digest().take(16)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    override val cacheKey: String
        get() = "$name-model-v2-${modelAssetsFingerprint}-default-${KokoroVoiceRange.clampSpeakerId(speakerId)}"

    private val tts: OfflineTts

    init {
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

    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        val normalizedText = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedText.isEmpty()) return TtsAudio(FloatArray(0), 24000)
        val selectedSpeaker = KokoroVoiceRange.clampSpeakerId(if (speakerId >= 0) speakerId else this.speakerId)
        val safeSpeed = TtsSpeechRatePolicy.normalize(speed)
        val audio = tts.generate(normalizedText, selectedSpeaker, safeSpeed)
        val sampleRate = audio.sampleRate
        if (sampleRate !in 8_000..192_000 || audio.samples.isEmpty()) {
            android.util.Log.w("KokoroTts", "Invalid generated audio: rate=$sampleRate samples=${audio.samples.size}")
            return TtsAudio(FloatArray(0), sampleRate.coerceIn(8_000, 192_000))
        }
        // Discard NaN/Infinity and prevent invalid floats from reaching AudioTrack.
        val clean = FloatArray(audio.samples.size) { i ->
            val value = audio.samples[i]
            if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        }
        return TtsAudio(clean, sampleRate)
    }

    override fun close() = tts.release()
}

/** 系统 TTS：等待合成回调完成后再读取 WAV，避免读到尚未写完的文件。 */
class SystemTts(context: Context) : TtsEngine {
    private val appCtx = context.applicationContext
    override val name = "系统TTS"
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    @Volatile private var defaultChineseVoice: android.speech.tts.Voice? = null
    @Volatile private var chineseVoiceCatalog: List<android.speech.tts.Voice> = emptyList()

    val chineseVoiceCount: Int get() = chineseVoiceCatalog.size
    val supportsMultipleChineseVoices: Boolean get() = chineseVoiceCount > 1
    val chineseVoiceNames: List<String> get() = chineseVoiceCatalog.map { it.name }

    // Keep this identity stable while synth() switches per-speaker voices. The selected default
    // voice and complete Chinese voice catalogue make PCM cache entries invalid when capabilities change.
    override val cacheKey: String
        get() {
            val engine = ttsRef.get()
            return listOf(
                "system-voice-v2",
                engine?.defaultEngine.orEmpty(),
                defaultChineseVoice?.name.orEmpty(),
                chineseVoiceCatalog.joinToString(",") { voice ->
                    "${voice.name}@${voice.locale}" +
                        ":network=${voice.isNetworkConnectionRequired}" +
                        ":quality=${voice.quality}:latency=${voice.latency}"
                },
            ).joinToString("|")
        }
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var closed = false
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var activeLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var activeError = false

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

        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) activeLatch?.countDown()
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    activeError = true
                    activeLatch?.countDown()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == activeUtteranceId) {
                    activeError = true
                    activeLatch?.countDown()
                }
            }
        })
    }

    @Synchronized
    override fun synth(text: String, speed: Float, speakerId: Int): TtsAudio {
        if (closed) throw IllegalStateException("系统 TTS 实例已经关闭")
        if (text.isBlank()) return TtsAudio(FloatArray(0), 16000)
        val tts = ttsRef.get()
            ?: throw IllegalStateException("系统 TTS 实例已经关闭")
        if (!ok) throw IllegalStateException("系统 TTS 尚未初始化成功")

        val tmp = java.io.File.createTempFile("tts_", ".wav", contextCacheDir())
        val utteranceId = "system-tts-" + System.nanoTime()
        val completed = java.util.concurrent.CountDownLatch(1)
        activeUtteranceId = utteranceId
        activeLatch = completed
        activeError = false
        try {
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
            if (!completed.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { tts.stop() }
                throw IllegalStateException("系统 TTS 合成超时")
            }
            // close() also releases this latch; never decode a file after that early wake-up.
            if (closed) throw IllegalStateException("系统 TTS 已关闭，本次合成已取消")
            if (activeError) {
                throw IllegalStateException("系统 TTS 合成回调返回错误")
            }
            val audio = readWav(tmp)
            if (audio.samples.isEmpty()) {
                throw IllegalStateException("系统 TTS 已返回，但生成的 WAV 没有可播放音频")
            }
            return audio
        } finally {
            activeUtteranceId = null
            activeLatch = null
            tmp.delete()
        }
    }

    private fun contextCacheDir(): java.io.File =
        java.io.File(appCtx.cacheDir, "tts_tmp").apply { mkdirs() }

    private fun readWav(file: java.io.File): TtsAudio = SystemTtsWavDecoder.decode(file)

    override fun close() {
        // Set this before waking synth(): a released waiter must not read the temp WAV.
        closed = true
        activeLatch?.countDown()
        val engine = ttsRef.getAndSet(null)
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
