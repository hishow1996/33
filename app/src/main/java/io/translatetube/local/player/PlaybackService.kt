package io.translatetube.local.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.translatetube.local.R
import io.translatetube.local.util.AudioDownloadProgressFormatter
import io.translatetube.local.asr.AudioExtract
import io.translatetube.local.asr.AudioTrackSelectionPolicy
import io.translatetube.local.asr.LocalAsrEngine
import io.translatetube.local.asr.LocalAsrPerformancePolicy
import io.translatetube.local.asr.SenseVoiceEngine
import io.translatetube.local.asr.SpeakerDiarizer
import io.translatetube.local.asr.SpeechOnsetDetector
import io.translatetube.local.asr.WhisperEngine
import io.translatetube.local.asr.vadSegments
import io.translatetube.local.asr.vadSegmentsFromFile
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.SourceType
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.data.VideoHistoryCacheIndex
import io.translatetube.local.subs.SubLine
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubtitleFileDecoder
import io.translatetube.local.translate.Crypto
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.LangMap
import io.translatetube.local.translate.SourceLanguagePolicy
import io.translatetube.local.translate.MlKitEngine
import io.translatetube.local.translate.OpusMtEngine
import io.translatetube.local.translate.Small100Engine
import io.translatetube.local.translate.SinglePairTranslationEngine
import io.translatetube.local.translate.GoogleHybridEngine
import io.translatetube.local.translate.TranslationEngine
import io.translatetube.local.tts.KokoroTts
import io.translatetube.local.tts.TtsEngine
import io.translatetube.local.yt.NewPipeRepo
import io.translatetube.local.yt.SubtitleTrackOption
import io.translatetube.local.yt.YouTubeAudioCandidate
import io.translatetube.local.yt.YouTubeStreamSelector
import io.translatetube.local.yt.YouTubeVideoCandidate
import io.translatetube.local.yt.BoundedTextPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest

enum class Phase { IDLE, PARSING, DOWNLOADING_AUDIO, TRANSCRIBING, TRANSLATING, SYNTHESIZING, READY, ERROR }

/**
 * Persists only a one-way configuration fingerprint per history item. It lets history routing
 * distinguish unchanged settings after the in-memory playback service has been recreated, without
 * persisting API secrets or subtitle text in SharedPreferences.
 */
private enum class EngineConfigDomain {
    TRANSCRIPTION,
    TRANSLATION,
    DUBBING,
}

private data class EngineConfigFingerprints(
    val transcription: String,
    val translation: String,
    val dubbing: String,
)

private enum class EngineRestartStage {
    TRANSCRIPTION,
    TRANSLATION,
    DUBBING,
}

private object ReadyHistorySessionConfigStore {
    private const val PREFS_NAME = "ready_history_session_config"
    private const val KEY_PREFIX = "video_"

    fun isRelevantPreferenceKey(key: String): Boolean =
        key == "active_api_id" ||
            // ASR provider/model choices, including local and cloud implementations.
            key.startsWith("asr_") ||
            key.startsWith("local_asr_") ||
            key.startsWith("cloud_asr_") ||
            key.startsWith("online_asr_") ||
            key.startsWith("whisper_") ||
            key.startsWith("sensevoice_") ||
            key.contains("transcription", ignoreCase = true) ||
            key.contains("transcribe", ignoreCase = true) ||
            key.contains("speech_recognition", ignoreCase = true) ||
            // Online API profiles and built-in/local translation model choices.
            key.startsWith("free_gemini_") ||
            key.startsWith("free_deepseek_") ||
            key.startsWith("mlkit_") ||
            key.startsWith("opus_") ||
            key.startsWith("small100_") ||
            key.startsWith("single_pair_") ||
            key.startsWith("local_translation_") ||
            key.startsWith("online_translation_") ||
            key.startsWith("cloud_translation_") ||
            key.startsWith("local_translate_") ||
            key.startsWith("online_translate_") ||
            key.startsWith("cloud_translate_") ||
            key.contains("translate", ignoreCase = true) ||
            key.contains("translation", ignoreCase = true) ||
            // Voice engine, provider, model and voice-selection settings.
            key.startsWith("tts_") ||
            key.startsWith("edge_tts_") ||
            key.startsWith("web_tts_") ||
            key.startsWith("online_tts_") ||
            key.startsWith("cloud_tts_") ||
            key.startsWith("local_tts_") ||
            key.startsWith("piper_") ||
            key.startsWith("kokoro_") ||
            key.startsWith("system_tts_")

    fun fingerprint(context: Context, ignoreTranscription: Boolean = false): String {
        val relevant = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.entries
            .filter { entry ->
                isRelevantPreferenceKey(entry.key) &&
                    !(ignoreTranscription && belongsToDomain(entry.key, EngineConfigDomain.TRANSCRIPTION))
            }
            .sortedBy { it.key }
            .joinToString("\n") { entry ->
                val value = when (val raw = entry.value) {
                    is Set<*> -> raw.map { it?.toString().orEmpty() }.sorted().joinToString(",")
                    else -> raw?.toString().orEmpty()
                }
                "${entry.key}=$value"
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(relevant.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun fingerprints(context: Context): EngineConfigFingerprints = EngineConfigFingerprints(
        transcription = domainFingerprint(context, EngineConfigDomain.TRANSCRIPTION),
        translation = domainFingerprint(context, EngineConfigDomain.TRANSLATION),
        dubbing = domainFingerprint(context, EngineConfigDomain.DUBBING),
    )

    /**
     * Detect whether the selected engines differ from the last successful run for this video.
     * A changed configuration must synthesize fresh Chinese speech even if a matching subtitle
     * snapshot for this configuration is already available.
     */
    fun hasEngineConfigChangedSinceLastSuccess(context: Context, videoKey: String): Boolean {
        val previous = savedFingerprints(context, videoKey) ?: return false
        val current = fingerprints(context)
        return previous.transcription != current.transcription ||
            previous.translation != current.translation ||
            previous.dubbing != current.dubbing
    }

    fun savedProcessingFlow(context: Context, videoKey: String): ProcessingFlow? {
        if (videoKey.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        val saved = prefs.getString(key + "-processing-flow", null)
        if (!saved.isNullOrBlank()) {
            return runCatching { ProcessingFlow.valueOf(saved) }.getOrNull()
        }
        // Older versions kept the same distinction as a boolean: only true means YouTube captions were used.
        if (!videoKey.startsWith("youtube:", ignoreCase = true)) return null
        return if (prefs.getBoolean("$key-ignore-transcription", false)) {
            ProcessingFlow.YOUTUBE_SUBTITLES
        } else {
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION
        }
    }

    fun savedCompositeFingerprint(context: Context, videoKey: String): String? {
        if (videoKey.isBlank()) return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(storageKey(videoKey), null)
    }

    fun savedFingerprints(context: Context, videoKey: String): EngineConfigFingerprints? {
        if (videoKey.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        val transcription = prefs.getString(key + "-transcription-fingerprint", null) ?: return null
        val translation = prefs.getString(key + "-translation-fingerprint", null) ?: return null
        val dubbing = prefs.getString(key + "-dubbing-fingerprint", null) ?: return null
        if (transcription.isBlank() || translation.isBlank() || dubbing.isBlank()) return null
        return EngineConfigFingerprints(transcription, translation, dubbing)
    }

    private fun domainFingerprint(context: Context, domain: EngineConfigDomain): String {
        val entries = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.entries
            .filter { belongsToDomain(it.key, domain) }
            .sortedBy { it.key }
            .joinToString("\n") { entry ->
                val value = when (val raw = entry.value) {
                    is Set<*> -> raw.map { it?.toString().orEmpty() }.sorted().joinToString(",")
                    else -> raw?.toString().orEmpty()
                }
                "${entry.key}=$value"
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(entries.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun belongsToDomain(key: String, domain: EngineConfigDomain): Boolean = when (domain) {
        EngineConfigDomain.TRANSCRIPTION ->
            key.startsWith("asr_") ||
                key.startsWith("local_asr_") ||
                key.startsWith("cloud_asr_") ||
                key.startsWith("online_asr_") ||
                key.startsWith("whisper_") ||
                key.startsWith("sensevoice_") ||
                key.contains("transcription", ignoreCase = true) ||
                key.contains("transcribe", ignoreCase = true) ||
                key.contains("speech_recognition", ignoreCase = true)
        EngineConfigDomain.TRANSLATION ->
            key == "active_api_id" ||
                key.startsWith("free_gemini_") ||
                key.startsWith("free_deepseek_") ||
                // Local model identity and manual/automatic language-mode options affect output.
                key.startsWith("mlkit_") ||
                key.startsWith("opus_") ||
                key.startsWith("small100_") ||
                key.startsWith("single_pair_") ||
                key.startsWith("local_translation_") ||
                key.startsWith("online_translation_") ||
                key.startsWith("cloud_translation_") ||
                key.startsWith("local_translate_") ||
                key.startsWith("online_translate_") ||
                key.startsWith("cloud_translate_") ||
                key.contains("translate", ignoreCase = true) ||
                key.contains("translation", ignoreCase = true)
        EngineConfigDomain.DUBBING ->
            key.startsWith("tts_") ||
                key.startsWith("edge_tts_") ||
                key.startsWith("web_tts_") ||
                key.startsWith("online_tts_") ||
                key.startsWith("cloud_tts_") ||
                key.startsWith("local_tts_") ||
                key.startsWith("piper_") ||
                key.startsWith("kokoro_") ||
                key.startsWith("system_tts_")
    }
    fun save(
        context: Context,
        videoKey: String,
        fingerprint: String,
        ignoreTranscription: Boolean,
        fingerprints: EngineConfigFingerprints,
        processingFlow: ProcessingFlow,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        prefs.edit()
            .putString(key, fingerprint)
            .putBoolean("$key-ignore-transcription", ignoreTranscription)
            .putString(key + "-transcription-fingerprint", fingerprints.transcription)
            .putString(key + "-translation-fingerprint", fingerprints.translation)
            .putString(key + "-dubbing-fingerprint", fingerprints.dubbing)
            .putString(key + "-processing-flow", processingFlow.name)
            .apply()
    }

    fun matches(context: Context, videoKey: String, isYouTube: Boolean): Boolean {
        if (videoKey.isBlank()) return false
        if (ReadySessionSnapshotStore.findForCurrentConfig(context, videoKey) { ignoreTranscription ->
                fingerprint(context, ignoreTranscription = ignoreTranscription)
            } != null
        ) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        val saved = prefs.getString(key, null) ?: return false
        // Only a YouTube run that actually used YouTube captions may ignore ASR settings.
        // A YouTube video without captions uses ASR, so its chosen transcription engine matters.
        val ignoreTranscription = prefs.getBoolean("$key-ignore-transcription", false)
        if (ignoreTranscription && !isYouTube) return false
        return saved.isNotBlank() && saved == fingerprint(context, ignoreTranscription)
    }

    fun remove(context: Context, videoKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        prefs.edit()
            .remove(key)
            .remove("$key-ignore-transcription")
            .remove(key + "-transcription-fingerprint")
            .remove(key + "-translation-fingerprint")
            .remove(key + "-dubbing-fingerprint")
            .remove(key + "-processing-flow")
            .apply()
        ReadySessionSnapshotStore.remove(context, videoKey)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        ReadySessionSnapshotStore.clearAll(context)
    }

    private fun storageKey(videoKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoKey.toByteArray(Charsets.UTF_8))
        return KEY_PREFIX + digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private data class PartialAsrTranscript(
    val processedSegments: Int,
    val rows: List<Triple<Long, Long, String>>,
    val sourceLang: String,
)

private class TranslationFailureException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)


data class PlayerUiState(
    val phase: Phase = Phase.IDLE,
    val progress: String = "",
    val key: String = "",
    val title: String = "",
    val channel: String = "",
    val thumbUrl: String? = null,
    val durationMs: Long = 0,
    val lines: List<SubLine> = emptyList(),
    /** Number of rows resolved for the active subtitle-translation pass; avoids rescanning the full list for progress. */
    val translatedLineCount: Int = 0,
    /** Subtitle index whose AudioTrack has actually started; separate from the video playhead highlight. */
    val activeDubbingLine: Int = -1,
    val currentLine: Int = -1,
    /**
     * 当前配音实际念的整块文本（相邻两句合并成一块一口气合成时，念的是两句）。非空时字幕条显示它，
     * 保证"听到的"和"看到的"是同一段文字；为空时显示 [currentLine] 那一行自己的译文。
     */
    val captionOverride: String? = null,
    val preparedUntil: Int = 0,
    val preparedCount: Int = 0,
    val totalLines: Int = 0,
    val dubbingOn: Boolean = true,
    val muteOriginal: Boolean = true,
    /** User-selected source and Chinese dubbing gains, independently adjustable from 0 to 1. */
    val originalVolume: Float = 1f,
    val dubbingVolume: Float = 1f,
    val audioOnly: Boolean = false,
    val error: String? = null,
    val sourceLang: String = "",
    val engineName: String = "",
    /** Translation cache identity is carried through retries so imported sources keep their fingerprint. */
    val translationCacheKey: String = "",
    val ttsEngineName: String = "",
    val dubbingStatus: String = "",
    val isPlaying: Boolean = false,
    val videoAspect: Float = 16f / 9f,   // 视频真实宽高比（竖屏视频自适应）
    val noModelHint: String? = null,     // 模型/引擎问题的用户提示
    /** Subtitle generation is blocked because the selected ASR engine is not ready. */
    val waitingForTranscriptionEngine: Boolean = false,
    /** Chinese dubbing is blocked because the selected TTS engine is not ready. */
    val waitingForDubbingEngine: Boolean = false,
    /** At least one translated cue failed synthesis/output and has fallen back to the original audio. */
    val dubbingHasErrors: Boolean = false,
    val qualityOptions: List<String> = emptyList(),
    val selectedQuality: String = "",
    val availableSubtitleTracks: List<SubtitleTrackOption> = emptyList(),
    val subtitleTrackStatus: String = "",
    /** The user opted to translate the transcript externally instead of using the configured engine. */
    val manualTranslationModeEnabled: Boolean = false,
    /** The transcription/translation pipeline is waiting for an external translated subtitle file. */
    val manualTranslationWaiting: Boolean = false,
    val isProcessingPaused: Boolean = false,
    val processingFlow: ProcessingFlow = ProcessingFlow.AUTO,
    /** Last active pipeline stage when processing entered ERROR. */
    val failedPhase: Phase? = null,
    /** Last visible step detail captured before an error or cancellation cleared progress. */
    val failedProgress: String = "",
)

private data class YouTubeQualitySource(
    val label: String,
    val height: Int,
    val url: String,
    val muxed: Boolean,
)

private data class YouTubeStreamChoices(
    val sources: List<YouTubeQualitySource>,
    val audioUrl: String,
    val defaultQuality: YouTubeQualitySource,
)

/** 开播前等待第一句配音挂上混流总线的最长时间（视频还没开始播，不影响观看）。 */
private const val STARTUP_MOUNT_WAIT_MS = 800L

/** 开播前挂载首批句子期间冻结渲染头的最长时间（覆盖挂载等待 + 音频管线 flush）。 */
private const val STARTUP_READ_HEAD_FREEZE_MS = 2_000L

/** 开播前 flush 之后，位置不连续回调保留总线的有效期。 */
private const val STARTUP_FLUSH_KEEP_BUS_NANOS = 1_500_000_000L

// Media3's DefaultMediaNotificationProvider uses notification ID 1001 for media controls.
// Processing progress must use a distinct ID so BigTextStyle updates cannot replace MediaStyle.
private const val PROCESSING_NOTIFICATION_ID = 1004
private const val PROCESSING_NOTIFICATION_MIN_INTERVAL_MS = 750L
private const val MAX_NOTIFICATION_AUDIT_EVENTS = 12

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Serialize notification creation and posting on the main looper; StateFlow progress ticks
    // can otherwise race with direct service commands while Android is inflating the same notice.
    private val processingNotificationHandler = Handler(Looper.getMainLooper())
    private val processingNotificationUpdateLock = Any()
    private var processingNotificationUpdateScheduled = false
    private var processingNotificationUpdatePending = false
    @Volatile private var processingNotificationShuttingDown = false
    @Volatile private var lastProcessingNotificationPostedAtMs = 0L
    @Volatile private var processingNotificationPostCount = 0L
    private var lastProcessingNotificationFingerprint: String? = null
    @Volatile private var lastProcessingNotificationAuditSignature: String? = null
    /** Keep processing in the foreground even when Media3 no longer needs a playback notification. */
    @Volatile private var processingForegroundActive = false
    @Volatile private var latestProcessingNotification: Notification? = null
    private val processingWakeLockLock = Any()
    private var processingWakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastProcessingForegroundFailureKey: String? = null
    private val notificationAuditLock = Any()
    private val recentNotificationAuditTrail = java.util.ArrayDeque<String>()
    @Volatile private var mediaNotificationUpdateCount = 0L
    @Volatile private var lastMediaNotificationUpdatedAtMs = 0L
    @Volatile private var lastMediaNotificationAuditSignature: String? = null
    private val processingNotificationUpdateRunnable = Runnable { drainProcessingNotificationUpdate() }
    private lateinit var pm: PlayerManager
    // A seek can trigger transient buffering/state callbacks after the immediate play() call.
    // Retry playback once on ExoPlayer's main looper, but cancel the retry on an explicit pause.
    private val seekResumeHandler = Handler(Looper.getMainLooper())
    private val seekResumeLock = Any()
    private var seekResumeGeneration = 0L
    private var pendingSeekResume: Runnable? = null
    /** Catch-up queue for subtitle starts that pass while an earlier utterance finishes. */
    private val dubbingCatchUpLock = Any()
    private val dubbingCatchUpQueue = LinkedHashSet<Int>()
    @Volatile private var dubbingCatchUpSessionKey = ""
    private var lastDubbingCatchUpPositionMs = -1L
    private val dubbingDrainHandler = Handler(Looper.getMainLooper())
    @Volatile private var dubbingDrainAfterVideoEnd = false
    @Volatile private var dubbingDrainEngine: DubbingEngine? = null
    @Volatile private var dubbingDrainSessionKey = ""
    @Volatile private var dubbingDrainPoll: Runnable? = null
    @Volatile private var playerReady = false
    private var session: MediaSession? = null
    @Volatile private var dubbing: DubbingEngine? = null
    private val retiringDubbingEngines = java.util.concurrent.CopyOnWriteArrayList<DubbingEngine>()
    @Volatile private var localAsrEngine: LocalAsrEngine? = null
    @Volatile private var localAsrEngineModelId: String? = null
    // sherpa-onnx OfflineRecognizer is native state and must never decode two videos concurrently.
    private val localAsrMutex = kotlinx.coroutines.sync.Mutex()

    private fun retireDubbing(engine: DubbingEngine?): DubbingEngine? {
        if (engine == null) return null
        // Register first: releaseAndJoin() is cancellable, but later tasks must still wait for
        // this engine to finish blocking TTS/native work before initializing a replacement.
        retiringDubbingEngines.addIfAbsent(engine)
        if (dubbing === engine) dubbing = null
        engine.release()
        return engine
    }

    private fun retireCurrentDubbing(): DubbingEngine? = retireDubbing(dubbing)

    private suspend fun awaitRetiredDubbingEngines() {
        while (true) {
            val previous = retiringDubbingEngines.firstOrNull() ?: return
            // If this pipeline is canceled while waiting, keep the retired engine registered so
            // the next pipeline cannot initialize another TTS beside an active native call.
            previous.releaseAndJoin()
            retiringDubbingEngines.remove(previous)
        }
    }
    private var prepJob: Job? = null
    private val manualTranslationRequestedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val manualTranslationWaiters =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<List<SubLine>>>()
    private val manualTranslationSources = java.util.concurrent.ConcurrentHashMap<String, List<SubLine>>()
    private val processingPauseGate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var processingPaused = false
    @Volatile private var videoKey = ""
    private var videoId: String? = null
    private var localUri: Uri? = null
    private var remoteMediaUrl: String? = null
    private var remoteMediaTitle: String = ""
    private var remoteMediaReferrer: String = ""
    private var remoteMediaUserAgent: String = ""
    private var remoteMediaCookie: String? = null
    @Volatile private var qualitySources: List<YouTubeQualitySource> = emptyList()
    @Volatile private var qualityAudioUrl: String = ""
    /** 仅在本轮网页/本地视频源已挂载后，才读取其可选视频轨。 */
    @Volatile private var adaptiveQualitySourceReady = false
    @Volatile private var activeOpenRequestToken: Long = 0L
    // A media key can be reopened from History without changing the key. Give each open
    // a unique generation so a cancelled previous pipeline cannot resume beside the new TTS engine.
    private val pipelineGenerationLock = Any()
    private var pipelineGenerationCounter = 0L
    @Volatile private var activePipelineGeneration = 0L
    private var dubbingMutationGenerationCounter = 0L
    @Volatile private var activeDubbingMutationGeneration = 0L
    @Volatile private var processingEngineConfigFingerprint = ""
    // Snapshot the configuration that produced the currently completed session. Unlike the
    // preference-listener baseline above, this value must not change just because settings are
    // edited while the READY player is idle on the Home screen.
    @Volatile private var completedReadySessionKey = ""
    @Volatile private var completedReadySessionIgnoresTranscription = false
    @Volatile private var completedReadySessionEngineFingerprint = ""
    private var enginePreferencesListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var enginePreferenceRestartJob: Job? = null
    // Serialize every flow that creates/replaces a DubbingEngine. YouTube, remote web media,
    // local video and imported subtitles must not initialize a new TTS engine while a cancelled
    // pipeline is still unwinding a blocking Android TTS/JNI call.
    private val pipelineExecutionMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var qualityRecoveryAttempted = false
    private var qualityRecoveryJob: Job? = null
    private var qualityJob: Job? = null
    private var importedLines: List<SubLine>? = null
    // Subtitle inputs can arrive out of time order. Reuse the same interval index as dubbing
    // rather than rebuilding it every playback tick or assuming the UI list is sorted.
    private var highlightTimelineSource: List<SubLine>? = null
    private var highlightTimeline = DubbingTimeline(emptyList())
    private var lastLineIdx = Int.MIN_VALUE
    private var dubCollectJob: Job? = null
    private var speakerJob: Job? = null
    @Volatile private var subtitleDownloadJob: Job? = null
    private val asrAudioDownloadLocks = KeyedMutex<String>()
    // Do not evict downloaded audio while MediaExtractor is actively reading it.
    private val activeAsrAudioPaths = ActiveFileLeaseRegistry()

    companion object {
        private const val PREF_PLAYER_ORIGINAL_VOLUME = "player_original_audio_volume"
        private const val PREF_PLAYER_ORIGINAL_VOLUME_LAST = "player_original_audio_volume_last_nonzero"
        private const val PREF_PLAYER_DUBBING_VOLUME = "player_chinese_dubbing_volume"
        private const val PREF_PLAYER_DUBBING_VOLUME_LAST = "player_chinese_dubbing_volume_last_nonzero"

        @Volatile var instance: PlaybackService? = null
            private set
        val ui = MutableStateFlow(PlayerUiState())
        fun state(): StateFlow<PlayerUiState> = ui

        /**
         * A privacy-safe runtime snapshot attached to uncaught-crash records. Never include raw
         * video URLs, cookies, subtitle text, API keys, or local file paths here.
         */
        fun diagnosticSnapshot(): Map<String, String> {
            val state = ui.value
            val service = instance
            val snapshot = linkedMapOf(
                "播放服务实例状态" to if (service == null) "当前没有活动的 PlaybackService 实例" else "实例存在",
                "当前处理阶段" to state.phase.name,
                "当前视频源类型" to (service?.diagnosticMediaSourceType(state) ?: "服务实例不可用"),
                "当前会话指纹" to if (state.key.isBlank()) "无活动会话" else
                    (service?.safeDiagnosticSessionFingerprint(state.key) ?: "服务实例不可用"),
                "视频播放状态" to if (state.isPlaying) "播放中" else "未播放/状态未知",
                "中文配音开关" to state.dubbingOn.toString(),
                "原声音量档位" to state.originalVolume.toString(),
                "中文配音音量档位" to state.dubbingVolume.toString(),
                "当前处理是否暂停" to state.isProcessingPaused.toString(),
            )
            if (service != null) snapshot.putAll(service.notificationRuntimeSnapshot())
            return snapshot
        }

        /** 播放画面 TextureView（供悬浮窗摘取 reparent） */
        @Volatile var videoSurfaceView: android.view.TextureView? = null
        const val CH_MAIN = "playback"
        const val ACTION_TOGGLE_PROCESSING_PAUSE = "io.translatetube.local.action.TOGGLE_PROCESSING_PAUSE"
        const val ACTION_CANCEL_PROCESSING = "io.translatetube.local.action.CANCEL_PROCESSING"
        const val EXTRA_PROCESSING_SESSION_KEY = "io.translatetube.local.extra.PROCESSING_SESSION_KEY"

        /** True only when this history item was previously completed with the currently selected engines. */
        fun hasCompatibleHistorySnapshot(context: Context, key: String, isYouTube: Boolean): Boolean =
            ReadyHistorySessionConfigStore.matches(context, key, isYouTube = isYouTube)

        fun clearHistorySessionConfig(context: Context, key: String) =
            ReadyHistorySessionConfigStore.remove(context, key)

        fun clearAllHistorySessionConfig(context: Context) =
            ReadyHistorySessionConfigStore.clearAll(context)
    }

    /** 服务初始化状态，供播放页在初始化失败后安全重试。 */
    val isReady: Boolean get() = playerReady && ::pm.isInitialized

    /**
     * A video key identifies media, not a particular user action. The same history row can be
     * tapped again with different TTS settings, so compare the request token as well as the key.
     * Returning from Settings with the same token must not restart the active playback session.
     */
    fun isOpenRequestActive(key: String, requestToken: Long): Boolean =
        requestToken > 0L &&
            activeOpenRequestToken == requestToken &&
            videoKey == key &&
            ui.value.key == key

    /**
     * Reuse an already prepared video when it is opened again from Home history. This deliberately
     * does not call open(): open() invalidates the playback pipeline, retires the dubbing engine,
     * and returns the UI to PARSING. YouTube does not depend on the selected ASR engine once its
     * translated subtitle timeline has already been prepared.
     */
    fun resumeReadySessionIfCompatible(
        key: String,
        requestToken: Long,
        isYouTube: Boolean,
    ): Boolean {
        if (!playerReady || !::pm.isInitialized || requestToken <= 0L) return false
        val state = ui.value
        if (
            key.isBlank() ||
            key != videoKey ||
            key != completedReadySessionKey ||
            state.key != key ||
            state.phase != Phase.READY ||
            state.waitingForTranscriptionEngine ||
            state.waitingForDubbingEngine ||
            state.dubbingHasErrors
        ) return false

        val currentConfigSnapshot = ReadySessionSnapshotStore.findForCurrentConfig(this, key) { ignore ->
            ReadyHistorySessionConfigStore.fingerprint(this, ignoreTranscription = ignore)
        }
        if (
            currentConfigSnapshot != null &&
            currentConfigSnapshot.fingerprint != completedReadySessionEngineFingerprint
        ) {
            val restoredState = currentConfigSnapshot.state.copy(
                // Playback volume controls audibility; keep timeline preparation independent so
                // muting dubbing does not discard the background synthesis queue.
                dubbingOn = true,
                muteOriginal = state.originalVolume <= 0f,
                originalVolume = state.originalVolume,
                dubbingVolume = state.dubbingVolume,
                audioOnly = state.audioOnly,
                videoAspect = state.videoAspect,
            )
            io.translatetube.local.util.AppLogStore.info(
                this,
                "PlaybackFlow",
                "命中该视频此前成功的引擎配置组合，直接恢复其完整处理结果",
                diagnosticContext = mapOf(
                    "视频会话指纹" to safeDiagnosticSessionFingerprint(key),
                    "处理流程" to restoredState.processingFlow.name,
                    "恢复的配置指纹" to currentConfigSnapshot.fingerprint.take(16),
                    "原文字幕行数" to restoredState.lines.size.toString(),
                    "是否重新转写" to "否",
                    "是否重新翻译" to "否",
                    "配音缓存策略" to "复用该成功配置自己的 PCM，不重新合成",
                ),
            )
            open(
                key = key,
                videoId = videoId,
                localUri = localUri,
                importedLines = null,
                resumeState = restoredState,
                remoteMediaUrl = remoteMediaUrl,
                remoteMediaTitle = remoteMediaTitle,
                remoteMediaReferrer = remoteMediaReferrer,
                remoteMediaCookie = remoteMediaCookie,
                remoteMediaUserAgent = remoteMediaUserAgent,
                requestToken = requestToken,
                reuseMountedMediaForRestart = true,
                // This snapshot was verified against the selected configuration. Restore the
                // completed playback result and its own PCM cache without revisiting five stages.
                restoreReadySnapshot = true,
                forceRegenerateDubbing = false,
            )
            return true
        }

        val ignoreTranscription = completedReadySessionIgnoresTranscription
        if (ignoreTranscription && !isYouTube) return false
        val currentFingerprint = processingEnginePreferenceFingerprint(ignoreTranscription = ignoreTranscription)
        val previousDomains = ReadyHistorySessionConfigStore.savedFingerprints(this, key)
        val currentDomains = ReadyHistorySessionConfigStore.fingerprints(this)
        val restartStage = previousDomains?.let { previous ->
            chooseEngineRestartStage(previous, currentDomains, state, ignoreTranscription)
        }

        if (restartStage != null && state.lines.isNotEmpty()) {
            val restartState = stateForEngineRestart(state, restartStage)
            val importedRestartLines = if (this.importedLines != null) restartState?.lines else null
            io.translatetube.local.util.AppLogStore.info(
                this,
                "PlaybackFlow",
                "引擎配置变化，按最早受影响阶段恢复视频处理",
                diagnosticContext = mapOf(
                    "视频会话指纹" to safeDiagnosticSessionFingerprint(key),
                    "恢复阶段" to restartStage.name,
                    "转写配置是否变化" to (previousDomains.transcription != currentDomains.transcription).toString(),
                    "翻译配置是否变化" to (previousDomains.translation != currentDomains.translation).toString(),
                    "配音配置是否变化" to (previousDomains.dubbing != currentDomains.dubbing).toString(),
                    "已复用字幕行数" to state.lines.size.toString(),
                    "音轨处理策略" to when (restartStage) {
                        EngineRestartStage.TRANSCRIPTION -> "复用已缓存音轨；重新转写、翻译和配音"
                        EngineRestartStage.TRANSLATION -> "复用原文字幕和时间轴；重新翻译和配音"
                        EngineRestartStage.DUBBING -> "复用原文、中文字幕和时间轴；仅重新生成配音"
                    },
                ),
            )
            open(
                key = key,
                videoId = videoId,
                localUri = localUri,
                importedLines = importedRestartLines,
                resumeState = restartState,
                remoteMediaUrl = remoteMediaUrl,
                remoteMediaTitle = remoteMediaTitle,
                remoteMediaReferrer = remoteMediaReferrer,
                remoteMediaCookie = remoteMediaCookie,
                remoteMediaUserAgent = remoteMediaUserAgent,
                requestToken = requestToken,
                reuseMountedMediaForRestart = true,
                forceRegenerateDubbing = true,
            )
            return true
        }

        if (completedReadySessionEngineFingerprint.isBlank() || currentFingerprint != completedReadySessionEngineFingerprint) {
            return false
        }
        return try {
            if (!state.isPlaying) pm.play()
            activeOpenRequestToken = requestToken
            io.translatetube.local.util.AppLogStore.info(
                this,
                "PlaybackFlow",
                "首页历史记录复用已就绪的视频会话，跳过五阶段处理页",
                diagnosticContext = mapOf(
                    "视频来源" to if (isYouTube) "YouTube" else if (key.startsWith("remote:")) "网页视频" else "本地视频",
                    "视频会话指纹" to safeDiagnosticSessionFingerprint(key),
                    "播放请求标记" to requestToken.toString(),
                    "引擎配置是否匹配" to "是",
                    "YouTube 是否忽略转写引擎配置" to isYouTube.toString(),
                ),
            )
            true
        } catch (error: Exception) {
            android.util.Log.w("PlaybackService", "Unable to resume completed history session", error)
            false
        }
    }

    private fun chooseEngineRestartStage(
        previous: EngineConfigFingerprints,
        current: EngineConfigFingerprints,
        state: PlayerUiState,
        ignoreTranscription: Boolean,
    ): EngineRestartStage? {
        val importedFlow = state.processingFlow == ProcessingFlow.IMPORTED_SUBTITLES ||
            state.processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
        if (!ignoreTranscription && !importedFlow && previous.transcription != current.transcription) {
            return EngineRestartStage.TRANSCRIPTION
        }
        if (previous.translation != current.translation) {
            if (state.lines.isEmpty()) return null
            return if (
                isChineseLanguage(state.sourceLang) ||
                state.processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
            ) EngineRestartStage.DUBBING else EngineRestartStage.TRANSLATION
        }
        if (previous.dubbing != current.dubbing) {
            if (state.lines.isEmpty()) return null
            val hasMissingTranslation = !isChineseLanguage(state.sourceLang) &&
                state.processingFlow != ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES &&
                state.lines.any { needsSubtitleTranslation(it, state.sourceLang) }
            return if (hasMissingTranslation) null else EngineRestartStage.DUBBING
        }
        return null
    }

    private fun stateForEngineRestart(
        state: PlayerUiState,
        stage: EngineRestartStage,
    ): PlayerUiState? = when (stage) {
        EngineRestartStage.TRANSCRIPTION -> state.copy(
            lines = emptyList(),
            translatedLineCount = 0,
            totalLines = 0,
            preparedUntil = 0,
            preparedCount = 0,
            sourceLang = "",
            waitingForTranscriptionEngine = false,
            waitingForDubbingEngine = false,
            dubbingHasErrors = false,
            error = null,
            failedPhase = null,
            failedProgress = "",
        )
        EngineRestartStage.TRANSLATION -> state.copy(
            lines = state.lines.mapIndexed { index, line -> line.copy(index = index, zh = null) },
            translatedLineCount = 0,
            preparedUntil = 0,
            preparedCount = 0,
            waitingForTranscriptionEngine = false,
            waitingForDubbingEngine = false,
            dubbingHasErrors = false,
            error = null,
            failedPhase = null,
            failedProgress = "",
        )
        EngineRestartStage.DUBBING -> state.copy(
            preparedUntil = 0,
            preparedCount = 0,
            waitingForTranscriptionEngine = false,
            waitingForDubbingEngine = false,
            dubbingHasErrors = false,
            error = null,
            failedPhase = null,
            failedProgress = "",
        )
    }

    private fun beginPipelineGeneration(): Long = synchronized(pipelineGenerationLock) {
        (++pipelineGenerationCounter).also { activePipelineGeneration = it }
    }

    private fun beginDubbingMutationGeneration(): Long = synchronized(pipelineGenerationLock) {
        (++dubbingMutationGenerationCounter).also { activeDubbingMutationGeneration = it }
    }

    private suspend fun ensureCurrentSession(
        sessionKey: String,
        pipelineGeneration: Long? = null,
        dubbingMutationGeneration: Long? = null,
    ) {
        // Extractor/JNI calls are sometimes synchronous. A canceled job may return from one
        // without suspending, so explicitly check before publishing results or opening engines.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        PlaybackSessionGuard.ensureCurrent(
            sessionKey = sessionKey,
            activeVideoKey = videoKey,
            activeUiKey = ui.value.key,
            expectedPipelineGeneration = pipelineGeneration,
            activePipelineGeneration = activePipelineGeneration,
            expectedDubbingMutationGeneration = dubbingMutationGeneration,
            activeDubbingMutationGeneration = activeDubbingMutationGeneration,
        )
    }

    private fun isProcessingPhase(phase: Phase): Boolean = phase in setOf(
        Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING,
        Phase.TRANSLATING, Phase.SYNTHESIZING,
    )

    private fun isProcessingEnginePreferenceKey(key: String): Boolean =
        ReadyHistorySessionConfigStore.isRelevantPreferenceKey(key)

    /**
     * Hash only the settings that influence transcription, translation or synthesis.
     * The fingerprint is diagnostic-safe: API keys and other preference values are never logged.
     */
    private fun processingEnginePreferenceFingerprint(ignoreTranscription: Boolean = false): String =
        ReadyHistorySessionConfigStore.fingerprint(this, ignoreTranscription)

    /**
     * Restart the active pipeline when an output-affecting engine setting changes.
     * A completed READY session is eligible too: changing translation engines while the player
     * is open must not leave the previous engine's Chinese subtitles on screen.
     */
    private fun shouldRestartForEnginePreferenceChange(state: PlayerUiState): Boolean =
        isProcessingPhase(state.phase) ||
            (state.phase == Phase.READY && (
                state.waitingForTranscriptionEngine ||
                    state.waitingForDubbingEngine ||
                    (state.key.isNotBlank() && state.key == videoKey && state.lines.isNotEmpty())
                )) ||
            (state.phase == Phase.ERROR &&
                state.key.isNotBlank() &&
                state.key == videoKey &&
                state.lines.isNotEmpty() &&
                state.sourceLang.isNotBlank())

    private fun registerProcessingEnginePreferenceWatcher() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        processingEngineConfigFingerprint = processingEnginePreferenceFingerprint()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey != null && isProcessingEnginePreferenceKey(changedKey)) {
                val latestFingerprint = processingEnginePreferenceFingerprint()
                val current = ui.value
                if (!shouldRestartForEnginePreferenceChange(current)) {
                    processingEngineConfigFingerprint = latestFingerprint
                } else if (latestFingerprint != processingEngineConfigFingerprint) {
                    // Debounce rapid preference writes so a single settings change restarts the
                    // pipeline once, using the final selected ASR/translation/TTS configuration.
                    enginePreferenceRestartJob?.cancel()
                    val changedSessionKey = videoKey
                    val changedPipelineGeneration = activePipelineGeneration
                    enginePreferenceRestartJob = scope.launch {
                        kotlinx.coroutines.delay(850L)
                        val stableFingerprint = processingEnginePreferenceFingerprint()
                        val state = ui.value
                        // If the user canceled or opened a different item while this debounce was
                        // pending, don't resurrect that task. A pipeline that simply reached READY
                        // is still restarted because the engine changed while it was processing.
                        if (changedSessionKey.isBlank() ||
                            videoKey != changedSessionKey ||
                            activePipelineGeneration != changedPipelineGeneration ||
                            state.key != changedSessionKey
                        ) {
                            processingEngineConfigFingerprint = stableFingerprint
                            return@launch
                        }
                        if (stableFingerprint == processingEngineConfigFingerprint) return@launch

                        val sessionKey = changedSessionKey
                        val restartLocalUri = localUri
                        val restartRemoteUrl = remoteMediaUrl
                        val restartVideoId = videoId ?: if (restartRemoteUrl == null) {
                            sessionKey.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")
                        } else {
                            null
                        }
                        val restartRemoteTitle = remoteMediaTitle
                        val restartRemoteReferrer = remoteMediaReferrer
                        val restartRemoteCookie = remoteMediaCookie
                        val restartRemoteUserAgent = remoteMediaUserAgent

                        // If this exact engine combination has completed successfully before, restore
                        // that result directly. It is normal for the latest-success fingerprint to
                        // differ after the user tried another local/online/cloud engine.
                        val matchingSuccessfulSnapshot =
                            ReadySessionSnapshotStore.findForCurrentConfig(this@PlaybackService, sessionKey) { ignore ->
                                ReadyHistorySessionConfigStore.fingerprint(
                                    this@PlaybackService,
                                    ignoreTranscription = ignore,
                                )
                            }
                        if (matchingSuccessfulSnapshot != null) {
                            val restoredSuccessfulState = matchingSuccessfulSnapshot.state.copy(
                                dubbingOn = true,
                                muteOriginal = state.originalVolume <= 0f,
                                originalVolume = state.originalVolume,
                                dubbingVolume = state.dubbingVolume,
                                audioOnly = state.audioOnly,
                                videoAspect = state.videoAspect,
                            )
                            io.translatetube.local.util.AppLogStore.info(
                                this@PlaybackService,
                                "PlaybackFlow",
                                "切回此前成功的引擎配置组合，直接恢复播放",
                                diagnosticContext = mapOf(
                                    "视频会话指纹" to safeDiagnosticSessionFingerprint(sessionKey),
                                    "恢复的配置指纹" to matchingSuccessfulSnapshot.fingerprint.take(16),
                                    "恢复的处理流程" to restoredSuccessfulState.processingFlow.name,
                                    "恢复字幕行数" to restoredSuccessfulState.lines.size.toString(),
                                    "跳过五阶段重新处理" to "是",
                                    "配音缓存策略" to "复用该成功配置对应的配音 PCM",
                                ),
                            )
                            withContext(Dispatchers.Main.immediate) {
                                open(
                                    key = sessionKey,
                                    videoId = restartVideoId,
                                    localUri = restartLocalUri,
                                    importedLines = null,
                                    resumeState = restoredSuccessfulState,
                                    remoteMediaUrl = restartRemoteUrl,
                                    remoteMediaTitle = restartRemoteTitle,
                                    remoteMediaReferrer = restartRemoteReferrer,
                                    remoteMediaCookie = restartRemoteCookie,
                                    remoteMediaUserAgent = restartRemoteUserAgent,
                                    requestToken = activeOpenRequestToken,
                                    reuseMountedMediaForRestart = true,
                                    restoreReadySnapshot = true,
                                    forceRegenerateDubbing = false,
                                )
                            }
                            return@launch
                        }

                        // On a completed session, compare the saved per-domain fingerprints and
                        // restart at the earliest affected stage. A translation-engine switch
                        // reuses the source transcript/timestamps but clears old Chinese text;
                        // a TTS-only switch keeps the validated translation and rebuilds speech.
                        val canResumeFailedOrReadyState =
                            state.phase in setOf(Phase.READY, Phase.ERROR) &&
                                state.lines.isNotEmpty() &&
                                state.sourceLang.isNotBlank()
                        val previousDomains = if (canResumeFailedOrReadyState) {
                            ReadyHistorySessionConfigStore.savedFingerprints(this@PlaybackService, sessionKey)
                        } else null
                        val currentDomains = if (previousDomains != null) {
                            ReadyHistorySessionConfigStore.fingerprints(this@PlaybackService)
                        } else null
                        val readyRestartStage = if (previousDomains != null && currentDomains != null) {
                            val ignoreTranscription = completedReadySessionKey == sessionKey &&
                                completedReadySessionIgnoresTranscription
                            chooseEngineRestartStage(previousDomains, currentDomains, state, ignoreTranscription)
                        } else null
                        val readyRestartState = readyRestartStage?.let { stage ->
                            stateForEngineRestart(state, stage)
                        }
                        val canResumeAtStage = canResumeFailedOrReadyState &&
                            readyRestartStage != null &&
                            readyRestartState != null
                        val importedRestartLines = if (canResumeAtStage && this@PlaybackService.importedLines != null) {
                            readyRestartState?.lines
                        } else null
                        val restartRequestToken = activeOpenRequestToken

                        io.translatetube.local.util.AppLogStore.info(
                            this@PlaybackService,
                            "PlaybackFlow",
                            when {
                                canResumeAtStage && state.phase == Phase.READY ->
                                    "播放完成后检测到引擎设置变化，按最早受影响阶段重新处理当前视频"
                                canResumeAtStage && state.phase == Phase.ERROR ->
                                    "处理失败后检测到引擎设置变化，恢复最早受影响阶段并按新配置重试"
                                else ->
                                    "处理期间检测到引擎设置变化，按新配置重新处理当前视频"
                            },
                            diagnosticContext = mapOf(
                                "视频会话指纹" to safeDiagnosticSessionFingerprint(sessionKey),
                                "触发时处理阶段" to state.phase.name,
                                "处理流程" to state.processingFlow.name,
                                "旧引擎配置指纹" to processingEngineConfigFingerprint.take(16),
                                "新引擎配置指纹" to stableFingerprint.take(16),
                                "选择的恢复阶段" to (readyRestartStage?.name ?: "从流程入口恢复"),
                                "已复用字幕行数" to (readyRestartState?.lines?.size ?: 0).toString(),
                                "恢复策略" to when (readyRestartStage) {
                                    EngineRestartStage.TRANSCRIPTION -> "重新获取/转写原文字幕，再翻译和配音"
                                    EngineRestartStage.TRANSLATION -> "复用原文和时间轴，按新翻译引擎重新翻译，再生成配音"
                                    EngineRestartStage.DUBBING -> "复用原文、中文字幕和时间轴，仅重新生成配音"
                                    null -> "使用现有缓存策略重新进入公共处理流程"
                                },
                                "音频策略" to "已缓存音轨和原文字幕按来源与转写引擎隔离复用",
                            ),
                        )
                        withContext(Dispatchers.Main.immediate) {
                            open(
                                key = sessionKey,
                                videoId = restartVideoId,
                                localUri = restartLocalUri,
                                importedLines = importedRestartLines,
                                resumeState = if (canResumeAtStage) readyRestartState else null,
                                remoteMediaUrl = restartRemoteUrl,
                                remoteMediaTitle = restartRemoteTitle,
                                remoteMediaReferrer = restartRemoteReferrer,
                                remoteMediaCookie = restartRemoteCookie,
                                remoteMediaUserAgent = restartRemoteUserAgent,
                                requestToken = restartRequestToken,
                                reuseMountedMediaForRestart = canResumeAtStage,
                                forceRegenerateDubbing = true,
                            )
                        }
                    }
                }
            }
        }
        enginePreferencesListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    /** Processing checkpoints wait here while paused. */
    private suspend fun awaitProcessingResumed(
        sessionKey: String,
        pipelineGeneration: Long? = null,
    ) {
        if (processingPaused) processingPauseGate.withLock { /* resumes when unlocked */ }
        ensureCurrentSession(sessionKey, pipelineGeneration)
    }

    fun toggleProcessingPause() {
        if (!isProcessingPhase(ui.value.phase)) return
        if (processingPaused) {
            processingPaused = false
            if (processingPauseGate.isLocked) runCatching { processingPauseGate.unlock() }
        } else if (processingPauseGate.tryLock()) {
            processingPaused = true
        }
        ui.update { current ->
            if (current.key == videoKey) current.copy(isProcessingPaused = processingPaused)
            else current
        }
    }

    fun cancelProcessing() {
        val current = ui.value
        if (!isProcessingPhase(current.phase)) {
            updateProcessingNotification(current)
            return
        }
        beginPipelineGeneration()
        processingPaused = false
        if (processingPauseGate.isLocked) runCatching { processingPauseGate.unlock() }
        prepJob?.cancel(kotlinx.coroutines.CancellationException("用户取消视频处理"))
        prepJob = null
        subtitleDownloadJob?.cancel()
        subtitleDownloadJob = null
        speakerJob?.cancel()
        speakerJob = null
        qualityJob?.cancel()
        qualityJob = null
        qualityRecoveryJob?.cancel()
        qualityRecoveryJob = null
        dubCollectJob?.cancel()
        dubCollectJob = null
        runCatching { retireCurrentDubbing() }
        if (playerReady && ::pm.isInitialized) runCatching { safelySetOriginalVolume(1f, "source audio restore requested") }
        updateUiForSession(current.key) {
            it.copy(
                phase = Phase.ERROR,
                progress = "",
                error = "已取消处理。点击重试即可重新开始。",
                muteOriginal = false,
                noModelHint = null,
                            waitingForTranscriptionEngine = false,
                            waitingForDubbingEngine = false,
                isProcessingPaused = false,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedSession = intent?.getStringExtra(EXTRA_PROCESSING_SESSION_KEY)
        if (!requestedSession.isNullOrBlank() && requestedSession != ui.value.key) {
            updateProcessingNotification(ui.value)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE_PROCESSING_PAUSE -> {
                toggleProcessingPause()
                updateProcessingNotification(ui.value)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_PROCESSING -> {
                cancelProcessing()
                updateProcessingNotification(ui.value)
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 主线程把画面 TextureView 交给播放器 */
    fun attachSurface(tv: android.view.TextureView) {
        // Service 初始化失败时，PlayerScreen 仍可能尝试挂载 TextureView。
        // 在投递到主线程前后都检查，避免访问尚未初始化的 lateinit PlayerManager。
        if (!playerReady || !::pm.isInitialized) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (playerReady && ::pm.isInitialized) {
                runCatching { pm.player.setVideoTextureView(tv) }
                    .onFailure { android.util.Log.w("PlaybackService", "Attach video surface failed", it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedOriginalVolume = audioPrefs.getFloat(PREF_PLAYER_ORIGINAL_VOLUME, 1f).coerceIn(0f, 1f)
        val savedDubbingVolume = audioPrefs.getFloat(PREF_PLAYER_DUBBING_VOLUME, 1f).coerceIn(0f, 1f)
        ui.update { state ->
            state.copy(
                originalVolume = savedOriginalVolume,
                dubbingVolume = savedDubbingVolume,
                muteOriginal = savedOriginalVolume <= 0f,
            )
        }
        // Supply the application context for structured NewPipe/YouTube extraction diagnostics.
        // The extractor itself is initialized lazily by the first metadata request.
        NewPipeRepo.setLogContext(applicationContext)
        registerProcessingEnginePreferenceWatcher()
        // 先尽早建立前台通知，再初始化播放器；任何初始化异常都转成页面错误，
        // 避免点击“观看”后因 Service onCreate 抛异常而直接杀掉整个进程。
        try {
            NotificationManagerCompat_c.create(this)
            val boot = NotificationCompat.Builder(this, CH_MAIN)
                .setSmallIcon(io.translatetube.local.R.drawable.ic_stat_brand)
                .setContentTitle("大象翻译")
                .setContentText("正在准备…")
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, io.translatetube.local.MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            rememberNotificationAudit("BEFORE startForeground id=1001 purpose=service-start-placeholder")
            io.translatetube.local.util.AppLogStore.info(
                this,
                "NotificationDiagnostics",
                "即将提交播放服务启动前台通知",
                diagnosticContext = mapOf(
                    "通知 ID" to "1001",
                    "通知用途" to "播放服务启动占位通知；后续可能被 Media3 媒体控制通知替换",
                    "通知构建方式" to "NotificationCompat.Builder",
                    "通知是否 ongoing" to "true",
                    "提交线程" to Thread.currentThread().name,
                ),
            )
            startForeground(1001, boot)
            rememberNotificationAudit("AFTER startForeground id=1001 returned")
            io.translatetube.local.util.AppLogStore.info(
                this,
                "NotificationDiagnostics",
                "播放服务启动前台通知提交调用已返回",
                diagnosticContext = mapOf(
                    "通知 ID" to "1001",
                    "通知用途" to "启动占位通知",
                    "提交线程" to Thread.currentThread().name,
                ),
            )

            pm = PlayerManager(this)
            pm.playbackAllowed = { !isDubbingPlaybackBlocked(ui.value) }
            pm.originalVolume = ui.value.originalVolume
            pm.dubbingVolume = ui.value.dubbingVolume
            // Match the new mix bus to the currently visible mode before any timeline tick runs.
            pm.dubbingBus.setDubbingEnabled(ui.value.dubbingOn)
            pm.onTick = { ms -> tickFromPlayerCallback(ms, "player ticker") }
            pm.onEnded = {
                val sessionKey = ui.value.key
                val engine = dubbing
                val activeIndex = engine?.currentLineIdx() ?: -1
                val activeSentenceCanFinish = engine != null && activeIndex >= 0 && (
                    engine.shouldContinueCurrentCue(-1) ||
                        (!engine.hasStartedPlaybackFor(activeIndex) &&
                            engine.isPrepared(activeIndex) && !engine.isSilent(activeIndex))
                    )
                val queuedCuesRemain = hasDubbingCatchUpCues(sessionKey)
                if (engine != null && ui.value.dubbingOn && (queuedCuesRemain || activeSentenceCanFinish)) {
                    // The video has ended; finish speech off-timeline without pausing or restarting the video.
                    if (activeIndex >= 0 && !engine.hasStartedPlaybackFor(activeIndex) &&
                        engine.isPrepared(activeIndex) && !engine.isSilent(activeIndex)
                    ) {
                        enqueueDubbingCatchUpCue(sessionKey, activeIndex)
                    }
                    safelySetOriginalVolume(1f, "video ended; draining remaining dubbing")
                    ui.update {
                        if (it.key == sessionKey) it.copy(
                            isPlaying = false,
                            currentLine = -1,
                            activeDubbingLine = activeIndex,
                            dubbingStatus = "视频已结束，正在完成剩余配音",
                        ) else it
                    }
                    startDubbingDrainAfterVideoEnd(engine, sessionKey)
                } else {
                    cancelDubbingDrainAfterVideoEnd()
                    clearDubbingCatchUpQueue(sessionKey = sessionKey, positionMs = pm.position())
                    safelyControlDubbing("player ended") { dubbing?.stop() }
                    safelySetOriginalVolume(1f, "player ended")
                    ui.update { it.copy(isPlaying = false, currentLine = -1, activeDubbingLine = -1) }
                }
            }
            pm.onVideoAspect = { ratio -> ui.update { it.copy(videoAspect = ratio) } }

            // Keep dubbed AudioTrack locked to ExoPlayer's playback state. This callback
            // runs with the player's state change, so voice stops immediately on pause and
            // resumes from the paused sample position instead of finishing the current line.
            pm.player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val endedWithPendingDubbing = !isPlaying && ::pm.isInitialized &&
                        pm.player.playbackState == androidx.media3.common.Player.STATE_ENDED &&
                        hasPendingDubbingAfterVideoEnd(ui.value.key)
                    if (!isPlaying && ::pm.isInitialized && !pm.player.playWhenReady && !endedWithPendingDubbing) {
                        cancelDubbingDrainAfterVideoEnd()
                        clearDubbingCatchUpQueue(sessionKey = ui.value.key, positionMs = pm.position())
                    }
                    if (isPlaying && dubbingDrainAfterVideoEnd) {
                        cancelDubbingDrainAfterVideoEnd()
                        clearDubbingCatchUpQueue(sessionKey = ui.value.key, positionMs = pm.position())
                    }
                    val drainingEndedVideo = !isPlaying && ::pm.isInitialized &&
                        pm.player.playbackState == androidx.media3.common.Player.STATE_ENDED &&
                        hasPendingDubbingAfterVideoEnd(ui.value.key)
                    if (isPlaying && ui.value.dubbingOn) {
                        // A new video play session owns the timeline again; discard stale voice
                        // and let the next tick select the subtitle at the live playhead.
                        safelyControlDubbing("player resumed") { dubbing?.stop() }
                        lastLineIdx = Int.MIN_VALUE
                        if (::pm.isInitialized) tickFromPlayerCallback(pm.position(), "player resumed")
                    } else if (!drainingEndedVideo) {
                        safelyControlDubbing("player paused") { dubbing?.pausePlayback() }
                        ui.update { state ->
                            if (state.activeDubbingLine == -1) state
                            else state.copy(activeDubbingLine = -1)
                        }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: androidx.media3.common.Player.PositionInfo,
                    newPosition: androidx.media3.common.Player.PositionInfo,
                    reason: Int,
                ) {
                    cancelDubbingDrainAfterVideoEnd()
                    clearDubbingCatchUpQueue(sessionKey = ui.value.key, positionMs = newPosition.positionMs)
                    // A seek can land inside the same subtitle cue, so comparing line indexes
                    // alone is not enough. Invalidate any queued/playing PCM and re-evaluate at
                    // Media3's new playhead immediately; this also covers seeks from media controls.
                    safelyControlDubbing("position discontinuity") { dubbing?.stop() }
                    // 混流总线：seek 后音频流会 flush；清空旧块并按新媒体头重设时钟基点，
                    // 下一次 playAt()（本次 tick 立即触发）重新挂载正确时间窗的块。
                    if (::pm.isInitialized) {
                        if (System.nanoTime() < keepDubbingBusUntilNanos) {
                            // 开播前的 flush（见 startPlaybackWithMountedDubbing）：配音已经挂好，
                            // 只把读头和时钟基点对齐到新位置，不能清总线，否则第一句又要重新挂。
                            keepDubbingBusUntilNanos = 0L
                            pm.dubbingBus.rewindReadHead()
                        } else {
                            pm.dubbingBus.reset()
                        }
                        pm.dubbingBus.setStreamStartUs(newPosition.positionMs * 1000L)
                    }
                    lastLineIdx = Int.MIN_VALUE
                    if (::pm.isInitialized) tickFromPlayerCallback(newPosition.positionMs, "position discontinuity")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        // 混流时钟基点：READY 后音频流从头输出，以当前播放位置校准总线。
                        if (::pm.isInitialized) {
                            pm.dubbingBus.setStreamStartUs(
                                pm.player.currentPosition.coerceAtLeast(0L) * 1000L,
                            )
                        }
                        ui.update { state ->
                            if (state.progress.startsWith("正在尝试兼容清晰度 ")) {
                                state.copy(phase = Phase.READY, progress = "", error = null)
                            } else state
                        }
                    }
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    // HLS/DASH 清单解析后，Media3 才会公开真实的视频轨与分辨率。
                    refreshAdaptiveVideoQualityOptions()
                }
            })

            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, io.translatetube.local.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session = MediaSession.Builder(this, pm.player)
                .setSessionActivity(pi)
                .build()

            scope.launch {
                pm.isPlaying.collect { playing -> ui.update { it.copy(isPlaying = playing) } }
            }
            // Processing belongs to the foreground service, not the visible Activity.
            // Keep the ongoing notification current so users can switch apps and return later.
            scope.launch {
                ui.collect { state -> updateProcessingNotification(state) }
            }
            scope.launch {
                pm.errorSink.collect { err ->
                    if (err == null) return@collect
                    val activeRecovery = qualityRecoveryJob?.takeIf { it.isActive }
                    if (activeRecovery != null) {
                        // Media3 can report the same obsolete signed-URL failure more than once
                        // while the replacement source is being installed. Do not cancel the
                        // active recovery job in response to that duplicate callback.
                        android.util.Log.w(
                            "PlaybackService",
                            "Ignoring duplicate player error while YouTube recovery is active: $err",
                        )
                    } else {
                        val id = videoId
                        val sessionKey = ui.value.key
                        if (id != null && qualitySources.isNotEmpty() && !qualityRecoveryAttempted) {
                            qualityRecoveryAttempted = true
                            qualityRecoveryJob = scope.launch {
                                recoverYouTubePlayback(sessionKey, id, err)
                            }
                        } else {
                            ui.update { it.copy(error = err, phase = Phase.ERROR, progress = "") }
                        }
                    }
                }
            }
            playerReady = true
            instance = this
        } catch (e: Throwable) {
            // Player/MediaSession startup can fail with LinkageError on older Android builds.
            // Keep those failures visible in the player page instead of killing the app process.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            playerReady = false
            instance = this
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            ui.update {
                it.copy(phase = Phase.ERROR, progress = "", error = "播放器初始化失败（${e.javaClass.simpleName}）：$message")
            }
            android.util.Log.e("PlaybackService", "Playback service initialization failed", e)
            // 不保留一个无法播放的前台服务实例；页面仍可显示错误并在用户点击重试时重新启动。
            stopSelf()
        }
    }


    /**
     * A worker can remain active briefly after marking the final row ready. Use cue readiness as
     * the user-visible completion condition so notifications don't stick at 100% while cleanup ends.
     */
    private fun isDubbingPreparationIncomplete(state: PlayerUiState): Boolean {
        val engine = dubbing ?: return false
        if (!engine.isPreparing) return false
        // A session with no subtitle cues has no synthesis work to wait for.
        return state.totalLines > 0 && state.preparedCount < state.totalLines
    }

    /**
     * Generated-PCM dubbing must be fully prepared before the video starts. Direct System TTS
     * speaks live on the timeline and has no PCM batch to wait for, so it keeps its small startup
     * buffer instead. Failed cues are still counted as prepared by DubbingEngine and safely fall
     * back to source audio rather than blocking the whole video forever.
     */
    private suspend fun awaitDubbingSynthesisBeforePlayback(
        sessionKey: String,
        pipelineGeneration: Long,
        engine: DubbingEngine,
        lines: List<SubLine>,
    ) {
        if (engine.usesImmediateTimelinePlayback || lines.isEmpty()) return

        val spokenIndices = DubbingStartupBufferPolicy.spokenCueIndices(lines)
        val firstSpeechCue = spokenIndices.firstOrNull() ?: return
        engine.prioritizeAround(firstSpeechCue, lookAhead = 12)

        // 滚动缓冲：前几句就绪即开播，合成在后台继续追赶（VideoLingo 式流水线的实时版）。
        // 不再等全部合成完成——视频永不因配音暂停；30 秒兜底超时防极端场景卡死。
        val needReady = minOf(6, spokenIndices.size)
        val deadlineNs = System.nanoTime() +
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(30_000L)
        var lastReportedCount = -1
        while (true) {
            awaitProcessingResumed(sessionKey, pipelineGeneration)
            ensureCurrentSession(sessionKey, pipelineGeneration)
            if (dubbing !== engine) {
                throw kotlinx.coroutines.CancellationException("配音引擎已更换，取消等待合成")
            }

            val prepared = engine.preparedCount.value.coerceIn(0, lines.size)
            if (prepared != lastReportedCount) {
                lastReportedCount = prepared
                updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                    state.copy(
                        phase = Phase.SYNTHESIZING,
                        progress = "正在合成中文配音（$prepared/${lines.size}），前几句就绪即开始播放",
                    )
                }
            }
            val readyAhead = spokenIndices
                .take(needReady)
                .count { engine.isPrepared(it) || engine.isSilent(it) }
            if (readyAhead >= needReady) break
            if (System.nanoTime() >= deadlineNs) break
            kotlinx.coroutines.delay(100L)
        }

        // Never start source-only playback if the initial usable voice failed or is still unavailable.
        // A failed cue is counted as "prepared" by the queue so progress can continue, but it is
        // not an audible dubbing result and must not be mistaken for a successful startup buffer.
        awaitProcessingResumed(sessionKey, pipelineGeneration)
        ensureCurrentSession(sessionKey, pipelineGeneration)
        val firstAudibleCue = spokenIndices.firstOrNull { index ->
            lines[index].zh?.let {
                io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(it)
            } == true
        } ?: return
        val firstCueHasAudio = engine.isPrepared(firstAudibleCue) && !engine.isSilent(firstAudibleCue)
        if (!firstCueHasAudio) {
            val terminalFailure = engine.isSilent(firstAudibleCue)
            val hint = if (terminalFailure) {
                "所选配音引擎未能生成首句中文配音，视频已暂停。请检查在线语音网络/服务或本地模型文件后，重新处理此视频。"
            } else {
                "等待首句中文配音超过启动等待时间，视频保持暂停。请检查网络连接、语音服务或模型状态后重新处理。"
            }
            updateUiForSession(sessionKey, pipelineGeneration) { state ->
                state.copy(
                    waitingForDubbingEngine = !terminalFailure,
                    dubbingHasErrors = state.dubbingHasErrors || terminalFailure,
                    dubbingStatus = if (terminalFailure) "首句配音生成失败，视频已暂停" else "首句配音尚未就绪，视频保持暂停",
                    noModelHint = state.noModelHint ?: hint,
                    progress = if (terminalFailure) "首句配音生成失败，视频已暂停" else "等待首句配音生成完成；视频暂不播放",
                )
            }
            withContext(Dispatchers.Main) {
                if (videoKey == sessionKey && ui.value.key == sessionKey) pm.pause()
            }
            return
        }
    }

    /** 开播前的 flush 会触发一次位置不连续回调；这段时间内保留已挂载的配音，不清总线。 */
    @Volatile private var keepDubbingBusUntilNanos = 0L

    /**
     * 开播：先把起播位置附近的配音挂到混流总线，再让播放器音频管线从起播位置重读一遍，最后 play。
     *
     * 播放器在点播放之前就会预读并混好开头一小段音频（几百毫秒）。如果那时第一句配音还没挂上
     * 总线，这一段就只有原声，配音只能等预读之后才开口——表现为"视频已经开始说话了，中文
     * 配音还没开始，中途才接上"。所以必须：挂好第一批句子 → flush 一次音频管线（保留总线）→
     * 播放，让开头的音频带着配音重新混一遍。
     *
     * @param seekToMs 非空表示需要先跳到该位置（续播）；为空表示从播放器当前位置开始。
     * @param ensureCurrent 每次等待之后检查会话是否仍然有效，失效时抛出取消。
     */
    private fun isDubbingPlaybackBlocked(state: PlayerUiState): Boolean =
        // Never let a pending seek/resume or external media-control action start the source
        // while ASR is waiting for the user to select/install/configure a transcription engine.
        state.waitingForTranscriptionEngine ||
            (state.dubbingOn && (
                state.waitingForDubbingEngine ||
                    state.dubbingHasErrors ||
                    state.phase == Phase.ERROR
                ))

    private suspend fun startPlaybackWithMountedDubbing(
        seekToMs: Long?,
        ensureCurrent: suspend () -> Unit,
    ) {
        val entryState = ui.value
        if (isDubbingPlaybackBlocked(entryState)) {
            cancelPendingSeekResume()
            pm.pause()
            safelyControlDubbing("配音引擎未就绪或合成失败，阻止自动播放") { dubbing?.stop() }
            ui.update { current ->
                if (current.key == entryState.key) {
                    current.copy(isPlaying = false, currentLine = -1, activeDubbingLine = -1)
                } else current
            }
            return
        }
        val startMs = (seekToMs ?: pm.position()).coerceAtLeast(0L)
        val engine = dubbing
        val mountsOnBus = engine != null && ui.value.dubbingOn && !engine.usesImmediateTimelinePlayback
        if (engine != null && mountsOnBus) {
            // 预读已经把读头推到开头之后：归零，让第一批句子按各自的期望时间挂载。
            pm.dubbingBus.rewindReadHead(freezeMs = STARTUP_READ_HEAD_FREEZE_MS)
            engine.remountForPlaybackStart(startMs + subtitleTimingOffsetMs())
            engine.primeUpcomingCuesAndWait(
                startMs + subtitleTimingOffsetMs(),
                lookAheadMs = 2_500L,
                waitMs = STARTUP_MOUNT_WAIT_MS,
            )
            ensureCurrent()
            keepDubbingBusUntilNanos = System.nanoTime() + STARTUP_FLUSH_KEEP_BUS_NANOS
            pm.seekTo(startMs)
        } else if (seekToMs != null) {
            pm.seekTo(startMs)
        }
        primeSubtitleHighlightAtPlaybackStart(pm.position())
        pm.play()
    }

    private fun diagnosticMediaSourceType(state: PlayerUiState): String = when {
        state.key.isBlank() -> "无活动会话"
        state.key.startsWith("youtube:", ignoreCase = true) -> "YouTube"
        remoteMediaUrl?.isNotBlank() == true -> "网页视频/远程媒体"
        localUri != null -> "本地视频/文件"
        state.key.startsWith("import:", ignoreCase = true) -> "字幕导入"
        else -> "其他或历史会话"
    }

    private fun notificationRuntimeSnapshot(): Map<String, String> {
        val state = ui.value
        val now = android.os.SystemClock.elapsedRealtime()
        val lastPostedAt = lastProcessingNotificationPostedAtMs
        val pendingState = synchronized(processingNotificationUpdateLock) {
            "已排队=$processingNotificationUpdateScheduled；有待合并更新=$processingNotificationUpdatePending"
        }
        return linkedMapOf(
            "进度通知 ID" to PROCESSING_NOTIFICATION_ID.toString(),
            "前台启动占位通知 ID" to "1001",
            "媒体控制通知默认 ID" to "1001",
            "媒体通知与进度通知是否使用独立 ID" to (PROCESSING_NOTIFICATION_ID != 1001).toString(),
            "进度通知使用的样式" to "NotificationCompat.BigTextStyle",
            "进度通知成功提交次数" to processingNotificationPostCount.toString(),
            "距最近一次进度通知成功提交的毫秒数" to if (lastPostedAt > 0L) (now - lastPostedAt).coerceAtLeast(0L).toString() else "尚未成功提交",
            "最近一次通知审计状态签名" to (lastProcessingNotificationAuditSignature ?: "暂无"),
            "Media3 媒体通知更新次数" to mediaNotificationUpdateCount.toString(),
            "距最近一次 Media3 通知更新的毫秒数" to if (lastMediaNotificationUpdatedAtMs > 0L) (now - lastMediaNotificationUpdatedAtMs).coerceAtLeast(0L).toString() else "尚未触发",
            "最近一次 Media3 通知状态签名" to (lastMediaNotificationAuditSignature ?: "暂无"),
            "通知专属审计轨迹（最近 24 条）" to notificationAuditTrailSnapshot().ifBlank { "暂无通知审计事件" },
            "进度通知更新排队状态" to pendingState,
            "当前会话阶段" to state.phase.name,
            "当前播放服务是否已就绪" to playerReady.toString(),
        )
    }

    private fun updateProcessingNotification(state: PlayerUiState) {
        if (processingNotificationShuttingDown || (!playerReady && state.phase == Phase.IDLE)) return
        val delayMs = synchronized(processingNotificationUpdateLock) {
            processingNotificationUpdatePending = true
            if (processingNotificationUpdateScheduled) return
            processingNotificationUpdateScheduled = true
            nextProcessingNotificationDelayLocked()
        }
        processingNotificationHandler.postDelayed(processingNotificationUpdateRunnable, delayMs)
    }

    private fun nextProcessingNotificationDelayLocked(): Long {
        val lastPostedAt = lastProcessingNotificationPostedAtMs
        if (lastPostedAt <= 0L) return 0L
        return (lastPostedAt + PROCESSING_NOTIFICATION_MIN_INTERVAL_MS -
            android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun drainProcessingNotificationUpdate() {
        val shouldPublish = synchronized(processingNotificationUpdateLock) {
            if (!processingNotificationUpdatePending) {
                processingNotificationUpdateScheduled = false
                false
            } else {
                processingNotificationUpdatePending = false
                true
            }
        }
        if (!shouldPublish || processingNotificationShuttingDown) return

        // Read the current StateFlow value at execution time, not an older queued emission. This
        // prevents a late progress update from the previous URL replacing the new session status.
        publishProcessingNotification(ui.value)

        synchronized(processingNotificationUpdateLock) {
            if (processingNotificationUpdatePending && !processingNotificationShuttingDown) {
                processingNotificationHandler.postDelayed(
                    processingNotificationUpdateRunnable,
                    nextProcessingNotificationDelayLocked(),
                )
            } else {
                processingNotificationUpdateScheduled = false
                processingNotificationUpdatePending = false
            }
        }
    }

    /**
     * Keep CPU time available for the entire active processing pipeline, not just network
     * downloads. Local ASR/translation and speech preparation can continue with the screen off.
     * Pausing or finishing processing releases the lock to avoid unnecessary battery use.
     */
    private fun syncProcessingWakeLock(state: PlayerUiState) {
        val hasActiveWork = isProcessingPhase(state.phase) ||
            (state.phase == Phase.READY && isDubbingPreparationIncomplete(state))
        val shouldHold = !processingNotificationShuttingDown &&
            hasActiveWork &&
            !state.isProcessingPaused
        synchronized(processingWakeLockLock) {
            val existing = processingWakeLock
            if (!shouldHold) {
                if (existing?.isHeld == true) runCatching { existing.release() }
                return
            }
            if (existing?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = existing ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TranslateTube:Processing",
            ).apply {
                setReferenceCounted(false)
            }.also {
                processingWakeLock = it
            }
            // A wake-lock failure must not fail or cancel the media extraction itself.
            runCatching { lock.acquire() }
        }
    }

    private fun releaseProcessingWakeLock() {
        synchronized(processingWakeLockLock) {
            val lock = processingWakeLock
            if (lock?.isHeld == true) runCatching { lock.release() }
            processingWakeLock = null
        }
    }

    private fun promoteProcessingForeground(
        notification: Notification,
        state: PlayerUiState,
        reason: String,
    ) {
        try {
            // PlaybackService is already declared as a mediaPlayback foreground service in the
            // manifest. Using the processing notification here prevents background demotion while
            // downloading/extracting an audio track or advancing the remaining parse stages.
            startForeground(PROCESSING_NOTIFICATION_ID, notification)
            processingForegroundActive = true
            lastProcessingForegroundFailureKey = null
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            processingForegroundActive = false
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.notify(PROCESSING_NOTIFICATION_ID, notification)
            }
            val failureKey = safeDiagnosticSessionFingerprint(state.key) + "|" +
                state.phase.name + "|" + failure.javaClass.name
            if (lastProcessingForegroundFailureKey != failureKey) {
                lastProcessingForegroundFailureKey = failureKey
                io.translatetube.local.util.AppLogStore.error(
                    this,
                    "ProcessingForeground",
                    "无法将处理进度通知提升为前台服务；已回退为普通进度通知",
                    failure,
                    diagnosticContext = mapOf(
                        "调用位置" to reason,
                        "当前处理阶段" to state.phase.name,
                        "会话指纹" to safeDiagnosticSessionFingerprint(state.key),
                        "服务类型" to "mediaPlayback",
                        "异常类型" to failure.javaClass.name,
                    ),
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "ProcessingForeground",
                        failureKey,
                    ),
                )
            }
        }
    }

    private fun releaseProcessingForegroundIfSafe(state: PlayerUiState) {
        if (!processingForegroundActive) return
        val playerNeedsForeground = if (playerReady && ::pm.isInitialized) {
            val player = pm.player
            player.playWhenReady &&
                player.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                player.playbackState != androidx.media3.common.Player.STATE_ENDED
        } else {
            state.isPlaying
        }
        if (playerNeedsForeground) {
            // Media3 should keep the service in the foreground for playback.
            processingForegroundActive = false
            return
        }
        runCatching {
            stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
        }
        processingForegroundActive = false
    }

    private fun publishProcessingNotification(state: PlayerUiState) {
        syncProcessingWakeLock(state)
        if (processingNotificationShuttingDown || (!playerReady && state.phase == Phase.IDLE)) return
        val preparationIncomplete = isDubbingPreparationIncomplete(state)
        val shouldShowProcessingNotification = when (state.phase) {
            Phase.IDLE -> false
            Phase.READY -> state.waitingForTranscriptionEngine || state.waitingForDubbingEngine ||
                preparationIncomplete || state.dubbingHasErrors
            else -> true
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!shouldShowProcessingNotification) {
            latestProcessingNotification = null
            releaseProcessingForegroundIfSafe(state)
            manager.cancel(PROCESSING_NOTIFICATION_ID)
            if (lastProcessingNotificationFingerprint != null) {
                io.translatetube.local.util.AppLogStore.info(
                    this,
                    "NotificationDiagnostics",
                    "处理结束或进入空闲状态，已清除独立进度通知",
                    diagnosticContext = mapOf(
                        "通知 ID" to PROCESSING_NOTIFICATION_ID.toString(),
                        "当前处理阶段" to state.phase.name,
                        "会话指纹" to safeDiagnosticSessionFingerprint(state.key),
                        "原因" to if (state.phase == Phase.IDLE) "空闲状态" else "处理已完成且无需继续显示进度通知",
                    ),
                )
            }
            lastProcessingNotificationFingerprint = null
            lastProcessingNotificationAuditSignature = null
            return
        }
        val (title, detail) = when (state.phase) {
            Phase.PARSING -> "正在分析视频" to state.progress.ifBlank { "正在获取视频信息与字幕" }
            Phase.DOWNLOADING_AUDIO -> "正在准备音轨" to state.progress.ifBlank { "正在检查并读取视频音频" }
            Phase.TRANSCRIBING -> "正在转写原文字幕" to state.progress.ifBlank { "正在生成与视频时间轴对齐的原文字幕" }
            Phase.TRANSLATING -> "正在将原文字幕翻译成中文" to state.progress.ifBlank {
                if (state.totalLines > 0) "已翻译 ${state.translatedLineCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行" else "正在逐句翻译原文字幕"
            }
            Phase.SYNTHESIZING -> "正在按时间轴生成中文配音" to if (state.totalLines > 0) {
                "已准备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音"
            } else state.progress.ifBlank { "正在准备语音" }
            Phase.READY -> {
                val waitTitle = VideoProcessingFlowPolicy.readyNotificationWaitTitle(
                    waitingForTranscriptionEngine = state.waitingForTranscriptionEngine,
                    waitingForDubbingEngine = state.waitingForDubbingEngine,
                )
                val failureTitle = VideoProcessingFlowPolicy.readyNotificationFailureTitle(state.dubbingHasErrors)
                when {
                    waitTitle != null -> waitTitle to state.noModelHint
                        ?.takeIf { it.isNotBlank() }
                        .orEmpty()
                        .ifBlank {
                            state.progress.ifBlank {
                                "已可以播放视频；配置对应引擎后重新打开视频以继续处理"
                            }
                        }
                    preparationIncomplete ->
                        (if (state.dubbingHasErrors) "配音部分失败 · 仍在准备" else if (state.isPlaying) "播放中 · 配音准备" else "正在准备配音") to if (state.totalLines > 0) {
                            "已准备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音" +
                                if (state.dubbingHasErrors) "；失败字幕暂用原声" else ""
                        } else state.progress.ifBlank { "正在生成中文配音" }
                    failureTitle != null -> failureTitle to (
                        state.noModelHint?.takeIf { it.isNotBlank() }
                            ?: "部分字幕配音失败，相关片段已恢复原声。打开应用查看详情。"
                    )
                    state.isPlaying -> "正在播放" to state.title.ifBlank { "视频正在播放" }
                    else -> "配音已准备完成" to state.title.ifBlank { "返回应用即可查看字幕与配音" }
                }
            }
            Phase.ERROR -> "处理遇到问题" to (state.error ?: "打开应用查看详情")
            else -> "大象翻译" to "等待开始"
        }

        // Only publish determinate progress when it comes from a measurable source:
        // downloaded bytes, completed ASR segments, translated lines, or prepared TTS lines.
        // Do not use Android's indeterminate notification animation as a fake progress display.
        val measuredPercent: Int? = when (state.phase) {
            Phase.DOWNLOADING_AUDIO -> percentFromProgress(state.progress)
                ?: ratioPercentFromProgress(state.progress)
            Phase.TRANSCRIBING -> ratioPercentFromProgress(state.progress)
                ?: percentFromProgress(state.progress)
            Phase.TRANSLATING -> ratioPercentFromProgress(state.progress)
                ?: if (state.totalLines > 0) {
                    (state.translatedLineCount.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
                } else null
            Phase.SYNTHESIZING -> if (state.totalLines > 0) {
                (state.preparedCount.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
            } else ratioPercentFromProgress(state.progress)
            Phase.READY -> if (preparationIncomplete && state.totalLines > 0) {
                (state.preparedCount.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
            } else null
            else -> null
        }
        val displayDetail = if (
            measuredPercent != null &&
            !Regex("\\d+(?:\\.\\d+)?%").containsMatchIn(detail)
        ) "$detail · $measuredPercent%" else detail
        val ongoing = state.isPlaying || preparationIncomplete ||
            state.phase !in setOf(Phase.READY, Phase.ERROR, Phase.IDLE)
        val hasProcessingActions = isProcessingPhase(state.phase)
        val notificationFingerprint = listOf(
            state.phase.name,
            state.key,
            title,
            displayDetail,
            measuredPercent?.toString().orEmpty(),
            state.isProcessingPaused.toString(),
            ongoing.toString(),
            hasProcessingActions.toString(),
        ).joinToString("\\u001f")
        if (notificationFingerprint == lastProcessingNotificationFingerprint) {
            if (!isProcessingPhase(state.phase)) releaseProcessingForegroundIfSafe(state)
            return
        }

        val notification = NotificationCompat.Builder(this, CH_MAIN)
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle(if (state.isProcessingPaused) "已暂停 · $title" else title)
            .setContentText(if (state.isProcessingPaused) "已暂停 · $displayDetail" else displayDetail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayDetail))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, io.translatetube.local.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .apply {
                if (hasProcessingActions) {
                    val toggleIntent = Intent(this@PlaybackService, PlaybackService::class.java)
                        .setAction(ACTION_TOGGLE_PROCESSING_PAUSE)
                        .setData(Uri.parse("translate-tube://processing/pause/" + Uri.encode(state.key)))
                        .putExtra(EXTRA_PROCESSING_SESSION_KEY, state.key)
                    val processingPendingFlags =
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val togglePending = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(this@PlaybackService, 1002, toggleIntent, processingPendingFlags)
                    } else {
                        PendingIntent.getService(this@PlaybackService, 1002, toggleIntent, processingPendingFlags)
                    }
                    addAction(
                        if (state.isProcessingPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                        if (state.isProcessingPaused) "继续" else "暂停",
                        togglePending,
                    )
                    val cancelIntent = Intent(this@PlaybackService, PlaybackService::class.java)
                        .setAction(ACTION_CANCEL_PROCESSING)
                        .setData(Uri.parse("translate-tube://processing/cancel/" + Uri.encode(state.key)))
                        .putExtra(EXTRA_PROCESSING_SESSION_KEY, state.key)
                    val cancelPending = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(this@PlaybackService, 1003, cancelIntent, processingPendingFlags)
                    } else {
                        PendingIntent.getService(this@PlaybackService, 1003, cancelIntent, processingPendingFlags)
                    }
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
                }
                // Always replace or clear the previous stage's progress state.
                // Only measurable work receives a percentage; other phases show no stale bar.
                if (measuredPercent != null) {
                    setProgress(100, measuredPercent.coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
        rememberNotificationAudit(
            "BEFORE processing notice id=" + PROCESSING_NOTIFICATION_ID + " phase=" + state.phase.name +
                " actions=" + (if (hasProcessingActions) 2 else 0) +
                " determinateProgress=" + (measuredPercent != null) +
                " session=" + safeDiagnosticSessionFingerprint(state.key),
        )
        if (isProcessingPhase(state.phase)) {
            latestProcessingNotification = notification
            promoteProcessingForeground(notification, state, "处理进度通知更新")
        } else {
            latestProcessingNotification = null
            releaseProcessingForegroundIfSafe(state)
            try {
                manager.notify(PROCESSING_NOTIFICATION_ID, notification)
            } catch (failure: Throwable) {
                runCatching {
                    io.translatetube.local.util.AppLogStore.error(
                        this,
                        "NotificationDiagnostics",
                        "提交处理进度通知时同步抛出异常",
                        failure,
                        diagnosticContext = mapOf(
                            "通知 ID" to PROCESSING_NOTIFICATION_ID.toString(),
                            "通知样式" to "NotificationCompat.BigTextStyle",
                            "当前处理阶段" to state.phase.name,
                            "会话指纹" to safeDiagnosticSessionFingerprint(state.key),
                            "是否包含暂停/取消操作" to hasProcessingActions.toString(),
                            "是否包含进度条" to (measuredPercent != null).toString(),
                            "提交线程" to Thread.currentThread().name,
                        ),
                    )
                }
                throw failure
            }
        }
        lastProcessingNotificationFingerprint = notificationFingerprint
        lastProcessingNotificationPostedAtMs = android.os.SystemClock.elapsedRealtime()
        processingNotificationPostCount += 1L
        rememberNotificationAudit(
            "AFTER processing notice id=" + PROCESSING_NOTIFICATION_ID + " phase=" + state.phase.name +
                " count=" + processingNotificationPostCount + " session=" + safeDiagnosticSessionFingerprint(state.key),
        )

        // Audit only layout/state transitions, not every counter tick. This creates a compact
        // timeline that can be matched to RemoteServiceException's notification tag/id.
        val auditSignature = listOf(
            safeDiagnosticSessionFingerprint(state.key),
            state.phase.name,
            ongoing.toString(),
            hasProcessingActions.toString(),
            (measuredPercent != null).toString(),
            state.isProcessingPaused.toString(),
        ).joinToString("|")
        if (auditSignature != lastProcessingNotificationAuditSignature) {
            lastProcessingNotificationAuditSignature = auditSignature
            io.translatetube.local.util.AppLogStore.info(
                this,
                "NotificationDiagnostics",
                "处理进度通知状态已提交（布局/阶段变更）",
                diagnosticContext = mapOf(
                    "通知 ID" to PROCESSING_NOTIFICATION_ID.toString(),
                    "通知用途" to "视频解析/转写/翻译/配音进度，不是 Media3 媒体控制通知",
                    "通知样式" to "NotificationCompat.BigTextStyle",
                    "当前处理阶段" to state.phase.name,
                    "会话类型" to diagnosticMediaSourceType(state),
                    "会话指纹" to safeDiagnosticSessionFingerprint(state.key),
                    "是否 ongoing" to ongoing.toString(),
                    "操作按钮数" to if (hasProcessingActions) "2" else "0",
                    "是否有确定进度条" to (measuredPercent != null).toString(),
                    "是否已暂停处理" to state.isProcessingPaused.toString(),
                    "本次服务进程内成功提交序号" to processingNotificationPostCount.toString(),
                    "提交线程" to Thread.currentThread().name,
                ),
            )
        }
    }

    private fun percentFromProgress(progress: String): Int? {
        val match = Regex("(\\d+(?:\\.\\d+)?)%").find(progress) ?: return null
        return match.groupValues[1].toFloatOrNull()?.toInt()?.coerceIn(0, 100)
    }

    private fun ratioPercentFromProgress(progress: String): Int? {
        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(progress) ?: return null
        val current = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        if (total <= 0) return null
        return (current * 100f / total).toInt().coerceIn(0, 100)
    }

    /** Stable per-session correlation ID without persisting the original URL or local file path. */
    private fun safeDiagnosticSessionFingerprint(sessionKey: String): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(sessionKey.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrDefault("unavailable")

    /** Normalize changing counters/percentages so progress ticks do not flood the diagnostic log. */
    private fun progressLogSignature(progress: String): String = progress
        .replace(Regex("\\d+(?:\\.\\d+)?\\s*%"), "%")
        .replace(Regex("\\d+\\s*/\\s*\\d+"), "#/#")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), "#")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(160)

    private fun rememberNotificationAudit(event: String) {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val line = ("uptimeMs=" + elapsed + " " + event).take(320)
        synchronized(notificationAuditLock) {
            recentNotificationAuditTrail.addLast(line)
            while (recentNotificationAuditTrail.size > MAX_NOTIFICATION_AUDIT_EVENTS) {
                recentNotificationAuditTrail.removeFirst()
            }
        }
    }

    private fun notificationAuditTrailSnapshot(): String = synchronized(notificationAuditLock) {
        recentNotificationAuditTrail.joinToString(separator = " || ")
    }

    /**
     * Media3 owns the media-control notification (default ID 1001). Record its state transitions
     * separately from our processing-progress notification (ID 1004), so a future system-side
     * RemoteServiceException can be correlated to the notification being refreshed at that time.
     */
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val player = session.player
        val state = ui.value
        val sessionFingerprint = state.key.takeIf { it.isNotBlank() }
            ?.let(::safeDiagnosticSessionFingerprint).orEmpty().ifBlank { "no-active-session" }
        val signature = listOf(
            player.playbackState.toString(),
            player.playWhenReady.toString(),
            player.isPlaying.toString(),
            startInForegroundRequired.toString(),
            state.phase.name,
            sessionFingerprint,
        ).joinToString("|")
        val changed = signature != lastMediaNotificationAuditSignature
        val updateNumber = mediaNotificationUpdateCount + 1L
        val event = "Media3 media notice update #" + updateNumber + " id=1001 phase=" + state.phase.name +
            " playbackState=" + player.playbackState + " playWhenReady=" + player.playWhenReady +
            " isPlaying=" + player.isPlaying + " foregroundRequired=" + startInForegroundRequired +
            " session=" + sessionFingerprint
        rememberNotificationAudit("BEFORE " + event)

        if (changed) {
            io.translatetube.local.util.AppLogStore.info(
                this,
                "NotificationDiagnostics",
                "Media3 即将更新媒体控制通知",
                diagnosticContext = mapOf(
                    "通知 ID" to "1001（Media3 默认媒体通知 ID）",
                    "通知用途" to "媒体控制/播放状态",
                    "Media3 更新序号" to updateNumber.toString(),
                    "播放器状态码" to player.playbackState.toString(),
                    "playWhenReady" to player.playWhenReady.toString(),
                    "播放器 isPlaying" to player.isPlaying.toString(),
                    "是否需要前台服务" to startInForegroundRequired.toString(),
                    "当前处理阶段" to state.phase.name,
                    "会话指纹" to sessionFingerprint,
                    "当前线程" to Thread.currentThread().name,
                ),
            )
        }
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (failure: Throwable) {
            runCatching {
                io.translatetube.local.util.AppLogStore.error(
                    this,
                    "NotificationDiagnostics",
                    "Media3 更新媒体控制通知时同步抛出异常",
                    failure,
                    diagnosticContext = mapOf(
                        "通知 ID" to "1001（Media3 默认媒体通知 ID）",
                        "Media3 更新序号" to updateNumber.toString(),
                        "播放器状态码" to player.playbackState.toString(),
                        "是否需要前台服务" to startInForegroundRequired.toString(),
                        "当前处理阶段" to state.phase.name,
                        "会话指纹" to sessionFingerprint,
                    ),
                )
            }
            throw failure
        }
        // Media3 may demote this service when the video is not actively playing. Processing is
        // independent of the visible Activity, so restore the progress notification as the FGS
        // notification after Media3 has finished updating its own media-control notification.
        val currentProcessingState = ui.value
        syncProcessingWakeLock(currentProcessingState)
        if (isProcessingPhase(currentProcessingState.phase)) {
            val processingNotice = latestProcessingNotification
            if (processingNotice != null) {
                promoteProcessingForeground(
                    processingNotice,
                    currentProcessingState,
                    "Media3 媒体通知更新后恢复前台状态",
                )
            } else {
                publishProcessingNotification(currentProcessingState)
            }
        } else if (startInForegroundRequired) {
            // Media3 now owns foreground status for active playback.
            processingForegroundActive = false
        } else {
            releaseProcessingForegroundIfSafe(currentProcessingState)
        }

        mediaNotificationUpdateCount = updateNumber
        lastMediaNotificationUpdatedAtMs = android.os.SystemClock.elapsedRealtime()
        lastMediaNotificationAuditSignature = signature
        rememberNotificationAudit("AFTER Media3 media notice update #" + updateNumber + " id=1001 returned")
        if (changed) {
            io.translatetube.local.util.AppLogStore.info(
                this,
                "NotificationDiagnostics",
                "Media3 媒体控制通知更新调用已返回",
                diagnosticContext = mapOf(
                    "通知 ID" to "1001（Media3 默认媒体通知 ID）",
                    "Media3 更新序号" to updateNumber.toString(),
                    "当前处理阶段" to state.phase.name,
                    "会话指纹" to sessionFingerprint,
                    "当前线程" to Thread.currentThread().name,
                ),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Prevent a canceled pipeline from publishing progress/results into a newer video session. */
    private fun updateUiForSession(
        sessionKey: String,
        pipelineGeneration: Long? = null,
        dubbingMutationGeneration: Long? = null,
        transform: (PlayerUiState) -> PlayerUiState,
    ) {
        val publish = {
            ui.update { current ->
                if (
                    !PlaybackSessionGuard.isCurrent(
                        sessionKey = sessionKey,
                        activeVideoKey = videoKey,
                        activeUiKey = current.key,
                        expectedPipelineGeneration = pipelineGeneration,
                        activePipelineGeneration = activePipelineGeneration,
                        expectedDubbingMutationGeneration = dubbingMutationGeneration,
                        activeDubbingMutationGeneration = activeDubbingMutationGeneration,
                    )
                ) {
                    current
                } else {
                    val updated = transform(current)
                    when {
                        updated.phase == Phase.ERROR && current.phase != Phase.ERROR ->
                            updated.copy(failedPhase = current.phase, failedProgress = current.progress)
                        updated.phase != Phase.ERROR ->
                            updated.copy(failedPhase = null, failedProgress = "")
                        else -> updated
                    }
                }
            }
        }
        val before = ui.value
        if (pipelineGeneration == null) {
            publish()
        } else {
            // Serialize generation changes with publication so an old same-key task cannot
            // slip a final progress/error update into the newly opened session.
            synchronized(pipelineGenerationLock) {
                if (pipelineGeneration != activePipelineGeneration) return
                publish()
            }
        }

        val after = ui.value
        if (after.key == sessionKey) {
            val phaseChanged = before.phase != after.phase
            val stepChanged = after.progress.isNotBlank() &&
                progressLogSignature(before.progress) != progressLogSignature(after.progress)
            if (phaseChanged || stepChanged) {
                val operation = if (phaseChanged) {
                    "处理阶段变更：${before.phase.name} -> ${after.phase.name}"
                } else {
                    "处理步骤：${after.progress.take(160)}"
                }
                val contextSnapshot = mapOf(
                    "前一阶段" to before.phase.name,
                    "当前阶段" to after.phase.name,
                    "当前处理步骤" to after.progress.ifBlank { after.failedProgress }.take(200),
                    "出错前阶段" to after.failedPhase?.name.orEmpty().ifBlank { "未记录" },
                    "当前错误提示" to after.error.orEmpty().take(200),
                    "翻译引擎" to after.engineName.ifBlank { "未确定" },
                    "配音引擎" to after.ttsEngineName.ifBlank { "未确定" },
                    "源语言" to after.sourceLang.ifBlank { "未确定" },
                    "字幕已发布行数" to after.lines.size.toString(),
                    "字幕总行数" to after.totalLines.toString(),
                    "已准备配音行数" to after.preparedCount.toString(),
                    "当前字幕索引" to after.currentLine.toString(),
                    "等待转写引擎" to after.waitingForTranscriptionEngine.toString(),
                    "等待配音引擎" to after.waitingForDubbingEngine.toString(),
                    "处理代数" to (pipelineGeneration?.toString() ?: "未指定"),
                    "视频来源" to when {
                        sessionKey.startsWith("youtube:") -> "YouTube"
                        sessionKey.startsWith("remote:") -> "网页视频"
                        sessionKey.startsWith("webpage:") -> "网页"
                        sessionKey.startsWith("local:") -> "本地视频"
                        else -> "其他来源"
                    },
                    "视频会话指纹" to safeDiagnosticSessionFingerprint(sessionKey),
                    "播放器初始化状态" to playerReady.toString(),
                )
                // File I/O must not run on the UI thread; the event is queued on the service scope.
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val statePrefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val sourceKind = when {
                        after.key.startsWith("youtube:") -> "youtube"
                        after.key.startsWith("remote:") -> "web"
                        after.key.startsWith("local:") -> "local"
                        after.key.startsWith("webpage:") -> "webpage"
                        else -> "other"
                    }
                    val compactProcessState = listOf(
                        "p=${after.phase.name}",
                        "src=$sourceKind",
                        "tts=${statePrefs.getString("tts_engine", "unknown") ?: "unknown"}",
                        "api=${statePrefs.getLong("active_api_id", -1L)}",
                        "tr=${after.translatedLineCount}/${after.totalLines}",
                        "dub=${after.preparedCount}",
                        "pause=${if (after.isProcessingPaused) 1 else 0}",
                        "play=${if (after.isPlaying) 1 else 0}",
                        "err=${if (after.phase == Phase.ERROR) 1 else 0}",
                    ).joinToString(";")
                    // This platform API is intentionally updated only when the processing
                    // phase changes. The system may throttle frequent writes; detailed step logs
                    // remain in the local file log and don't need to be sent to ActivityManager.
                    if (phaseChanged) {
                        io.translatetube.local.util.AppLogStore.updateProcessStateSummary(
                            this@PlaybackService,
                            compactProcessState,
                        )
                    }
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "PlaybackTrace",
                        operation,
                        diagnosticContext = contextSnapshot,
                    )
                }
            }
        }
    }

    private fun isCurrentDubbingEngine(engine: DubbingEngine, sessionKey: String): Boolean =
        videoKey == sessionKey && ui.value.key == sessionKey && dubbing === engine

    /** Publish an initialized engine only if the workflow still owns the active session. */
    private fun publishDubbingEngine(
        engine: DubbingEngine,
        sessionKey: String,
        pipelineGeneration: Long,
        mutationGeneration: Long? = null,
    ): Boolean = synchronized(pipelineGenerationLock) {
        if (
            !PlaybackSessionGuard.isCurrent(
                sessionKey = sessionKey,
                activeVideoKey = videoKey,
                activeUiKey = ui.value.key,
                expectedPipelineGeneration = pipelineGeneration,
                activePipelineGeneration = activePipelineGeneration,
                expectedDubbingMutationGeneration = mutationGeneration,
                activeDubbingMutationGeneration = activeDubbingMutationGeneration,
            )
        ) {
            false
        } else {
            dubbing = engine
            cancelDubbingDrainAfterVideoEnd()
            clearDubbingCatchUpQueue(
                sessionKey = sessionKey,
                positionMs = if (::pm.isInitialized) pm.position() else -1L,
            )
            true
        }
    }

    /** Ignore late callbacks from a replaced engine, including when the same video key is reused. */
    private fun updateUiForCurrentDubbingEngine(
        engine: DubbingEngine,
        sessionKey: String,
        transform: (PlayerUiState) -> PlayerUiState,
    ) {
        ui.update { current ->
            if (current.key == sessionKey && videoKey == sessionKey && dubbing === engine) {
                transform(current)
            } else {
                current
            }
        }
    }

    override fun onDestroy() {
        processingNotificationShuttingDown = true
        releaseProcessingWakeLock()
        latestProcessingNotification = null
        processingForegroundActive = false
        processingNotificationHandler.removeCallbacks(processingNotificationUpdateRunnable)
        synchronized(processingNotificationUpdateLock) {
            processingNotificationUpdatePending = false
            processingNotificationUpdateScheduled = false
        }
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(PROCESSING_NOTIFICATION_ID)
        }
        enginePreferenceRestartJob?.cancel()
        enginePreferenceRestartJob = null
        enginePreferencesListener?.let { listener ->
            runCatching {
                getSharedPreferences("settings", MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
        enginePreferencesListener = null
        // Cancel work before releasing services it may still be using. In particular, the
        // local ASR JNI recognizer must not be released while a synchronous decode call is active.
        cancelDubbingDrainAfterVideoEnd()
        val activePipeline = prepJob
        prepJob?.cancel()
        speakerJob?.cancel()
        qualityJob?.cancel()
        qualityRecoveryJob?.cancel()
        dubCollectJob?.cancel()
        scope.cancel()

        runCatching { retireCurrentDubbing() }
        // Previously retired engines have already received release(); their barriers remain
        // registered until a live processing task can join them.
        if (activePipeline != null && !activePipeline.isCompleted) {
            // Cancellation is cooperative around blocking JNI calls. Close the recognizer only
            // after the owning pipeline has completed. Do cleanup off-main because older canceled
            // jobs may still be returning from a synchronous native decode.
            activePipeline.invokeOnCompletion { releaseLocalAsrOffMainThread() }
        } else {
            releaseLocalAsrOffMainThread()
        }
        runCatching { session?.release() }
        if (::pm.isInitialized) runCatching { pm.release() }
        playerReady = false
        if (instance === this) instance = null
        super.onDestroy()
    }


    private fun shouldReleaseLocalAsrForMemoryPressure(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        return AsrMemoryPolicy.shouldReleaseRecognizer(
            isLowRamDevice = manager.isLowRamDevice,
            memoryClassMb = manager.memoryClass,
            systemLowMemory = memoryInfo.lowMemory,
            availableMemoryBytes = memoryInfo.availMem,
            totalMemoryBytes = memoryInfo.totalMem,
        )
    }

    private fun releaseLocalAsrOffMainThread() {
        Thread(
            { releaseLocalAsr() },
            "tt-local-asr-release",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseLocalAsr() {
        val engine = localAsrEngine
        localAsrEngine = null
        localAsrEngineModelId = null
        runCatching { engine?.close() }
            .onFailure { android.util.Log.w("PlaybackService", "Local ASR recognizer cleanup failed", it) }
    }

    /**
     * Local ASR recognizers hold native model memory after transcription. Release that memory
     * before constructing a TTS engine: replaying a cached video can reach synthesis almost
     * immediately, while the previous Whisper/SenseVoice session is still resident in this
     * long-lived service. This shared path covers YouTube, web video, local video and imports.
     */
    private suspend fun releaseLocalAsrBeforeDubbing(
        sessionKey: String,
        pipelineGeneration: Long,
    ) {
        withContext(Dispatchers.IO) {
            localAsrMutex.withLock {
                ensureCurrentSession(sessionKey, pipelineGeneration)
                if (localAsrEngine != null) {
                    releaseLocalAsr()
                    android.util.Log.i(
                        "PlaybackService",
                        "Released local ASR model before TTS initialization for session=$sessionKey",
                    )
                }
            }
        }
    }

    // ============ 管线入口 ============
    fun open(
        key: String,
        videoId: String?,
        localUri: Uri?,
        importedLines: List<SubLine>? = null,
        resumeState: PlayerUiState? = null,
        remoteMediaUrl: String? = null,
        remoteMediaTitle: String = "",
        remoteMediaReferrer: String = "",
        remoteMediaCookie: String? = null,
        remoteMediaUserAgent: String? = null,
        requestToken: Long = 0L,
        reuseMountedMediaForRestart: Boolean = false,
        restoreReadySnapshot: Boolean = false,
        forceRegenerateDubbing: Boolean = false,
    ) {
        val canRestoreStoredResult = resumeState == null && importedLines == null && key.isNotBlank()
        // A matching snapshot may belong to an older run of the current configuration. Compare
        // against the latest successful run first; if any engine domain changed, restart from the
        // latest successful snapshot instead of treating that older matching snapshot as "unchanged".
        val engineConfigChangedSinceLastSuccess =
            ReadyHistorySessionConfigStore.hasEngineConfigChangedSinceLastSuccess(this, key)
        // A matching snapshot may be from a configuration used before the latest run. It is still
        // reusable when the user switches back to that exact successful engine combination.
        val reusableSnapshot = if (canRestoreStoredResult) {
            ReadySessionSnapshotStore.findForCurrentConfig(this, key) { ignore ->
                ReadyHistorySessionConfigStore.fingerprint(this, ignoreTranscription = ignore)
            }
        } else null
        val fallbackSnapshot = if (canRestoreStoredResult && reusableSnapshot == null) {
            ReadySessionSnapshotStore.findLatestForVideo(this, key)
        } else null
        val fallbackPreviousDomains = fallbackSnapshot?.let { snapshot ->
            val hasSnapshotFingerprints = snapshot.transcriptionFingerprint.isNotBlank() &&
                snapshot.translationFingerprint.isNotBlank() &&
                snapshot.dubbingFingerprint.isNotBlank()
            when {
                hasSnapshotFingerprints -> EngineConfigFingerprints(
                    transcription = snapshot.transcriptionFingerprint,
                    translation = snapshot.translationFingerprint,
                    dubbing = snapshot.dubbingFingerprint,
                )
                ReadyHistorySessionConfigStore.savedCompositeFingerprint(this, key) == snapshot.fingerprint ->
                    ReadyHistorySessionConfigStore.savedFingerprints(this, key)
                else -> null
            }
        }
        val fallbackCurrentDomains = fallbackSnapshot?.let {
            ReadyHistorySessionConfigStore.fingerprints(this)
        }
        val fallbackRestartStage = if (
            fallbackSnapshot != null &&
            fallbackPreviousDomains != null &&
            fallbackCurrentDomains != null
        ) {
            chooseEngineRestartStage(
                previous = fallbackPreviousDomains,
                current = fallbackCurrentDomains,
                state = fallbackSnapshot.state,
                ignoreTranscription = fallbackSnapshot.ignoreTranscription,
            )
        } else null
        val fallbackCanBeCompared = fallbackPreviousDomains != null && fallbackCurrentDomains != null
        val fallbackResumeState = when {
            fallbackSnapshot == null || !fallbackCanBeCompared -> null
            fallbackRestartStage != null ->
                stateForEngineRestart(fallbackSnapshot.state, fallbackRestartStage)
            else -> fallbackSnapshot.state
        }
        val effectiveResumeState = resumeState ?: reusableSnapshot?.state ?: fallbackResumeState
        val restoreCompletedSnapshot = restoreReadySnapshot ||
            reusableSnapshot != null ||
            (!engineConfigChangedSinceLastSuccess &&
                fallbackSnapshot != null && fallbackCanBeCompared && fallbackRestartStage == null)
        // Freshly process a genuinely new configuration. If this is an exact historical success,
        // reuse that configuration's own speech cache instead; restoreReadySnapshot also marks
        // callers that explicitly hand in a verified, exact-match success snapshot.
        val regenerateDubbingThisRun = forceRegenerateDubbing ||
            (!restoreReadySnapshot && reusableSnapshot == null && (
                fallbackRestartStage != null ||
                    engineConfigChangedSinceLastSuccess ||
                    (fallbackSnapshot != null && !fallbackCanBeCompared)
                ))
        // Keep the current source mounted during a same-video stage restart. In particular,
        // do not reopen a time-limited webpage HLS URL merely to regenerate subtitles or speech.
        val reuseMountedMedia = (effectiveResumeState != null || reuseMountedMediaForRestart) &&
            key == videoKey && ui.value.key == key && playerReady && ::pm.isInitialized
        // Invalidate prior work even when the same History row reopens the same video key.
        val pipelineGeneration = beginPipelineGeneration()
        if (!playerReady || !::pm.isInitialized) {
            ui.update {
                it.copy(phase = Phase.ERROR, progress = "", error = it.error ?: "播放器服务未初始化成功，请返回主页后重试")
            }
            return
        }
        for (oldKey in manualTranslationRequestedKeys.toList()) {
            if (oldKey != key) manualTranslationRequestedKeys.remove(oldKey)
        }
        for (oldKey in manualTranslationWaiters.keys.toList()) {
            if (oldKey != key) {
                manualTranslationWaiters.remove(oldKey)?.cancel()
                manualTranslationSources.remove(oldKey)
            }
        }
        processingEngineConfigFingerprint = processingEnginePreferenceFingerprint()
        processingPaused = false
        if (processingPauseGate.isLocked) runCatching { processingPauseGate.unlock() }
        val previousPrepJob = prepJob
        previousPrepJob?.cancel()
        subtitleDownloadJob?.cancel(); subtitleDownloadJob = null
        speakerJob?.cancel(); speakerJob = null
        dubCollectJob?.cancel(); dubCollectJob = null
        qualityRecoveryJob?.cancel(); qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        retireCurrentDubbing()
        this.videoKey = key; this.videoId = videoId; this.localUri = localUri
        this.remoteMediaUrl = remoteMediaUrl
        this.remoteMediaTitle = remoteMediaTitle
        this.remoteMediaReferrer = remoteMediaReferrer
        this.remoteMediaUserAgent = remoteMediaUserAgent.orEmpty()
        if (!reuseMountedMedia) adaptiveQualitySourceReady = false
        // Publish the request token after its associated media identity.
        activeOpenRequestToken = requestToken
        // 兜底防护：blob:/data: 等网页内嵌地址绝不能进入 ExoPlayer（会以
        // PARSING_CONTAINER_UNSUPPORTED 失败甚至崩溃），必须在此拦截并给出可操作的提示。
        if (remoteMediaUrl != null && !remoteMediaUrl.startsWith("http://", true) &&
            !remoteMediaUrl.startsWith("https://", true)
        ) {
            ui.update {
                it.copy(
                    phase = Phase.ERROR,
                    progress = "",
                    error = "该视频使用网页内嵌流（blob/MSE）播放，无法直接播放或提取音频。请回到「网页视频」页等待检测到可访问的视频地址，或点击「用 yt-dlp 解析本页视频」重试。"
                )
            }
            return
        }
        this.remoteMediaCookie = remoteMediaCookie
        val importedLinesSnapshot = importedLines?.toList()
        this.importedLines = importedLinesSnapshot
        // Preserve parsed metadata/subtitles when resuming a translation failure.
        if (videoId != null && effectiveResumeState == null) NewPipeRepo.invalidate(videoId)
        qualityJob?.cancel()
        if (!reuseMountedMedia) {
            qualitySources = emptyList()
            qualityAudioUrl = ""
        }
        lastLineIdx = Int.MIN_VALUE
        val configuredTts = getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "edge") ?: "edge"
        val activeTranslationId = getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)
        // Match the session isolation used by the downloaded-audio cache. A signed URL can
        // serve different private media to different cookie sessions or page origins.
        val cacheKeyBase = remoteMediaUrl?.let {
            MediaUrlCacheIdentity.remoteSessionKey(it, remoteMediaReferrer, remoteMediaCookie)
        } ?: key
        val effectiveTranslationCacheKey = TranslationCacheIdentity.forProvider(
            baseKey = cacheKeyBase,
            activeId = activeTranslationId,
            importedLines = importedLines,
            previousKey = effectiveResumeState?.translationCacheKey,
        )
        val configuredTtsName = ttsDisplayName(configuredTts)
        val audioPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val originalVolume = audioPrefs.getFloat(PREF_PLAYER_ORIGINAL_VOLUME, 1f).coerceIn(0f, 1f)
        val dubbingVolume = audioPrefs.getFloat(PREF_PLAYER_DUBBING_VOLUME, 1f).coerceIn(0f, 1f)
        ui.value = PlayerUiState(
            phase = when {
                restoreCompletedSnapshot -> Phase.READY
                effectiveResumeState == null || effectiveResumeState.lines.isEmpty() -> Phase.PARSING
                !isChineseLanguage(effectiveResumeState.sourceLang) &&
                    effectiveResumeState.lines.any { needsSubtitleTranslation(it, effectiveResumeState.sourceLang) } ->
                    Phase.TRANSLATING
                else -> Phase.SYNTHESIZING
            },
            processingFlow = effectiveResumeState?.processingFlow ?: when {
                !importedLines.isNullOrEmpty() -> ProcessingFlow.IMPORTED_SUBTITLES
                videoId != null -> ProcessingFlow.AUTO
                remoteMediaUrl != null -> ProcessingFlow.WEB_SPEECH_RECOGNITION
                localUri != null -> ProcessingFlow.LOCAL_SPEECH_RECOGNITION
                else -> ProcessingFlow.AUTO
            },
            isProcessingPaused = false,
            key = key,
            progress = when {
                restoreCompletedSnapshot -> ""
                effectiveResumeState == null -> "解析中…"
                effectiveResumeState.lines.isEmpty() -> "复用现有视频资源，准备重新转写…"
                !isChineseLanguage(effectiveResumeState.sourceLang) &&
                    effectiveResumeState.lines.any { needsSubtitleTranslation(it, effectiveResumeState.sourceLang) } ->
                    "恢复已获取的原文字幕，继续翻译…"
                else -> "原文与中文字幕已就绪，正在准备配音…"
            },
            title = effectiveResumeState?.title.orEmpty(),
            channel = effectiveResumeState?.channel.orEmpty(),
            thumbUrl = effectiveResumeState?.thumbUrl,
            durationMs = effectiveResumeState?.durationMs ?: 0L,
            lines = effectiveResumeState?.lines ?: emptyList(),
            totalLines = effectiveResumeState?.totalLines ?: 0,
            // The dubbing timeline remains active for background preparation even at 0% output.
            dubbingOn = true,
            muteOriginal = originalVolume <= 0f,
            originalVolume = originalVolume,
            dubbingVolume = dubbingVolume,
            audioOnly = effectiveResumeState?.audioOnly ?: false,
            videoAspect = effectiveResumeState?.videoAspect ?: (16f / 9f),
            qualityOptions = effectiveResumeState?.qualityOptions ?: emptyList(),
            selectedQuality = effectiveResumeState?.selectedQuality.orEmpty(),
            availableSubtitleTracks = effectiveResumeState?.availableSubtitleTracks ?: emptyList(),
            subtitleTrackStatus = effectiveResumeState?.subtitleTrackStatus.orEmpty(),
            sourceLang = effectiveResumeState?.sourceLang.orEmpty(),
            engineName = effectiveResumeState?.engineName.orEmpty(),
            translationCacheKey = effectiveTranslationCacheKey,
            ttsEngineName = configuredTtsName,
            manualTranslationModeEnabled = manualTranslationRequestedKeys.contains(key),
            manualTranslationWaiting = false,
        )
        if (::pm.isInitialized) {
            pm.originalVolume = originalVolume
            pm.dubbingVolume = dubbingVolume
            pm.dubbingBus.setDubbingEnabled(ui.value.dubbingOn)
        }
        io.translatetube.local.util.AppLogStore.info(
            this@PlaybackService,
            "PlaybackFlow",
            if (restoreCompletedSnapshot) "恢复该视频已成功的配置结果" else if (effectiveResumeState != null) "恢复历史任务并重新进入处理管线" else "开始新的视频处理任务",
            diagnosticContext = mapOf(
                "视频来源" to when {
                    !importedLinesSnapshot.isNullOrEmpty() -> "导入字幕"
                    videoId != null -> "YouTube"
                    remoteMediaUrl != null -> "网页视频/远程媒体"
                    localUri != null -> "本地视频"
                    else -> "未知来源"
                },
                "初始阶段" to if (restoreCompletedSnapshot) Phase.READY.name else if (effectiveResumeState != null) Phase.TRANSLATING.name else Phase.PARSING.name,
                "处理流程" to ui.value.processingFlow.name,
                "本次处理代数" to pipelineGeneration.toString(),
                "播放请求标记" to requestToken.toString(),
                "是否使用历史结果恢复" to (effectiveResumeState != null).toString(),
                "是否直接恢复完整成功会话" to restoreCompletedSnapshot.toString(),
                "是否发现旧配置成功快照" to (fallbackSnapshot != null).toString(),
                "跨重启恢复阶段" to when {
                    reusableSnapshot != null -> "完整成功结果：跳过前四阶段"
                    fallbackSnapshot == null -> "没有可恢复成功快照"
                    fallbackRestartStage != null -> fallbackRestartStage.name
                    fallbackCanBeCompared -> "引擎配置未变化：恢复完整成功结果"
                    else -> "旧快照缺少配置指纹：安全地重新解析"
                },
                "自上次成功处理后引擎配置是否变化" to engineConfigChangedSinceLastSuccess.toString(),
                "本次是否强制重新合成中文配音" to regenerateDubbingThisRun.toString(),
                "恢复前字幕行数" to (effectiveResumeState?.lines?.size ?: 0).toString(),
                "已导入字幕行数" to (importedLinesSnapshot?.size ?: 0).toString(),
                "恢复的源语言" to effectiveResumeState?.sourceLang.orEmpty().ifBlank { "未确定" },
                "本次选择的配音引擎" to configuredTtsName,
                "翻译服务配置编号" to activeTranslationId.toString(),
            ),
        )
        pm.pause()
        prepJob = scope.launch {
            pipelineExecutionMutex.withLock {
                ensureCurrentSession(key, pipelineGeneration)
                runPipeline(
                    key = key,
                    pipelineGeneration = pipelineGeneration,
                    requestedVideoId = videoId,
                    localUri = localUri,
                    resumeState = effectiveResumeState,
                    sourceImportedLines = importedLinesSnapshot,
                    requestedRemoteMediaUrl = remoteMediaUrl,
                    requestedRemoteMediaTitle = remoteMediaTitle,
                    requestedRemoteMediaReferrer = remoteMediaReferrer,
                    requestedRemoteMediaCookie = remoteMediaCookie,
                    reuseMountedMedia = reuseMountedMedia,
                    restoreCompletedSnapshot = restoreCompletedSnapshot,
                    forceRegenerateDubbing = regenerateDubbingThisRun,
                )
                ensureCurrentSession(key, pipelineGeneration)
                val finishedState = ui.value
                if (
                    finishedState.key == key &&
                    finishedState.phase == Phase.READY &&
                    !finishedState.waitingForTranscriptionEngine &&
                    !finishedState.waitingForDubbingEngine
                ) {
                    // Only a YouTube subtitle-based run is independent of the ASR configuration.
                    // No-caption YouTube runs are marked YOUTUBE_SPEECH_RECOGNITION and must
                    // include the selected transcription engine in history compatibility checks.
                    val ignoreTranscription =
                        key.startsWith("youtube:", ignoreCase = true) &&
                            finishedState.processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES
                    val completedFingerprint = processingEnginePreferenceFingerprint(
                        ignoreTranscription = ignoreTranscription,
                    )
                    val completedDomainFingerprints = ReadyHistorySessionConfigStore.fingerprints(this@PlaybackService)
                    val translationsComplete = isChineseLanguage(finishedState.sourceLang) ||
                        finishedState.lines.none { needsSubtitleTranslation(it, finishedState.sourceLang) }
                    if (
                        finishedState.lines.isNotEmpty() &&
                        finishedState.dubbingOn &&
                        !finishedState.dubbingHasErrors &&
                        !finishedState.waitingForTranscriptionEngine &&
                        !finishedState.waitingForDubbingEngine &&
                        translationsComplete
                    ) {
                        ReadySessionSnapshotStore.save(
                            context = this@PlaybackService,
                            videoKey = key,
                            fingerprint = completedFingerprint,
                            ignoreTranscription = ignoreTranscription,
                            state = finishedState,
                            transcriptionFingerprint = completedDomainFingerprints.transcription,
                            translationFingerprint = completedDomainFingerprints.translation,
                            dubbingFingerprint = completedDomainFingerprints.dubbing,
                        )
                    }
                    completedReadySessionKey = key
                    completedReadySessionIgnoresTranscription = ignoreTranscription
                    completedReadySessionEngineFingerprint = completedFingerprint
                    ReadyHistorySessionConfigStore.save(
                        this@PlaybackService,
                        key,
                        completedFingerprint,
                        ignoreTranscription,
                        completedDomainFingerprints,
                        finishedState.processingFlow,
                    )
                }
            }
        }
    }

    /**
     * HLS/DASH 和其他多轨媒体由 Media3 管理清晰度。只暴露实际存在的多分辨率轨道，
     * 避免把普通单分辨率 MP4 误显示成可切换画质。
     */
    private fun refreshAdaptiveVideoQualityOptions() {
        if (!::pm.isInitialized || !adaptiveQualitySourceReady || videoId != null) return
        if (remoteMediaUrl == null && localUri == null) return

        val stateBefore = ui.value
        if (stateBefore.key != videoKey) return
        val actualOptions = pm.availableVideoQualityLabels()
        val options = actualOptions.takeIf { it.size > 1 } ?: emptyList()
        val actualSelected = pm.currentVideoQualityLabel().takeIf { it in options }.orEmpty()
        if (stateBefore.qualityOptions == options && stateBefore.selectedQuality == actualSelected) return

        ui.update { state ->
            if (state.key == videoKey && videoId == null && adaptiveQualitySourceReady) {
                state.copy(qualityOptions = options, selectedQuality = actualSelected)
            } else {
                state
            }
        }
        if (stateBefore.qualityOptions != options) {
            val logMessage = if (options.isEmpty()) {
                "当前网页/本地视频没有可手动切换的多档画质"
            } else {
                "已发现可切换的视频画质"
            }
            val diagnostics = mapOf(
                "媒体来源" to if (remoteMediaUrl != null) "网页视频" else "本地视频",
                "视频轨道清晰度" to (actualOptions.joinToString(",").ifBlank { "未检测到" }),
                "可切换档位数量" to options.size.toString(),
                "选择方式" to "Media3 视频轨覆盖，不重载媒体源",
                "播放位置毫秒" to (if (::pm.isInitialized) pm.position() else -1L).toString(),
            )
            // 播放器轨道回调可能在主线程触发，日志文件写入转移到 IO，避免卡顿。
            scope.launch(Dispatchers.IO) {
                io.translatetube.local.util.AppLogStore.info(
                    this@PlaybackService,
                    "PlaybackQuality",
                    logMessage,
                    diagnosticContext = diagnostics,
                )
            }
        }
    }

    /** 网页/本地自适应视频只改变视频轨选择；不重新加载媒体源，因此不重置播放时间轴。 */
    private fun selectAdaptiveVideoQuality(sessionKey: String, label: String) {
        if (!adaptiveQualitySourceReady || label !in ui.value.qualityOptions) return
        if (label == ui.value.selectedQuality) return

        qualityJob?.cancel()
        qualityJob = scope.launch {
            try {
                ensureCurrentSession(sessionKey)
                if (videoId != null || !adaptiveQualitySourceReady) {
                    throw kotlinx.coroutines.CancellationException("视频来源已改变，取消画质切换")
                }
                updateUiForSession(sessionKey) {
                    it.copy(progress = "正在切换清晰度至 $label…", error = null)
                }
                withContext(Dispatchers.Main.immediate) {
                    ensureCurrentSession(sessionKey)
                    if (!pm.selectVideoQuality(label)) {
                        throw IllegalStateException("当前媒体清单未能选择 $label 视频轨")
                    }
                }
                ensureCurrentSession(sessionKey)
                updateUiForSession(sessionKey) {
                    it.copy(selectedQuality = label, progress = "")
                }
                io.translatetube.local.util.AppLogStore.info(
                    this@PlaybackService,
                    "PlaybackQuality",
                    "网页/本地视频画质切换请求已应用",
                    diagnosticContext = mapOf(
                        "目标清晰度" to label,
                        "切换方式" to "Media3 视频轨覆盖",
                        "是否重新加载媒体源" to "否",
                        "当前播放位置毫秒" to withContext(Dispatchers.Main) { pm.position() }.toString(),
                        "字幕配音时间轴" to "沿用同一播放器时间轴",
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                io.translatetube.local.util.AppLogStore.error(
                    this@PlaybackService,
                    "PlaybackQuality",
                    "网页/本地视频画质切换失败",
                    error,
                    diagnosticContext = mapOf(
                        "目标清晰度" to label,
                        "媒体来源" to if (remoteMediaUrl != null) "网页视频" else "本地视频",
                        "视频会话标识" to safeDiagnosticSessionFingerprint(sessionKey),
                        "当前播放位置毫秒" to (if (::pm.isInitialized) pm.position() else -1L).toString(),
                        "视频轨道清晰度" to (if (::pm.isInitialized) pm.availableVideoQualityLabels().joinToString(",") else "播放器未初始化"),
                    ),
                )
                updateUiForSession(sessionKey) {
                    it.copy(
                        progress = "",
                        error = "切换到 $label 失败；当前网页可能未公开该清晰度的视频轨。",
                    )
                }
            }
        }
    }

    /** 切换清晰度：YouTube 使用提取出的独立流地址，网页/本地媒体使用 Media3 视频轨选择。 */
    fun selectQuality(label: String) {
        val sessionKey = ui.value.key
        if (!::pm.isInitialized || sessionKey.isBlank()) return
        val sessionVideoId = videoId
        if (sessionVideoId == null) {
            selectAdaptiveVideoQuality(sessionKey, label)
            return
        }
        val source = qualitySources.firstOrNull { it.label == label } ?: return
        if (!::pm.isInitialized || sessionKey.isBlank() || source.label == ui.value.selectedQuality) return
        val previousSource = qualitySources.firstOrNull { it.label == ui.value.selectedQuality }
        val previousAudioUrl = qualityAudioUrl
        qualityRecoveryJob?.cancel(); qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        qualityJob?.cancel()
        qualityJob = scope.launch {
            ensureCurrentSession(sessionKey)
            if (videoId != sessionVideoId) throw kotlinx.coroutines.CancellationException("视频已切换，取消旧清晰度切换")
            val positionMs = withContext(Dispatchers.Main) { pm.position() }
            // isPlaying is false while buffering even when the user intends playback.
            // Preserve playWhenReady so switching quality does not accidentally pause the video.
            val wasPlaying = withContext(Dispatchers.Main) { pm.player.playWhenReady }
            updateUiForSession(sessionKey) { it.copy(progress = "正在切换清晰度至 ${source.label}…", error = null) }
            try {
                ensureCurrentSession(sessionKey)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(sessionKey)
                    if (videoId != sessionVideoId) throw kotlinx.coroutines.CancellationException("视频已切换，取消旧清晰度切换")
                    val accepted = pm.setYouTubeQuality(
                        videoUrl = source.url,
                        audioUrl = qualityAudioUrl,
                        muxed = source.muxed,
                        title = ui.value.title,
                        artist = ui.value.channel,
                        artworkUrl = ui.value.thumbUrl,
                    )
                    if (!accepted) {
                        throw IllegalStateException(pm.errorSink.value ?: "播放器拒绝了所选清晰度")
                    }
                    pm.seekTo(positionMs)
                    if (wasPlaying) pm.play() else pm.pause()
                    updateUiForSession(sessionKey) { it.copy(selectedQuality = source.label, progress = "") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
    this@PlaybackService, "PlaybackQuality", "切换 YouTube 清晰度失败", e,
)
android.util.Log.e("PlaybackService", "Unable to switch YouTube quality", e)
                // setYouTubeQuality can reject a stream without throwing. Restore the last known
                // working stream and playback position rather than leaving a half-switched player.
                if (previousSource != null) {
                    try {
                        withContext(Dispatchers.Main) {
                            if (ui.value.key == sessionKey && videoId == sessionVideoId) {
                                val restored = pm.setYouTubeQuality(
                                    videoUrl = previousSource.url,
                                    audioUrl = previousAudioUrl,
                                    muxed = previousSource.muxed,
                                    title = ui.value.title,
                                    artist = ui.value.channel,
                                    artworkUrl = ui.value.thumbUrl,
                                )
                                if (restored) {
                                    pm.seekTo(positionMs)
                                    if (wasPlaying) pm.play() else pm.pause()
                                } else {
                                    android.util.Log.w(
                                        "PlaybackService",
                                        "Unable to restore previous YouTube quality: ${pm.errorSink.value}",
                                    )
                                }
                            }
                        }
                    } catch (rollbackCancellation: kotlinx.coroutines.CancellationException) {
                        throw rollbackCancellation
                    } catch (rollbackError: Exception) {
                        android.util.Log.w("PlaybackService", "Quality-switch rollback failed", rollbackError)
                    }
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        progress = "",
                        error = "切换清晰度失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** 将选中的 YouTube 字幕轨保存到用户指定位置；下载失败只更新提示，不打断播放任务。 */
    fun downloadSubtitle(track: SubtitleTrackOption, outputUri: Uri) {
        val sessionKey = ui.value.key
        val sessionVideoId = videoId
        subtitleDownloadJob?.cancel()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                ensureCurrentSession(sessionKey)
                updateUiForSession(sessionKey) {
                    it.copy(subtitleTrackStatus = "正在下载 " + track.languageTag + " 字幕…")
                }
                if (sessionVideoId != videoId) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕下载")
                }
                val content = NewPipeRepo.downloadSubtitleContent(track)
                ensureCurrentSession(sessionKey)
                if (sessionVideoId != videoId) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕下载")
                }
                val cueCount = SubtitleParsers.parseAutoCancellable(content) {
                    ownerJob?.ensureActive()
                }.size
                if (cueCount == 0) throw IllegalStateException("字幕文件没有可用的时间轴")
                withContext(Dispatchers.IO) {
                    ownerJob?.ensureActive()
                    if (sessionVideoId != videoId || ui.value.key != sessionKey) {
                        throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕写入")
                    }
                    val output = contentResolver.openOutputStream(outputUri, "wt")
                        ?: throw IllegalStateException("无法写入所选文件")
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        // Write in bounded chunks: avoid allocating a second full-size UTF-8 byte
                        // array for a large subtitle, and let task cancellation stop the save promptly.
                        var offset = 0
                        while (offset < content.length) {
                            ownerJob?.ensureActive()
                            val count = minOf(8 * 1024, content.length - offset)
                            writer.write(content, offset, count)
                            offset += count
                        }
                        ownerJob?.ensureActive()
                    }
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        subtitleTrackStatus = "已保存 " + track.languageTag + " 字幕（" + cueCount +
                            " 条）。翻译时请保留时间戳，完成后可上传并直接配音。",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.warning(
    this@PlaybackService, "Subtitles", "下载原字幕失败", e,
)
android.util.Log.w("PlaybackService", "Subtitle download failed", e)
                updateUiForSession(sessionKey) {
                    it.copy(subtitleTrackStatus = "字幕下载失败：" + (e.message ?: e.javaClass.simpleName))
                }
            } finally {
                if (subtitleDownloadJob === ownerJob) subtitleDownloadJob = null
            }
        }
        subtitleDownloadJob = job
        job.start()
    }


    /**
     * Pause the active five-stage pipeline after its source subtitle rows become available,
     * allowing the user to export them, translate externally, and resume by importing the result.
     */
    fun enableManualSubtitleTranslation() {
        val current = ui.value
        val key = current.key
        if (key.isBlank()) return
        if (current.phase !in setOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING, Phase.TRANSLATING)) {
            android.widget.Toast.makeText(
                this,
                "请在原文字幕转写或翻译阶段启用外部翻译模式。",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        manualTranslationRequestedKeys.add(key)
        ui.update { state ->
            if (state.key == key) state.copy(
                manualTranslationModeEnabled = true,
                subtitleTrackStatus = "已启用外部翻译模式；原文字幕生成后将暂停等待导入译文。",
            ) else state
        }
        io.translatetube.local.util.AppLogStore.info(
            this,
            "Subtitles",
            "已启用外部翻译模式",
            diagnosticContext = mapOf("字幕任务键类型" to when {
                key.startsWith("youtube:") -> "YouTube"
                key.startsWith("remote:") -> "网页视频/远程媒体"
                key.startsWith("local:") -> "本地视频"
                else -> "其他"
            }),
        )
    }

    /** Import the externally translated file into the waiting pipeline, retaining the original timeline. */
    fun importManualTranslatedSubtitle(uri: Uri) {
        val sessionKey = ui.value.key
        val waiter = manualTranslationWaiters[sessionKey]
        if (sessionKey.isBlank() || waiter == null || !ui.value.manualTranslationWaiting) {
            android.widget.Toast.makeText(
                this,
                "请先启用外部翻译模式，等待原文字幕生成后再导入译文。",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        scope.launch {
            try {
                val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                val decoded = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取选中的字幕文件")
                    input.use {
                        val bytes = io.translatetube.local.yt.BoundedTextPayload.readBytes(
                            it,
                            maxBytes = 8L * 1024L * 1024L,
                            resourceLabel = "外部翻译字幕文件",
                            checkActive = { ownerJob?.ensureActive() },
                        )
                        io.translatetube.local.subs.SubtitleFileDecoder.decode(bytes)
                    }
                }
                val translated = withContext(Dispatchers.Default) {
                    SubtitleParsers.parseAutoCancellable(decoded) {
                        ownerJob?.ensureActive()
                    }.filter { line ->
                        line.startMs >= 0L && line.endMs > line.startMs && line.text.isNotBlank()
                    }
                }
                ensureCurrentSession(sessionKey)
                val source = manualTranslationSources[sessionKey]
                    ?: throw IllegalStateException("原文字幕等待状态已失效，请重新启动该流程")
                val merged = io.translatetube.local.subs.ManualSubtitleTranslationMerger.merge(source, translated)
                ensureCurrentSession(sessionKey)
                if (!waiter.complete(merged)) {
                    throw IllegalStateException("当前字幕等待任务已结束，请重新启用外部翻译模式")
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        subtitleTrackStatus = "已导入外部译文，正在沿用原字幕时间轴继续处理。",
                        progress = "已匹配译文时间轴，正在继续配音准备…",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
                    this@PlaybackService,
                    "Subtitles",
                    "导入外部翻译字幕失败",
                    e,
                )
                updateUiForSession(sessionKey) {
                    it.copy(
                        manualTranslationWaiting = true,
                        manualTranslationModeEnabled = true,
                        subtitleTrackStatus = "导入失败，可重新选择译文文件：" + (e.message ?: e.javaClass.simpleName),
                        progress = "译文导入失败，请检查字幕时间轴后重试。",
                    )
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@PlaybackService,
                        "译文导入失败：" + (e.message ?: e.javaClass.simpleName),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private suspend fun awaitManualSubtitleTranslation(
        sessionKey: String,
        originalLines: List<SubLine>,
        sourceLang: String,
        pipelineGeneration: Long?,
    ): List<SubLine>? {
        if (!manualTranslationRequestedKeys.contains(sessionKey)) return null
        val sourceOnly = originalLines.mapIndexed { index, line ->
            line.copy(index = index, zh = null)
        }
        if (sourceOnly.isEmpty()) return null
        val newWaiter = kotlinx.coroutines.CompletableDeferred<List<SubLine>>()
        val existing = manualTranslationWaiters.putIfAbsent(sessionKey, newWaiter)
        val waiter = existing ?: newWaiter
        manualTranslationSources[sessionKey] = sourceOnly
        updateUiForSession(sessionKey, pipelineGeneration) {
            it.copy(
                phase = Phase.TRANSLATING,
                lines = sourceOnly,
                totalLines = sourceOnly.size,
                sourceLang = sourceLang,
                translatedLineCount = 0,
                manualTranslationModeEnabled = true,
                manualTranslationWaiting = true,
                progress = "原文字幕已生成（" + sourceOnly.size + " 条），请导出后外部翻译，再导入译文继续。",
                subtitleTrackStatus = "正在等待外部翻译字幕文件；当前视频与原文时间轴已保留。",
            )
        }
        try {
            val merged = waiter.await()
            ensureCurrentSession(sessionKey, pipelineGeneration)
            manualTranslationRequestedKeys.remove(sessionKey)
            updateUiForSession(sessionKey, pipelineGeneration) {
                it.copy(
                    lines = merged,
                    totalLines = merged.size,
                    translatedLineCount = merged.count { line -> !line.zh.isNullOrBlank() },
                    manualTranslationModeEnabled = false,
                    manualTranslationWaiting = false,
                    progress = "外部译文已载入，继续准备视频字幕与配音…",
                    subtitleTrackStatus = "外部译文已匹配到原文时间轴。",
                )
            }
            return merged
        } finally {
            manualTranslationWaiters.remove(sessionKey, waiter)
            manualTranslationSources.remove(sessionKey)
            if (!waiter.isCompleted) manualTranslationRequestedKeys.remove(sessionKey)
        }
    }

    /**
     * 导入已经翻译好的字幕：保留上传文件的时间轴，把译文作为配音文本，不再调用翻译引擎。
     * 支持 SRT/VTT/ASS；双语 cue 会优先取含中文的行作为译文。
     */
    fun importTranslatedSubtitles(uri: Uri) {
        val pipelineGeneration = beginPipelineGeneration()
        val sessionKey = videoKey
        val resumePlaybackAfterImport = ::pm.isInitialized &&
            runCatching { pm.player.playWhenReady }.getOrDefault(false)
        if (::pm.isInitialized) pm.pause()
        subtitleDownloadJob?.cancel(); subtitleDownloadJob = null
        prepJob?.cancel()
        prepJob = null
        speakerJob?.cancel()
        speakerJob = null
        qualityJob?.cancel()
        qualityJob = null
        qualityRecoveryJob?.cancel()
        qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        dubCollectJob?.cancel()
        dubCollectJob = null
        retireCurrentDubbing()
        lastLineIdx = Int.MIN_VALUE
        prepJob = scope.launch {
            pipelineExecutionMutex.withLock {
            try {
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.PARSING,
                        progress = "正在读取已翻译字幕文件…",
                        processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                        error = null,
                        noModelHint = null,
                        dubbingHasErrors = false,
                        translatedLineCount = 0,
                        activeDubbingLine = -1,
                        currentLine = -1,
                        waitingForTranscriptionEngine = false,
                        waitingForDubbingEngine = false,
                    )
                }
                val importOwnerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                val text = withContext(Dispatchers.IO) {
                    val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取字幕文件")
                    input.use {
                        SubtitleFileDecoder.decode(
                            BoundedTextPayload.readBytes(
                                it,
                                maxBytes = 8L * 1024L * 1024L,
                                resourceLabel = "导入的已翻译字幕文件",
                                checkActive = { ownerJob?.ensureActive() },
                            ),
                        )
                    }
                }
                val parsed = SubtitleParsers.parseAutoCancellable(text) {
                    importOwnerJob?.ensureActive()
                }.filter { it.startMs >= 0L && it.endMs > it.startMs }
                ensureCurrentSession(sessionKey, pipelineGeneration)
                if (parsed.isEmpty()) throw IllegalStateException("字幕文件为空、时间轴无效或格式不支持")
                val translatedLines = applySavedLineOverrides(parsed.mapIndexed { index, line ->
                    val cue = io.translatetube.local.subs.ImportedSubtitlePolicy.splitBilingualCue(line.text)
                    line.copy(
                        index = index,
                        text = cue.originalText,
                        zh = cue.translationText,
                    )
                })
                ensureCurrentSession(sessionKey, pipelineGeneration)
                safelySetOriginalVolume(if (ui.value.originalVolume <= 0f) 0f else 1f, "source audio fallback")
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.SYNTHESIZING,
                        progress = "已载入 " + translatedLines.size + " 条已翻译字幕，正在准备中文配音…",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        translatedLineCount = translatedLines.size,
                        activeDubbingLine = -1,
                        currentLine = -1,
                        preparedUntil = 0,
                        preparedCount = 0,
                        sourceLang = "zh",
                        error = null,
                        noModelHint = null,
                            waitingForTranscriptionEngine = false,
                            waitingForDubbingEngine = false,
                        subtitleTrackStatus = "已导入已翻译字幕；此流程会跳过自动翻译。",
                    )
                }
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey, pipelineGeneration)
                val tts = makeTtsOffMainThread()
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope, pm.dubbingBus)
                } catch (e: Exception) {
                    runCatching { tts.close() }
                    throw e
                }
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    runCatching { tts.close() }
                    throw e
                }
                registerDubbingFailureHandler(d, tts, sessionKey)
                try {
                    d.attach(tts, translatedLines)
                } catch (e: Exception) {
                    retireDubbing(d)
                    runCatching { tts.close() }
                    throw e
                }
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                if (!publishDubbingEngine(d, sessionKey, pipelineGeneration)) {
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("已翻译字幕导入任务已过期")
                }
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === d) dubbing = null
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕导入任务")
                }
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForCurrentDubbingEngine(d, sessionKey) {
                                it.copy(preparedUntil = prefix, preparedCount = readyCount)
                            }
                        }
                }
                lastLineIdx = Int.MIN_VALUE
                ensureCurrentSession(sessionKey, pipelineGeneration)
                awaitDubbingSynthesisBeforePlayback(
                    sessionKey = sessionKey,
                    pipelineGeneration = pipelineGeneration,
                    engine = d,
                    lines = translatedLines,
                )
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                    startPlaybackWithMountedDubbing(null) {
                        ensureCurrentSession(sessionKey, pipelineGeneration)
                    }
                }
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.READY,
                        progress = "",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        preparedUntil = d.preparedUntil.value,
                        preparedCount = d.preparedCount.value,
                        sourceLang = "zh",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
    this@PlaybackService, "Subtitles", "导入已翻译字幕失败", e,
)
android.util.Log.e("PlaybackService", "Import translated subtitles failed", e)
                if (videoKey == sessionKey && ui.value.key == sessionKey) {
                    runCatching { safelySetOriginalVolume(1f, "source audio restore requested") }
                }
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "已翻译字幕导入失败：" + (e.message ?: e.javaClass.simpleName),
                        muteOriginal = false,
                    )
                }
                if (resumePlaybackAfterImport && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) {
                        if (videoKey == sessionKey && ui.value.key == sessionKey) pm.play()
                    }
                }
            }
            }
        }
    }

    /** 导入外部字幕（SRT/VTT）：解析后重新走翻译+配音 */
    fun importSubtitles(uri: Uri) {
        val pipelineGeneration = beginPipelineGeneration()
        val sessionKey = videoKey
        val resumePlaybackAfterImport = ::pm.isInitialized &&
            runCatching { pm.player.playWhenReady }.getOrDefault(false)
        if (::pm.isInitialized) pm.pause()
        subtitleDownloadJob?.cancel(); subtitleDownloadJob = null
        prepJob?.cancel()
        prepJob = null
        speakerJob?.cancel()
        speakerJob = null
        qualityJob?.cancel()
        qualityJob = null
        qualityRecoveryJob?.cancel()
        qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        dubCollectJob?.cancel()
        dubCollectJob = null
        retireCurrentDubbing()
        safelySetOriginalVolume(if (ui.value.originalVolume <= 0f) 0f else 1f, "source audio fallback")
        lastLineIdx = Int.MIN_VALUE

        prepJob = scope.launch {
            pipelineExecutionMutex.withLock {
            try {
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.PARSING,
                        progress = "正在读取字幕文件…",
                        processingFlow = ProcessingFlow.IMPORTED_SUBTITLES,
                        error = null,
                        noModelHint = null,
                        dubbingHasErrors = false,
                        translatedLineCount = 0,
                        activeDubbingLine = -1,
                        currentLine = -1,
                        waitingForTranscriptionEngine = false,
                        waitingForDubbingEngine = false,
                    )
                }
                // Subtitle files can be large. Keep file I/O and parsing off the main thread so
                // importing a long SRT/VTT/ASS file cannot freeze player controls.
                val parsedLines = withContext(Dispatchers.IO) {
                    val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取字幕文件")
                    val text = input.use {
                        SubtitleFileDecoder.decode(
                            BoundedTextPayload.readBytes(
                                it,
                                maxBytes = 8L * 1024L * 1024L,
                                resourceLabel = "导入的字幕文件",
                                checkActive = { ownerJob?.ensureActive() },
                            ),
                        )
                    }
                    SubtitleParsers.parseAutoCancellable(text) {
                        ownerJob?.ensureActive()
                    }.filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
                        .mapIndexed { index, line -> line.copy(index = index) }
                }
                ensureCurrentSession(sessionKey, pipelineGeneration)
                if (parsedLines.isEmpty()) {
                    throw IllegalStateException("字幕文件为空或格式不支持")
                }

                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "翻译导入的字幕（${parsedLines.size} 行）…",
                    )
                }
                val importedCueTexts = parsedLines.map { it.text }
                val srcLang = resolveSourceLanguageForCues(
                    importedCueTexts,
                    io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(importedCueTexts),
                )
                val activeTranslationId = getSharedPreferences("settings", MODE_PRIVATE)
                    .getLong("active_api_id", -1L)
                val providerFingerprint = translationProviderFingerprint(activeTranslationId)
                val importedCacheKey = importedTranslationCacheKey(
                    sessionKey,
                    activeTranslationId,
                    parsedLines,
                    providerFingerprint,
                )
                ensureCurrentSession(sessionKey, pipelineGeneration)
                // Preserve the imported source identity if this translation is later retried.
                updateUiForSession(sessionKey, pipelineGeneration) { state ->
                    state.copy(
                        translationCacheKey = importedCacheKey,
                        lines = parsedLines,
                        totalLines = parsedLines.size,
                        translatedLineCount = 0,
                        activeDubbingLine = -1,
                        currentLine = -1,
                        sourceLang = srcLang,
                    )
                }
                // Reuse a matching translation cache when the same subtitle file is imported again.
                // Only valid rows are restored; missing or placeholder rows remain eligible for retry.
                val cachedImportedLines = if (isChineseLanguage(srcLang)) {
                    null
                } else {
                    val cachedEntry = AppDatabase.get(this@PlaybackService)
                        .subtitleCacheDao().get(importedCacheKey)
                    if (cachedEntry == null) {
                        null
                    } else {
                        val cacheLoadJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                        val restored = runCatching {
                            val cacheRows = org.json.JSONArray(cachedEntry.linesJson)
                            if (cacheRows.length() > 500_000) {
                                throw IllegalArgumentException("字幕缓存条数超过安全上限")
                            }
                            val rows = ArrayList<SubtitleCacheValidationPolicy.CachedTranslationRow>(cacheRows.length())
                            for (index in 0 until cacheRows.length()) {
                                if ((index and 0xff) == 0) cacheLoadJob?.ensureActive()
                                val row = cacheRows.optJSONObject(index)
                                    ?: throw IllegalArgumentException("字幕缓存第 ${index + 1} 行不是对象")
                                rows += SubtitleCacheValidationPolicy.CachedTranslationRow(
                                    startMs = row.optLong("s", Long.MIN_VALUE),
                                    endMs = row.optLong("e", Long.MIN_VALUE),
                                    sourceText = row.optString("t", ""),
                                    // JSONObject.NULL.toString() can become the literal "null";
                                    // treat absent/null translations as missing so they are retried.
                                    translatedText = row.optString("zh", "").takeIf { it.isNotBlank() },
                                )
                            }
                            SubtitleCacheValidationPolicy.restoreTranslationsForSource(parsedLines, rows) {
                                cacheLoadJob?.ensureActive()
                            }
                        }.getOrNull()
                        if (restored == null) {
                            android.util.Log.w(
                                "PlaybackService",
                                "Ignoring imported subtitle translation cache that failed source/timeline validation",
                            )
                            AppDatabase.get(this@PlaybackService).subtitleCacheDao().delete(importedCacheKey)
                        } else {
                            android.util.Log.i(
                                "PlaybackService",
                                "Reusing imported subtitle translation cache; unfinished rows will be retried",
                            )
                        }
                        restored
                    }
                }
                // Use immutable copies so StateFlow/Compose sees translated subtitle text.
                val translatedLines = applySavedLineOverrides(
                    if (isChineseLanguage(srcLang)) {
                        SubtitleCacheValidationPolicy.restoreChineseSourceTranslations(parsedLines)
                    } else {
                        translateLines(
                            sessionKey,
                            cachedImportedLines ?: parsedLines,
                            srcLang,
                            importedCacheKey,
                            activeTranslationId,
                            pipelineGeneration,
                        )
                    }
                )
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey, pipelineGeneration)
                val selectedImportTts = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("tts_engine", "edge") ?: "edge"
                val importEngineReady = when (selectedImportTts) {
                    "kokoro" -> kokoroRuntimeReady()
                    "piper" -> piperModelReadyForChinese()
                    "web" -> true
                    "edge" -> true
                    else -> selectedImportTts == "system"
                }
                if (!importEngineReady) {
                    // 中文源 + 所选 TTS 引擎未就绪（模型未下载/未知引擎）：直接显示原文并保留原声。
                    // 引擎就绪时（含系统 TTS 与已装模型）仍按时间轴朗读中文字幕。
                    val translatedCount = translatedLines.count {
                        SubtitleParsers.cleanTranslatedText(it.zh) != null
                    }
                    val arr = org.json.JSONArray()
                    for (line in translatedLines) arr.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                            .put("zh", line.zh ?: "")
                            .put("sp", line.speakerId)
                    )
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                    if (translatedCount > translatedLines.size / 2) {
                        AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                            io.translatetube.local.data.SubtitleCacheEntity(
                                importedCacheKey,
                                srcLang,
                                arr.toString(),
                                System.currentTimeMillis(),
                                videoKey = sessionKey,
                            )
                        )
                    }
                    updateUiForSession(sessionKey, pipelineGeneration) {
                        val unavailableEngineName = ttsDisplayName(selectedImportTts)
                        val unavailableHint = when (selectedImportTts) {
                            "kokoro" -> "已选择 Kokoro 配音模型，但模型尚未就绪。请在设置 → 配音设置中下载完整模型后重新打开或处理此视频。"
                            "piper" -> {
                                val modelId = getSharedPreferences("settings", MODE_PRIVATE)
                                    .getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium"
                                val definition = io.translatetube.local.tts.PiperModels.byId(modelId)
                                when {
                                    definition == null -> "当前 Piper 模型编号无效，请在设置中重新选择中文 Piper 模型。"
                                    !definition.supportsChineseOutput -> "当前 Piper 模型不支持中文输出，请在设置中选择中文模型。"
                                    else -> "已选择 Piper 中文配音，但模型尚未下载或文件不完整。请在设置 → 配音设置中下载中文模型。"
                                }
                            }
                            else -> "所选配音引擎「$unavailableEngineName」尚未就绪，请先检查设置和引擎配置。"
                        }
                        it.copy(
                            phase = Phase.READY,
                            progress = "",
                            ttsEngineName = unavailableEngineName,
                            dubbingStatus = "$unavailableEngineName 尚未就绪，视频已暂停",
                            muteOriginal = it.originalVolume <= 0f,
                            noModelHint = unavailableHint,
                            waitingForTranscriptionEngine = false,
                            waitingForDubbingEngine = true,
                            lines = translatedLines,
                            totalLines = translatedLines.size,
                            translatedLineCount = translatedCount,
                            activeDubbingLine = -1,
                            currentLine = -1,
                            error = null,
                        )
                    }
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                    return@launch
                }
                val tts = makeTtsOffMainThread()
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope, pm.dubbingBus)
                } catch (e: Exception) {
                    runCatching { tts.close() }
                    throw e
                }
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    runCatching { tts.close() }
                    throw e
                }
                registerDubbingFailureHandler(d, tts, sessionKey)
                try {
                    d.attach(tts, translatedLines, sourceLanguage = srcLang)
                } catch (e: Exception) {
                    retireDubbing(d)
                    runCatching { tts.close() }
                    throw e
                }
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                if (!publishDubbingEngine(d, sessionKey, pipelineGeneration)) {
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("字幕导入任务已过期")
                }
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === d) dubbing = null
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕导入任务")
                }
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForCurrentDubbingEngine(d, sessionKey) {
                                it.copy(preparedUntil = prefix, preparedCount = readyCount)
                            }
                        }
                }

                // Store a cache only after import + translation produced useful subtitle rows.
                val arr = org.json.JSONArray()
                for (line in translatedLines) arr.put(
                    org.json.JSONObject()
                        .put("s", line.startMs)
                        .put("e", line.endMs)
                        .put("t", line.text)
                        .put("zh", line.zh ?: "")
                        .put("sp", line.speakerId)
                )
                ensureCurrentSession(sessionKey, pipelineGeneration)
                if (translatedLines.count { SubtitleParsers.cleanTranslatedText(it.zh) != null } >
                    translatedLines.size / 2
                ) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            importedCacheKey,
                            srcLang,
                            arr.toString(),
                            System.currentTimeMillis(),
                            videoKey = sessionKey,
                        )
                    )
                }
                awaitDubbingSynthesisBeforePlayback(
                    sessionKey = sessionKey,
                    pipelineGeneration = pipelineGeneration,
                    engine = d,
                    lines = translatedLines,
                )
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        phase = Phase.READY,
                        progress = "",
                        error = null,
                    )
                }
                ensureCurrentSession(sessionKey, pipelineGeneration)
                lastLineIdx = Int.MIN_VALUE
                if (resumePlaybackAfterImport) {
                    withContext(Dispatchers.Main) {
                        ensureCurrentSession(sessionKey, pipelineGeneration)
                        startPlaybackWithMountedDubbing(null) {
                            ensureCurrentSession(sessionKey, pipelineGeneration)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
    this@PlaybackService, "Subtitles", "导入字幕并启动处理流程失败", e,
)
android.util.Log.e("PlaybackService", "Subtitle import failed", e)
                updateUiForSession(sessionKey, pipelineGeneration) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "字幕导入失败: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
                if (resumePlaybackAfterImport && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) {
                        if (videoKey == sessionKey && ui.value.key == sessionKey) pm.play()
                    }
                }
            }
            }
        }
    }

    /** Snapshot all fields that can change translation output, without exposing the API key in cache names. */
    private suspend fun translationProviderFingerprint(activeId: Long): String {
        val profiles = AppDatabase.get(this).apiProfileDao().enabled()
        val active = profiles.firstOrNull { it.id == activeId }
        val providerKind = when (activeId) {
            -1L, -2L -> "google-hybrid"
            EngineFactory.ID_MICROSOFT -> "microsoft-web"
            EngineFactory.ID_BAIDU -> "baidu-web"
            EngineFactory.ID_DEEPL -> "deepl-web"
            EngineFactory.ID_FREE_GEMINI -> "free-gemini"
            EngineFactory.ID_FREE_DEEPSEEK -> "free-deepseek"
            EngineFactory.ID_LOCAL_MLKIT -> "mlkit-local"
            EngineFactory.ID_LOCAL_OPUS -> "opus-mt-en-zh-4dc37af-onnx-quantized-v2"
            EngineFactory.ID_LOCAL_SMALL100 -> "small100-multilingual-onnx"
            else -> when {
                EngineFactory.isLocalTranslationEngine(activeId) -> "local-model-$activeId"
                active != null -> "api-profile"
                else -> "unregistered-engine-$activeId"
            }
        }
        val sourceLanguageMode = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("translation_source_language", "auto")
            .orEmpty()
            .ifBlank { "auto" }
        val cacheScopedProviderKind = if (SourceLanguagePolicy.isUndetermined(sourceLanguageMode)) {
            providerKind
        } else {
            "$providerKind|source=$sourceLanguageMode"
        }
        return TranslationCacheIdentity.providerFingerprint(
            activeId = activeId,
            providerKind = cacheScopedProviderKind,
            type = active?.type.orEmpty(),
            baseUrl = active?.baseUrl.orEmpty(),
            apiKey = active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            region = active?.region.orEmpty(),
            credentialsJson = active?.credentialsJson.orEmpty(),
        )
    }

    /** Imported subtitles get their own source fingerprint and active-provider identity. */
    private suspend fun resolveSourceLanguageForCues(
        cues: Iterable<String>,
        detectedLanguage: String,
    ): String {
        val preferred = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("translation_source_language", "auto")
            .orEmpty()
            .ifBlank { "auto" }
        if (!SourceLanguagePolicy.isUndetermined(preferred)) {
            return preferred
        }

        // Strong script detection first (Japanese, Korean, Chinese, etc.).
        val scriptDetected = io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(cues)
        if (!SourceLanguagePolicy.isUndetermined(scriptDetected)) {
            return scriptDetected
        }

        // Run bundled on-device language identification for ambiguous Latin-script subtitles,
        // including cases where an ASR provider's language hint may be wrong.
        val identified = io.translatetube.local.subs.MlKitSourceLanguageIdentifier.detect(cues)
        if (!SourceLanguagePolicy.isUndetermined(identified)) {
            return identified
        }

        // If the model cannot confidently identify the text, preserve the provider's hint.
        return io.translatetube.local.subs.SubtitleLanguageDetector
            .resolveTranscriptionLanguageFromCues(detectedLanguage, cues)
    }

    private fun importedTranslationCacheKey(
        key: String,
        activeId: Long,
        lines: List<SubLine>,
        providerFingerprint: String? = null,
    ): String = TranslationCacheIdentity.importedKey(key, activeId, lines, providerFingerprint)

    /** Compare source text and timing before trusting a legacy video-level translation cache. */
    private fun translationCacheMatchesSource(linesJson: String, sourceLines: List<SubLine>): Boolean = try {
        val rows = org.json.JSONArray(linesJson)
        if (rows.length() != sourceLines.size) {
            false
        } else {
            sourceLines.indices.all { i ->
                val row = rows.optJSONObject(i) ?: return@all false
                row.optLong("s", Long.MIN_VALUE) == sourceLines[i].startMs &&
                    row.optLong("e", Long.MIN_VALUE) == sourceLines[i].endMs &&
                    row.optString("t") == sourceLines[i].text
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("PlaybackService", "Unable to compare source lines to translation cache", e)
        false
    }

    /** Chinese subtitle tracks must not be translated into Chinese again. */
    private fun isChineseLanguage(tag: String): Boolean =
        SourceLanguagePolicy.isChinese(tag)

    /**
     * Treat a non-Chinese cue whose cached "translation" exactly mirrors its source as unfinished.
     * This repairs older caches where source text was mistakenly saved to the Chinese field.
     */
    private fun needsSubtitleTranslation(line: SubLine, srcLang: String): Boolean {
        if (line.text.isBlank()) return false
        val translated = SubtitleParsers.cleanTranslatedText(line.zh)
        if (translated.isNullOrBlank()) return true
        if (isChineseLanguage(srcLang)) return false
        return io.translatetube.local.translate.SubtitleTranslationQualityPolicy
            .isSourceEcho(line.text, translated, srcLang)
    }

    /** Reject corrupt timelines and progress/status placeholders before restoring translated rows. */
    private fun isUsableTranslationCache(linesJson: String): Boolean = try {
        val rows = org.json.JSONArray(linesJson)
        if (rows.length() == 0) {
            false
        } else {
            val translations = ArrayList<String?>(rows.length())
            val timelineRows = ArrayList<Triple<Long, Long, String>>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)
                    ?: throw IllegalArgumentException("字幕缓存第 ${i + 1} 行不是对象")
                val startMs = row.optLong("s", Long.MIN_VALUE)
                val endMs = row.optLong("e", Long.MIN_VALUE)
                val originalText = row.optString("t").trim()
                timelineRows += Triple(startMs, endMs, originalText)

                translations += row.optString("zh", "")
            }
            if (!SubtitleCacheValidationPolicy.hasValidTimeline(timelineRows)) {
                throw IllegalArgumentException("字幕缓存时间轴无效或未按开始时间排序")
            }
            // Keep any valid completed rows; restore logic cleans placeholder rows to null and
            // translates only those missing cues on retry.
            SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(translations)
        }
    } catch (e: Exception) {
        android.util.Log.w("PlaybackService", "Invalid subtitle translation cache JSON or timeline", e)
        false
    }

    private suspend fun runPipeline(
        key: String,
        pipelineGeneration: Long,
        requestedVideoId: String?,
        localUri: Uri?,
        resumeState: PlayerUiState? = null,
        sourceImportedLines: List<SubLine>? = null,
        requestedRemoteMediaUrl: String? = null,
        requestedRemoteMediaTitle: String = "",
        requestedRemoteMediaReferrer: String = "",
        requestedRemoteMediaCookie: String? = null,
        reuseMountedMedia: Boolean = false,
        restoreCompletedSnapshot: Boolean = false,
        forceRegenerateDubbing: Boolean = false,
    ) {
        // The NewPipe path remains preferred. If YouTube explicitly blocks anonymous watch access,
        // switch this session to the existing yt-dlp media URL pipeline and continue with audio ASR.
        var videoId = requestedVideoId
        var remoteMediaUrl = requestedRemoteMediaUrl
        var remoteMediaTitle = requestedRemoteMediaTitle
        var remoteMediaReferrer = requestedRemoteMediaReferrer
        var remoteMediaCookie = requestedRemoteMediaCookie

        // 记录仅供转写使用的临时网络音频，任何异常/取消路径都清理。
        var transientAudio: File? = null
        var transientPcmFile: AudioExtract.PcmFile? = null
        var transientCloudUploadAudio: File? = null
        try {
            awaitProcessingResumed(key, pipelineGeneration)
            awaitRetiredDubbingEngines()
            awaitProcessingResumed(key, pipelineGeneration)
            var meta = VideoEntity(
                key = key, videoId = videoId, localUri = localUri?.toString() ?: remoteMediaUrl,
                title = resumeState?.title.orEmpty(), channel = resumeState?.channel.orEmpty(),
                durationSec = (resumeState?.durationMs ?: 0L) / 1000,
                thumbUrl = resumeState?.thumbUrl,
                sourceType = if (videoId != null) SourceType.YOUTUBE else SourceType.LOCAL,
                addedAt = System.currentTimeMillis(), lastPlayedAt = System.currentTimeMillis(),
            )

            // ---- 1. 元信息 ----
            var srcLang = resumeState?.sourceLang.orEmpty()
            if (restoreCompletedSnapshot && resumeState != null) {
                // A complete snapshot is already translated and synthesized. Keep the playback
                // page in READY while the media source and cached dubbing queue are reattached.
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(phase = Phase.READY, progress = "", sourceLang = srcLang, error = null)
                }
            } else if (resumeState != null) {
                // Keep current metadata for selective restarts. An empty snapshot means the ASR
                // stage is rerunning; populated captions resume at translation only when their
                // Chinese text is missing, otherwise go straight to dubbing.
                val restartingTranscription = resumeState.lines.isEmpty()
                val restartingTranslation = !restartingTranscription &&
                    !isChineseLanguage(srcLang) &&
                    resumeState.lines.any { needsSubtitleTranslation(it, srcLang) }
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = when {
                            restartingTranscription -> Phase.PARSING
                            restartingTranslation -> Phase.TRANSLATING
                            else -> Phase.SYNTHESIZING
                        },
                        progress = when {
                            restartingTranscription -> "复用已有视频信息，准备重新转写…"
                            restartingTranslation -> "恢复已获取的原文字幕，继续翻译…"
                            else -> "原文与中文字幕已就绪，正在准备配音…"
                        },
                        sourceLang = srcLang,
                        error = null,
                    )
                }
            } else if (videoId != null) {
                awaitProcessingResumed(key, pipelineGeneration)
                val requestedId = videoId
                try {
                    val m = NewPipeRepo.resolve(requestedId)
                    awaitProcessingResumed(key, pipelineGeneration)
                    meta = meta.copy(title = m.title, channel = m.channel, durationSec = m.durationSec, thumbUrl = m.thumbUrl)
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(title = m.title, channel = m.channel, thumbUrl = m.thumbUrl, durationMs = m.durationSec * 1000)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (extractorError: Exception) {
                    if (!io.translatetube.local.yt.YouTubeAccessFallbackPolicy.shouldTryYtDlp(extractorError)) {
                        throw extractorError
                    }

                    val watchUrl = "https://www.youtube.com/watch?v=$requestedId"
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            progress = "YouTube 限制匿名访问，正在尝试 yt-dlp 备用解析…",
                            error = null,
                        )
                    }
                    awaitProcessingResumed(key, pipelineGeneration)
                    val cookieHeader = runCatching {
                        android.webkit.CookieManager.getInstance().getCookie(watchUrl)
                    }.getOrNull()
                    val fallback = try {
                        io.translatetube.local.yt.YtDlpRepo.resolve(
                            this@PlaybackService,
                            watchUrl,
                            cookieHeader,
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (fallbackError: Exception) {
                        android.util.Log.w(
                            "PlaybackService",
                            "YouTube anonymous-access fallback via yt-dlp failed",
                            fallbackError,
                        )
                        throw IllegalStateException(
                            "YouTube 拒绝当前网络的匿名访问，已自动尝试 yt-dlp 备用解析但仍失败。" +
                                "请稍后重试，或在应用网页视频页打开 YouTube 并登录后再解析。" +
                                "备用解析错误：${fallbackError.message ?: fallbackError.javaClass.simpleName}",
                            fallbackError,
                        ).also { it.addSuppressed(extractorError) }
                    }
                    awaitProcessingResumed(key, pipelineGeneration)

                    // From this point on, use the web-media branch: stream the resolved URL for
                    // playback and extract/cache its audio for ASR instead of calling NewPipe again.
                    videoId = null
                    this.videoId = null
                    remoteMediaUrl = fallback.mediaUrl
                    remoteMediaTitle = fallback.title
                    remoteMediaReferrer = fallback.referrer
                    remoteMediaCookie = fallback.cookie.takeIf { it.isNotBlank() }
                    this.remoteMediaUrl = remoteMediaUrl
                    this.remoteMediaTitle = remoteMediaTitle
                    this.remoteMediaReferrer = remoteMediaReferrer
                    this.remoteMediaCookie = remoteMediaCookie
                    this.remoteMediaUserAgent = io.translatetube.local.yt.YtDlpRepo.BROWSER_UA

                    val thumbnail = "https://i.ytimg.com/vi/$requestedId/hqdefault.jpg"
                    meta = meta.copy(
                        title = fallback.title,
                        channel = "",
                        durationSec = fallback.durationSec.toLong(),
                        thumbUrl = thumbnail,
                        localUri = fallback.mediaUrl,
                        sourceType = SourceType.YOUTUBE,
                    )
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            title = fallback.title,
                            channel = "",
                            thumbUrl = thumbnail,
                            durationMs = fallback.durationSec * 1000L,
                            processingFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                            progress = "备用解析成功，正在继续处理视频音频…",
                            error = null,
                        )
                    }
                }
            } else if (remoteMediaUrl != null && resumeState == null) {
                val remoteUri = Uri.parse(remoteMediaUrl)
                val title = remoteMediaTitle.takeIf { it.isNotBlank() }
                    ?: remoteUri.lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() }
                    ?: "网页视频"
                meta = meta.copy(title = title, localUri = remoteMediaUrl)
                updateUiForSession(key, pipelineGeneration) { it.copy(title = title, durationMs = 0L) }
            } else if (resumeState == null) {
                localUri?.let { u ->
                    // MetadataRetriever can perform blocking container I/O. Keep it off the main
                    // dispatcher so opening a large/slow local file cannot freeze playback UI.
                    val (title, dur) = withContext(Dispatchers.IO) {
                        var resolvedTitle = u.lastPathSegment ?: "本地视频"
                        var durationMs = 0L
                        var mmr: android.media.MediaMetadataRetriever? = null
                        try {
                            mmr = android.media.MediaMetadataRetriever()
                            mmr.setDataSource(this@PlaybackService, u)
                            resolvedTitle = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                ?.takeIf { it.isNotBlank() } ?: resolvedTitle
                            durationMs = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?: "0").toLongOrNull() ?: 0L
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Some SAF providers do not expose retrievable metadata; keep the URI name.
                        } finally {
                            // A bad URI/unsupported container can throw before metadata is read.
                            runCatching { mmr?.release() }
                        }
                        resolvedTitle to durationMs
                    }
                    meta = meta.copy(title = title, durationSec = dur / 1000)
                    updateUiForSession(key, pipelineGeneration) { it.copy(title = title, durationMs = dur) }
                }
            }

            // 解析视频时先列出所有字幕轨。下载是可选操作，不影响自动翻译/配音流程。
            if (videoId != null && resumeState == null) {
                try {
                    awaitProcessingResumed(key, pipelineGeneration)
                    updateUiForSession(key, pipelineGeneration) { it.copy(progress = "正在查找可用字幕轨…") }
                    val tracks = NewPipeRepo.subtitleTracks(videoId)
                    awaitProcessingResumed(key, pipelineGeneration)
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            availableSubtitleTracks = tracks,
                            subtitleTrackStatus = if (tracks.isEmpty()) {
                                "未找到可下载的字幕轨；仍可导入自己的字幕文件。"
                            } else {
                                "找到 " + tracks.size + " 个字幕轨。下载是可选的，不下载也会继续处理视频。"
                            },
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "Could not list subtitle tracks", e)
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            availableSubtitleTracks = emptyList(),
                            subtitleTrackStatus = "暂时无法读取字幕列表，可稍后重试或导入本地字幕。",
                        )
                    }
                }
            }

            // ---- 2. 字幕行 ----
            val db = AppDatabase.get(this)
            val selectedTranslationId = getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)
            // Keep signed media URLs out of subtitle-cache keys. The playback/history identity
            // remains unchanged so the original page can still be resumed from local history.
            // Match the session isolation used by the downloaded-audio cache. A signed URL can
            // serve different private media to different cookie sessions or page origins.
            val cacheKeyBase = remoteMediaUrl?.let {
                MediaUrlCacheIdentity.remoteSessionKey(it, remoteMediaReferrer, remoteMediaCookie)
            } ?: key
            val providerFingerprint = translationProviderFingerprint(selectedTranslationId)
            val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
            val storedAsrProvider = settingsPrefs.getString("asr_provider", "local") ?: "local"
            val asrProvider = storedAsrProvider.takeIf {
                it == "local" || it == "elevenlabs" || it == "cartesia" || it == "kawa"
            } ?: "local"
            val localAsrModelId = io.translatetube.local.data.ModelFiles.selectedLocalAsrModelId(this)
            val cloudAsrLanguageIdentity = when (asrProvider) {
                "cartesia" -> settingsPrefs.getString("asr_cartesia_language", "en") ?: "en"
                "kawa" -> settingsPrefs.getString("asr_kawa_language", "auto") ?: "auto"
                else -> "auto"
            }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val asrCacheIdentity = if (asrProvider == "local") {
                "local-$localAsrModelId"
            } else {
                "$asrProvider-$cloudAsrLanguageIdentity"
            }
            val baseTranslationCacheKey = TranslationCacheIdentity.forProvider(
                baseKey = cacheKeyBase,
                activeId = selectedTranslationId,
                importedLines = sourceImportedLines,
                previousKey = resumeState?.translationCacheKey,
                providerFingerprint = providerFingerprint,
            )
            // An ASR provider switch must never reuse captions/translations generated by a different
            // recognizer. Keep imported subtitle tasks independent of ASR settings.
            val translationCacheKey = when {
                !sourceImportedLines.isNullOrEmpty() -> baseTranslationCacheKey
                asrProvider == "local" -> "$baseTranslationCacheKey|local-asr-$localAsrModelId-timeline-v3"
                else -> "$baseTranslationCacheKey|cloud-asr-$asrCacheIdentity-timeline-v4"
            }
            updateUiForSession(key, pipelineGeneration) { it.copy(translationCacheKey = translationCacheKey) }
            val sourceSubtitleCacheKey = when {
                // YouTube rolling captions were normalized to one cue per line in v5.
                // Keep the existing local-model cache where possible; isolate cloud ASR by provider.
                videoId != null -> "$key|source|youtube-v5" + when (asrProvider) {
                    "local" -> "|asr-$localAsrModelId"
                    else -> "|asr-$asrCacheIdentity-cloud-v1"
                }
                remoteMediaUrl != null && asrProvider == "local" ->
                    "$cacheKeyBase|source|remote-asr-$localAsrModelId-timed-v3"
                remoteMediaUrl != null -> "$cacheKeyBase|source|remote-asr-$asrCacheIdentity-timed-v4"
                localUri != null && asrProvider == "local" ->
                    "$key|source|local-asr-$localAsrModelId-timed-v3"
                else -> "$key|source|local-asr-$asrCacheIdentity-timed-v4"
            }
            // 首次处理显示字幕读取阶段；完整成功快照恢复时不再展示解析、转写和翻译阶段。
            if (restoreCompletedSnapshot) {
                updateUiForSession(key, pipelineGeneration) { it.copy(phase = Phase.READY, progress = "") }
            } else if (resumeState == null || resumeState.lines.isEmpty()) {
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(phase = Phase.PARSING, progress = "正在检查本地字幕文件与缓存…")
                }
            } else {
                val resumeNeedsTranslation = !isChineseLanguage(resumeState.sourceLang) &&
                    resumeState.lines.any { needsSubtitleTranslation(it, resumeState.sourceLang) }
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = if (resumeNeedsTranslation) Phase.TRANSLATING else Phase.SYNTHESIZING,
                        progress = if (resumeNeedsTranslation) {
                            "继续翻译尚未完成的字幕…"
                        } else {
                            "原文与中文字幕已就绪，正在准备配音…"
                        },
                    )
                }
            }
            // 先读本地缓存，再决定是否请求 YouTube 字幕，避免重试重复下载。
            val storedTranslationCache = if (resumeState == null) db.subtitleCacheDao().get(translationCacheKey) else null
            // Older builds may have persisted a progress placeholder (for example, "正在翻译")
            // as every row's Chinese text. Never trust such cache entries: retranslate from the
            // cached original subtitles instead of displaying the placeholder as completed output.
            var cached = if (manualTranslationRequestedKeys.contains(key)) {
                null
            } else {
                storedTranslationCache?.takeIf { isUsableTranslationCache(it.linesJson) }
            }
            if (storedTranslationCache != null && cached == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring invalid/stale translation cache for $key; will translate the source subtitles again"
                )
                db.subtitleCacheDao().delete(translationCacheKey)
            }
            suspend fun fetchAndCacheSourceLines(): Pair<List<SubLine>, String>? {
                val id = videoId ?: return null
                awaitProcessingResumed(key, pipelineGeneration)
                updateUiForSession(key, pipelineGeneration) { it.copy(phase = Phase.PARSING, progress = "正在下载并读取字幕文件…") }
                val fetched = NewPipeRepo.subtitles(id) ?: return null
                awaitProcessingResumed(key, pipelineGeneration)
                val lang = fetched.second.takeIf { it.isNotBlank() } ?: "auto"
                val fetchedLines = fetched.first
                    .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
                    .mapIndexed { index, line -> line.copy(index = index) }
                if (fetchedLines.isEmpty()) return null

                val sourceArr = org.json.JSONArray()
                for ((index, line) in fetchedLines.withIndex()) {
                    if ((index and 0xff) == 0) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    sourceArr.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                    )
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ensureCurrentSession(key, pipelineGeneration)
                db.subtitleCacheDao().put(
                    io.translatetube.local.data.SubtitleCacheEntity(
                        sourceSubtitleCacheKey, lang, sourceArr.toString(), System.currentTimeMillis(),
                        videoKey = key,
                    )
                )
                // A valid caption track supersedes any interrupted ASR transcript for this video.
                db.subtitleCacheDao().delete("$sourceSubtitleCacheKey|asr-progress-v2")
                return fetchedLines to lang
            }

            var sourceCached = if (
                resumeState == null && (videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()
            ) {
                db.subtitleCacheDao().get(sourceSubtitleCacheKey)
            } else null
            var restoredSourceLines: List<SubLine>? = sourceCached?.let { source ->
                runCatching {
                    val sourceArr = org.json.JSONArray(source.linesJson)
                    val restored = ArrayList<SubLine>(sourceArr.length())
                    for (i in 0 until sourceArr.length()) {
                        if ((i and 0xff) == 0) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        }
                        val o = sourceArr.getJSONObject(i)
                        restored += SubLine(i, o.getLong("s"), o.getLong("e"), o.getString("t"))
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    restored.takeIf { rows ->
                        SubtitleCacheValidationPolicy.hasValidTimeline(
                            rows.map { row -> Triple(row.startMs, row.endMs, row.text) }
                        )
                    }
                }.getOrNull()
            }
            var restoredSourceLang: String? = restoredSourceLines?.let {
                sourceCached?.srcLang?.takeIf { lang -> lang.isNotBlank() }
                    ?: io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(it.asSequence().map { cue -> cue.text }.asIterable())
            }

            // Older versions could cache English captions even when the actual YouTube audio was
            // Korean (or another language). Validate populated YouTube source caches against the
            // source-audio metadata, so valid ASR checkpoints remain reusable and are not
            // invalidated merely because the caption-selection policy changed.
            if (videoId != null && sourceCached != null && restoredSourceLines != null) {
                val originalAudioLanguage = try {
                    io.translatetube.local.yt.NewPipeRepo.sourceAudioLanguageHint(videoId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w(
                        "PlaybackService",
                        "Could not validate cached YouTube source language; preserving cache",
                        failure,
                    )
                    null
                }
                val detectedCacheLanguage = io.translatetube.local.subs.SubtitleLanguageDetector
                    .detectFromCues(restoredSourceLines.asSequence().map { cue -> cue.text }.asIterable())
                val detectedCacheLanguageCode =
                    io.translatetube.local.yt.YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(detectedCacheLanguage)
                val cachedContentLanguage = detectedCacheLanguageCode
                    ?: io.translatetube.local.yt.YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(sourceCached.srcLang)
                val expectedContentLanguage =
                    io.translatetube.local.yt.YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(originalAudioLanguage)
                if (expectedContentLanguage != null && cachedContentLanguage != null &&
                    expectedContentLanguage != cachedContentLanguage
                ) {
                    android.util.Log.w(
                        "PlaybackService",
                        "Discarding cached YouTube source text because its language does not match original audio " +
                            "(audio=$expectedContentLanguage, cache=$cachedContentLanguage)",
                    )
                    db.subtitleCacheDao().delete(sourceSubtitleCacheKey)
                    sourceCached = null
                    restoredSourceLines = null
                    restoredSourceLang = null
                    if (cached != null) {
                        db.subtitleCacheDao().delete(translationCacheKey)
                        cached = null
                    }
                } else if (expectedContentLanguage != null && detectedCacheLanguageCode == expectedContentLanguage) {
                    // The old cache's language label may be stale even when its text is correct.
                    // Use the verified text language for the next translation pass.
                    restoredSourceLang = expectedContentLanguage
                }
            }

            if (sourceCached != null && restoredSourceLines != null) {
                // The completed source cache is authoritative if a crash happened between its
                // write and deletion of the partial-ASR checkpoint.
                db.subtitleCacheDao().delete("$sourceSubtitleCacheKey|asr-progress-v2")
            }
            if (sourceCached != null && restoredSourceLines == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring malformed source subtitle cache for $videoId; fetching subtitles/ASR again"
                )
                db.subtitleCacheDao().delete(sourceSubtitleCacheKey)
                // A legacy video-level translation cache cannot be proven to belong to this source.
                if (sourceImportedLines.isNullOrEmpty()) {
                    if (cached != null) db.subtitleCacheDao().delete(translationCacheKey)
                    cached = null
                }
            } else if (
                cached != null &&
                sourceImportedLines.isNullOrEmpty() &&
                restoredSourceLines != null &&
                !translationCacheMatchesSource(cached.linesJson, restoredSourceLines)
            ) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring translation cache for $videoId because it belongs to different source subtitle cues"
                )
                db.subtitleCacheDao().delete(translationCacheKey)
                cached = null
            } else if (
                cached != null &&
                sourceImportedLines.isNullOrEmpty() &&
                videoId != null &&
                sourceCached == null
            ) {
                // Old versions may have stored an imported subtitle translation under this generic
                // video key. Fetch the source once to validate it before trusting that legacy cache.
                val fetched = fetchAndCacheSourceLines()
                if (fetched == null) {
                    android.util.Log.w(
                        "PlaybackService",
                        "Cannot validate legacy translation cache for $videoId without source cues"
                    )
                    db.subtitleCacheDao().delete(translationCacheKey)
                    cached = null
                } else {
                    restoredSourceLines = fetched.first
                    restoredSourceLang = fetched.second
                    if (!translationCacheMatchesSource(cached.linesJson, fetched.first)) {
                        android.util.Log.w(
                            "PlaybackService",
                            "Discarding legacy translation cache for $videoId; source subtitle cues do not match"
                        )
                        db.subtitleCacheDao().delete(translationCacheKey)
                        cached = null
                    }
                }
            }

            if (!restoredSourceLang.isNullOrBlank()) srcLang = restoredSourceLang
            var lines: List<SubLine>? = resumeState?.lines?.takeIf { it.isNotEmpty() }
            if (resumeState == null && cached == null && sourceImportedLines.isNullOrEmpty() &&
                (videoId != null || localUri != null || remoteMediaUrl != null)) {
                if (restoredSourceLines != null) {
                    lines = restoredSourceLines
                    srcLang = restoredSourceLang
                        ?: io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(
                            restoredSourceLines.asSequence().map { it.text }.asIterable()
                        )
                    android.util.Log.i(
                        "PlaybackService",
                        "Using cached/validated source subtitles for $key (${restoredSourceLines.size} cues)"
                    )
                } else {
                    fetchAndCacheSourceLines()?.let { (fetchedLines, lang) ->
                        lines = fetchedLines
                        srcLang = lang
                    }
                }
            }

            val resolvedProcessingFlow = when {
                resumeState != null -> resumeState.processingFlow
                !sourceImportedLines.isNullOrEmpty() -> ProcessingFlow.IMPORTED_SUBTITLES
                videoId != null && restoredSourceLines != null ->
                    ReadyHistorySessionConfigStore.savedProcessingFlow(this@PlaybackService, key)
                        ?.takeIf { it == ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION || it == ProcessingFlow.YOUTUBE_SUBTITLES }
                        ?: ProcessingFlow.YOUTUBE_SUBTITLES
                videoId != null && lines == null && cached == null -> ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION
                videoId != null -> ProcessingFlow.YOUTUBE_SUBTITLES
                remoteMediaUrl != null -> ProcessingFlow.WEB_SPEECH_RECOGNITION
                localUri != null -> ProcessingFlow.LOCAL_SPEECH_RECOGNITION
                else -> ProcessingFlow.AUTO
            }
            updateUiForSession(key, pipelineGeneration) { it.copy(processingFlow = resolvedProcessingFlow) }

            if (lines == null && cached == null && sourceImportedLines.isNullOrEmpty()) {
                val asrApiKey = io.translatetube.local.asr.CloudTranscriptionSettings.apiKey(this, asrProvider)
                val cartesiaLanguage = getSharedPreferences("settings", MODE_PRIVATE).getString("asr_cartesia_language", "en") ?: "en"
                val kawaLanguage = getSharedPreferences("settings", MODE_PRIVATE).getString("asr_kawa_language", "auto") ?: "auto"
                val cloudLanguage = if (asrProvider == "kawa") kawaLanguage else cartesiaLanguage
                val missingLocalModel = asrProvider == "local" &&
                    !io.translatetube.local.data.ModelFiles.asrReady(this)
                val missingCloudKey = asrProvider != "local" && asrApiKey.isBlank()
                // ---- 2b. 无字幕 → 先提取音频（落缓存），再判断转写引擎是否就绪 ----
                // 音频提取无条件先执行：转写模型没装也要把音频准备好，这样用户装完
                // 模型重新打开本视频时直接复用缓存开始转写，不需要再次联网提取。
                awaitProcessingResumed(key, pipelineGeneration)
                updateUiForSession(key, pipelineGeneration) { it.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "无字幕，正在提取视频音频…") }
                val audioFile: Any = when {
                    videoId != null -> obtainAsrAudioFile(videoId, key, "复用已下载音频，避免重复下载…")
                    remoteMediaUrl != null -> obtainRemoteMediaAsrFile(
                        remoteMediaUrl,
                        remoteMediaReferrer,
                        remoteMediaCookie,
                        key,
                        "复用网页视频音频缓存，避免重复下载…",
                    )
                    else -> localUri ?: throw IllegalStateException("无音频源")
                }
                val leasedAudioPath = (audioFile as? File)
                    ?.takeIf { videoId != null || remoteMediaUrl != null }
                    ?.absolutePath
                if (missingLocalModel || missingCloudKey) {
                    val hint = when (asrProvider) {
                        "local" -> {
                            val modelName = io.translatetube.local.data.ModelFiles.localAsrModelDisplayName(localAsrModelId)
                            "视频音频已提取并缓存（约 ${((audioFile as? File)?.length() ?: 0L) / 1024 / 1024} MB）。请在设置 → 转写引擎中下载$modelName；安装后重新打开本视频，直接复用已提取的音频自动转写并配音。"
                        }
                        "elevenlabs" -> "视频音频已提取并缓存。已选择 ElevenLabs 云端转写，请先在设置 → 转写引擎填写 API Key；填写后重新打开本视频自动转写。"
                        "cartesia" -> "视频音频已提取并缓存。已选择 Cartesia 云端转写，请先在设置 → 转写引擎填写 API Key；填写后重新打开本视频自动转写。"
                        else -> "视频音频已提取并缓存。已选择 Kawa 云端转写，请先在设置 → 转写引擎填写 API Key；填写后重新打开本视频自动转写。"
                    }
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            noModelHint = hint,
                            waitingForTranscriptionEngine = true,
                            waitingForDubbingEngine = false,
                            isPlaying = false,
                            phase = if (it.phase == Phase.ERROR) it.phase else Phase.TRANSCRIBING,
                            progress = "视频音频已提取完成；转写等待引擎就绪" +
                                if (asrProvider == "local") "（设置 → 转写引擎 → 本地转写）" else "（设置 → 转写引擎）",
                        )
                    }
                    // Stop here instead of falling through with an empty transcript. Falling
                    // through skipped transcription/translation/dubbing and reached the final
                    // auto-play block, which started a local video as original-audio playback.
                    ensureCurrentSession(key, pipelineGeneration)
                    withContext(Dispatchers.Main.immediate) {
                        ensureCurrentSession(key, pipelineGeneration)
                        pm.pause()
                    }
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "PlaybackFlow",
                        "转写引擎未就绪，已暂停视频并停在转写阶段等待设置",
                        diagnosticContext = mapOf(
                            "视频来源" to if (localUri != null) "本地视频" else if (remoteMediaUrl != null) "网页视频" else "YouTube",
                            "当前阶段" to Phase.TRANSCRIBING.name,
                            "转写引擎配置" to asrProvider,
                            "等待本地模型" to missingLocalModel.toString(),
                            "等待云端 API Key" to missingCloudKey.toString(),
                            "自动播放" to "禁止；设置变更后重新处理并进入第五阶段",
                        ),
                    )
                    return
                } else {
                val partialAsrCacheKey = "${sourceSubtitleCacheKey}|asr-progress-v2"

                val asrLines: List<Triple<Long, Long, String>>
                val detectedAsrLang: String
                val asrTimelineDurationMs: Long
                val directCloudPreparation: Triple<io.translatetube.local.asr.CloudTranscription.EncodedAudioInput, Long, File?>? =
                    if (asrProvider == "elevenlabs" || asrProvider == "cartesia") {
                        updateUiForSession(key, pipelineGeneration) {
                            it.copy(
                                phase = Phase.TRANSCRIBING,
                                progress = "检查云端可直传音轨格式…",
                                noModelHint = null,
                                waitingForTranscriptionEngine = false,
                                waitingForDubbingEngine = false,
                            )
                        }
                        val preparationOwnerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                        try {
                            withContext(Dispatchers.IO) {
                                var stagedCloudAudio: File? = null
                                var transferredTempFile = false
                                try {
                                    if (audioFile is File) {
                                        val encodedSource =
                                            io.translatetube.local.asr.CloudTranscription.detectEncodedAudioInput(
                                                audioFile,
                                                asrProvider,
                                            )
                                        if (encodedSource != null) {
                                            val mediaInfo = runCatching {
                                                AudioExtract.probeMediaFile(audioFile)
                                            }.getOrNull()
                                            if (mediaInfo != null &&
                                                mediaInfo.audioTrackCount == 1 &&
                                                mediaInfo.videoTrackCount == 0 &&
                                                mediaInfo.durationMs > 0L
                                            ) {
                                                return@withContext Triple(
                                                    encodedSource,
                                                    mediaInfo.durationMs,
                                                    null,
                                                )
                                            }
                                        }
                                        stagedCloudAudio = AudioExtract.remuxAacAudioTrackToM4a(
                                            this@PlaybackService,
                                            file = audioFile,
                                            checkActive = { preparationOwnerJob?.ensureActive() },
                                            onProgressPercent = { percent ->
                                                updateUiForSession(key, pipelineGeneration) { state ->
                                                    if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                                        state.copy(progress = "整理压缩音轨（不解码） $percent%")
                                                    } else state
                                                }
                                            },
                                        )
                                    } else if (audioFile is Uri) {
                                        stagedCloudAudio = AudioExtract.remuxAacAudioTrackToM4a(
                                            this@PlaybackService,
                                            src = audioFile,
                                            checkActive = { preparationOwnerJob?.ensureActive() },
                                            onProgressPercent = { percent ->
                                                updateUiForSession(key, pipelineGeneration) { state ->
                                                    if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                                        state.copy(progress = "整理压缩音轨（不解码） $percent%")
                                                    } else state
                                                }
                                            },
                                        )
                                    }

                                    val preparedFile = stagedCloudAudio ?: return@withContext null
                                    val encodedInput =
                                        io.translatetube.local.asr.CloudTranscription.detectEncodedAudioInput(
                                            preparedFile,
                                            asrProvider,
                                        )
                                    val mediaInfo = runCatching {
                                        AudioExtract.probeMediaFile(preparedFile)
                                    }.getOrNull()
                                    if (encodedInput != null &&
                                        mediaInfo != null &&
                                        mediaInfo.audioTrackCount == 1 &&
                                        mediaInfo.videoTrackCount == 0 &&
                                        mediaInfo.durationMs > 0L
                                    ) {
                                        transferredTempFile = true
                                        Triple(encodedInput, mediaInfo.durationMs, preparedFile)
                                    } else {
                                        null
                                    }
                                } finally {
                                    if (!transferredTempFile) runCatching { stagedCloudAudio?.delete() }
                                }
                            }
                        } catch (cancelled: java.util.concurrent.CancellationException) {
                            releaseActiveAsrAudio(leasedAudioPath)
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.i(
                                "PlaybackService",
                                "Encoded cloud audio is not suitable for direct upload; using PCM fallback",
                                error,
                            )
                            null
                        }
                    } else {
                        null
                    }
                transientCloudUploadAudio = directCloudPreparation?.third

                if (directCloudPreparation != null) {
                    val encodedInput = directCloudPreparation.first
                    asrTimelineDurationMs = directCloudPreparation.second
                    val directResult = try {
                        withContext(Dispatchers.IO) {
                            val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                            val result = io.translatetube.local.asr.CloudTranscription.transcribe(
                                provider = asrProvider,
                                apiKey = asrApiKey,
                                language = cloudLanguage,
                                wavFile = encodedInput.file,
                                durationMs = directCloudPreparation.second,
                                checkActive = { ownerJob?.ensureActive() },
                                onProgress = { message ->
                                    updateUiForSession(key, pipelineGeneration) { state ->
                                        if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                            state.copy(progress = message)
                                        } else state
                                    }
                                },
                                encodedAudio = encodedInput,
                            )
                            ownerJob?.ensureActive()
                            updateUiForSession(key, pipelineGeneration) {
                                it.copy(progress = "云端转写完成，正在整理字幕…")
                            }
                            val language = io.translatetube.local.subs.SubtitleLanguageDetector
                                .resolveTranscriptionLanguageFromCues(
                                    result.language,
                                    result.cues.asSequence().take(120).map { it.third }.asIterable(),
                                )
                            result.cues to language
                        }
                    } finally {
                        releaseActiveAsrAudio(leasedAudioPath)
                        transientCloudUploadAudio?.delete()
                        transientCloudUploadAudio = null
                    }
                    asrLines = directResult.first
                    detectedAsrLang = directResult.second
                    if (audioFile is File && (videoId != null || remoteMediaUrl != null)) {
                        withContext(Dispatchers.IO) {
                            HlsAudioDownloader.cleanupSegmentCache(audioFile)
                        }
                    }
                } else {
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = Phase.TRANSCRIBING,
                        progress = "解码音频…",
                        noModelHint = null,
                        waitingForTranscriptionEngine = false,
                        waitingForDubbingEngine = false,
                    )
                }
                val pcmOwnerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                // A prompt cancellation can discard a successful withContext result before its
                // caller receives it. Keep a temporary ownership reference so that PCM file can
                // still be deleted in that narrow race instead of lingering until stale cleanup.
                val decodedPcmHolder =
                    java.util.concurrent.atomic.AtomicReference<AudioExtract.PcmFile?>(null)
                val pcmFile = try {
                    withContext(Dispatchers.IO) {
                        val decoded = if (audioFile is File) {
                            AudioExtract.decodeTo16kMonoFile(
                                this@PlaybackService, null, audioFile,
                                checkActive = { pcmOwnerJob?.ensureActive() },
                                onProgressPercent = { percent ->
                                    updateUiForSession(key, pipelineGeneration) { state ->
                                        if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                            state.copy(progress = "解码音频 ${percent}%")
                                        } else state
                                    }
                                },
                            )
                        } else {
                            AudioExtract.decodeTo16kMonoFile(
                                this@PlaybackService, audioFile as Uri, null,
                                checkActive = { pcmOwnerJob?.ensureActive() },
                                onProgressPercent = { percent ->
                                    updateUiForSession(key, pipelineGeneration) { state ->
                                        if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                            state.copy(progress = "解码音频 ${percent}%")
                                        } else state
                                    }
                                },
                            )
                        }
                        decodedPcmHolder.set(decoded)
                        decoded
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    decodedPcmHolder.getAndSet(null)?.file?.delete()
                    // Cancellation is not evidence of a corrupt download; keep it for resumption.
                    throw cancelled
                } catch (error: Exception) {
                    decodedPcmHolder.getAndSet(null)?.file?.delete()
                    // A remote download that the platform cannot decode must not stay in the cache
                    // forever. Otherwise every user retry reuses the same unusable bytes and fails
                    // before ASR can start. Successful decodes still retain audio for ASR retries.
                    if (audioFile is File && (videoId != null || remoteMediaUrl != null)) {
                        val removed = if (!audioFile.exists()) {
                            true
                        } else {
                            // This pipeline owns one lease. Retire the failed file only if no
                            // concurrent ASR/diarization reader owns an additional lease.
                            activeAsrAudioPaths.deleteIfLeaseCountAtMost(
                                audioFile.absolutePath,
                                allowedLeaseCount = 1,
                            ) { audioFile.delete() }
                        }
                        File(audioFile.parentFile, audioFile.name + ".part").delete()
                        android.util.Log.w(
                            "PlaybackService",
                            "Discarded remote audio cache after decode failure (removed=$removed)",
                            error,
                        )
                        val recoveryAdvice = if (removed) {
                            "已清理无法解码的音频缓存；重试时会重新获取音轨。"
                        } else {
                            "音频解码失败，且缓存文件无法删除；重试可能再次遇到同一错误，请清理应用缓存后重试。"
                        }
                        throw IllegalStateException(
                            "$recoveryAdvice 原因：${error.message ?: error.javaClass.simpleName}",
                            error,
                        )
                    }
                    throw error
                } finally {
                    releaseActiveAsrAudio(leasedAudioPath)
                }
                // Claim ownership before any further suspension point; the outer finally block
                // now cleans this file if HLS-cache cleanup or later ASR work is cancelled.
                transientPcmFile = pcmFile
                decodedPcmHolder.set(null)
                // Keep HLS parts when decoding fails so the next attempt can reuse downloaded
                // segments. Once MediaExtractor has accepted the merged file, discard the copies.
                if (audioFile is File && (videoId != null || remoteMediaUrl != null)) {
                    withContext(Dispatchers.IO) {
                        HlsAudioDownloader.cleanupSegmentCache(audioFile)
                    }
                }
                ensureCurrentSession(key, pipelineGeneration)
                // The transcript clock must use the duration of the exact PCM file sent to ASR.
                // Provider timestamps are relative to this full recording, not to each displayed line.
                asrTimelineDurationMs = pcmFile.durationMs
                val (decodedAsrLines, decodedAsrLang) = if (asrProvider == "local") {
updateUiForSession(key, pipelineGeneration) { it.copy(progress = "语音分段 0%") }
                val segs = withContext(Dispatchers.IO) {
                    vadSegmentsFromFile(
                        this@PlaybackService,
                        pcmFile,
                        checkActive = { pcmOwnerJob?.ensureActive() },
                        onProgressPercent = { percent ->
                            updateUiForSession(key, pipelineGeneration) { state ->
                                if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                    state.copy(progress = "语音分段 ${percent}%")
                                } else state
                            }
                        },
                    )
                }
                ensureCurrentSession(key, pipelineGeneration)
                if (segs.isEmpty()) throw IllegalStateException("未检测到语音内容")
                // PCM stays on disk; VAD timestamps and the current segment are loaded as needed.
                val asrSignature = AsrTranscriptCheckpointPolicy.signature(pcmFile, segs) {
                    pcmOwnerJob?.ensureActive()
                }
                var restoredProcessedSegments = 0
                var restoredAsrRows = emptyList<Triple<Long, Long, String>>()
                var restoredAsrLanguage = ""
                val storedPartialAsr = if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()) {
                    db.subtitleCacheDao().get(partialAsrCacheKey)
                } else null
                if (storedPartialAsr != null) {
                    val restored = try {
                        val checkpoint = org.json.JSONObject(storedPartialAsr.linesJson)
                        if (checkpoint.optInt("version", -1) != 2) {
                            throw IllegalStateException("ASR 检查点版本不兼容")
                        }
                        val processed = checkpoint.optInt("processedSegments", -1)
                        val total = checkpoint.optInt("totalSegments", -1)
                        val savedSignature = checkpoint.optString("signature")
                        if (!AsrTranscriptCheckpointPolicy.canResume(
                                savedSignature,
                                asrSignature,
                                total,
                                segs.size,
                                processed,
                            )
                        ) {
                            throw IllegalStateException("ASR 分段检查点与当前音频不匹配")
                        }
                        val savedRows = checkpoint.optJSONArray("rows")
                            ?: throw IllegalStateException("ASR 检查点缺少字幕列表")
                        // Token-aligned Whisper output can produce several captions per VAD segment.
                        // Keep a generous cap against corrupt cache payloads without assuming 1 row/segment.
                        val maxCheckpointRows = minOf(processed.toLong() * 64L, 100_000L).toInt()
                        if (savedRows.length() > maxCheckpointRows) {
                            throw IllegalStateException("ASR 检查点字幕数量异常")
                        }
                        val restoredRows = (0 until savedRows.length()).map { index ->
                            if ((index and 0xff) == 0) pcmOwnerJob?.ensureActive()
                            val row = savedRows.optJSONObject(index)
                                ?: throw IllegalStateException("ASR 检查点第 ${index + 1} 行格式无效")
                            Triple(
                                row.optLong("s", Long.MIN_VALUE),
                                row.optLong("e", Long.MIN_VALUE),
                                row.optString("t").trim(),
                            )
                        }
                        // Previous builds checked every row against segs.take(processed), creating a
                        // new list and rescanning completed segments for each row (quadratic on long
                        // captions). Validate order, exact bounds and duplicates with one hash set.
                        if (!AsrTranscriptCheckpointPolicy.areRowsValid(restoredRows, segs.take(processed))) {
                            throw IllegalStateException("ASR 检查点包含重复、无效或未完成的字幕行")
                        }
                        PartialAsrTranscript(processed, restoredRows, storedPartialAsr.srcLang)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        // Cancellation is control flow, not corrupt checkpoint data. Keep the
                        // saved progress intact so the next attempt can resume without redoing ASR.
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (restored == null) {
                        android.util.Log.w("PlaybackService", "Discarding incompatible partial ASR checkpoint for $key")
                        db.subtitleCacheDao().delete(partialAsrCacheKey)
                    } else {
                        restoredProcessedSegments = restored.processedSegments
                        restoredAsrRows = restored.rows
                        restoredAsrLanguage = restored.sourceLang
                        android.util.Log.i(
                            "PlaybackService",
                            "Resuming ASR for $key at segment ${restoredProcessedSegments}/${segs.size}",
                        )
                    }
                }

                val localAsrResult = localAsrMutex.withLock {
                    ensureCurrentSession(key, pipelineGeneration)
                    withContext(Dispatchers.IO) {
                        val selectedModelId = io.translatetube.local.data.ModelFiles.selectedLocalAsrModelId(this@PlaybackService)
                        val engine = localAsrEngine?.takeIf { localAsrEngineModelId == selectedModelId }
                            ?: run {
                                releaseLocalAsr()
                                val playbackState = ui.value
                                val asrThreads = LocalAsrPerformancePolicy.recommendedThreadCount(
                                    modelId = selectedModelId,
                                    processorCount = Runtime.getRuntime().availableProcessors(),
                                    isPlaying = playbackState.isPlaying,
                                    dubbingEnabled = playbackState.dubbingOn,
                                )
                                android.util.Log.i(
                                    "PlaybackService",
                                    "Local ASR engine=$selectedModelId threads=$asrThreads " +
                                        "cpuCount=${Runtime.getRuntime().availableProcessors()} " +
                                        "playing=${playbackState.isPlaying} dubbing=${playbackState.dubbingOn}",
                                )
                                val created: LocalAsrEngine = if (selectedModelId == "sensevoice") {
                                    SenseVoiceEngine(this@PlaybackService, asrThreads)
                                } else {
                                    WhisperEngine(this@PlaybackService, asrThreads)
                                }
                                localAsrEngine = created
                                localAsrEngineModelId = selectedModelId
                                created
                            }
                        if (restoredProcessedSegments < segs.size) {
                            // A shared engine may remember the previous video's detected language.
                            // Reset it for this transcript, then use the saved checkpoint language as fallback.
                            engine.clearDetectedLanguage()
                        }
                        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                        val rows = restoredAsrRows.toMutableList()
                        var processed = restoredProcessedSegments
                        // Keep one file descriptor and byte buffer for the entire local-ASR pass.
                        // Checkpoint writes still happen after every small segment group below.
                        pcmFile.openReader { ownerJob?.ensureActive() }.use { sharedPcmReader ->
                            while (processed < segs.size) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val chunkStart = processed
                            val chunkEnd = minOf(chunkStart + 3, segs.size)
                            val chunkRows = engine.transcribeBySegments(
                                pcmFile,
                                segs.subList(chunkStart, chunkEnd),
                                checkActive = { ownerJob?.ensureActive() },
                                sharedPcmReader = sharedPcmReader,
                            ) { done, _ ->
                                val completedSegment = segs.getOrNull((chunkStart + done - 1).coerceAtLeast(0))
                                val progressTimeMs = completedSegment?.second ?: 0L
                                val progressMinutes = progressTimeMs / 60_000L
                                val progressSeconds = (progressTimeMs / 1_000L) % 60L
                                val progressLabel = "转写中 ${chunkStart + done}/${segs.size} 段（音频进度 ${progressMinutes}分${progressSeconds}秒）"
                                updateUiForSession(key, pipelineGeneration) { state ->
                                    if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                        state.copy(progress = progressLabel)
                                    } else state
                                }
                            }
                            rows.addAll(chunkRows)
                            processed = chunkEnd

                            if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()) {
                                ensureCurrentSession(key, pipelineGeneration)
                                val rowsJson = org.json.JSONArray()
                                for ((rowIndex, row) in rows.withIndex()) {
                                    if ((rowIndex and 0xff) == 0) ownerJob?.ensureActive()
                                    rowsJson.put(
                                        org.json.JSONObject()
                                            .put("s", row.first)
                                            .put("e", row.second)
                                            .put("t", row.third)
                                    )
                                }
                                ownerJob?.ensureActive()
                                ensureCurrentSession(key, pipelineGeneration)
                                val language = engine.lastLang.takeIf { it.isNotBlank() }
                                    ?: restoredAsrLanguage
                                val checkpointJson = org.json.JSONObject()
                                    .put("version", 2)
                                    .put("signature", asrSignature)
                                    .put("totalSegments", segs.size)
                                    .put("processedSegments", processed)
                                    .put("rows", rowsJson)
                                ownerJob?.ensureActive()
                                db.subtitleCacheDao().put(
                                    io.translatetube.local.data.SubtitleCacheEntity(
                                        partialAsrCacheKey,
                                        language,
                                        checkpointJson.toString(),
                                        System.currentTimeMillis(),
                                        videoKey = key,
                                    )
                                )
                            }
                            }
                        }
                        val language = if (restoredProcessedSegments >= segs.size) {
                            restoredAsrLanguage.takeIf { it.isNotBlank() }
                                ?: io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(
                                    rows.asSequence().map { it.third }.asIterable()
                                )
                        } else {
                            engine.lastLang.takeIf { it.isNotBlank() }
                                ?: restoredAsrLanguage.takeIf { it.isNotBlank() }
                                ?: "en"
                        }
                        if (shouldReleaseLocalAsrForMemoryPressure() && localAsrEngine === engine) {
                            // The recognizer is native-memory heavy. Release only under reported
                            // memory pressure; healthy phones can keep it warm for another ASR job.
                            localAsrEngine = null
                            localAsrEngineModelId = null
                            runCatching { engine.close() }
                                .onFailure { android.util.Log.w("PlaybackService", "Local ASR release under memory pressure failed", it) }
                            android.util.Log.i("PlaybackService", "Released local ASR recognizer after transcription because of memory pressure")
                        }
                        rows to language
                    }
                }
                    localAsrResult
                } else {
                    val providerName = when (asrProvider) { "elevenlabs" -> "ElevenLabs"; "cartesia" -> "Cartesia"; else -> "Kawa" }
                    updateUiForSession(key, pipelineGeneration) { it.copy(progress = "正在准备 " + providerName + " 云端转写音频…") }
                    val removedStaleCloudWavs = runCatching {
                        io.translatetube.local.asr.CloudTranscription.cleanupStaleWavStages(cacheDir)
                    }.onFailure {
                        android.util.Log.w("PlaybackService", "Stale cloud transcription WAV cleanup failed", it)
                    }.getOrDefault(0)
                    if (removedStaleCloudWavs > 0) {
                        android.util.Log.i("PlaybackService", "Removed $removedStaleCloudWavs stale cloud transcription WAV file(s)")
                    }
                    val cloudWav = File.createTempFile("asr-cloud-", ".wav", cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                            io.translatetube.local.asr.CloudTranscription.writeWavFile(
                                pcmFile,
                                cloudWav,
                                checkActive = { ownerJob?.ensureActive() },
                                onProgressPercent = { percent ->
                                    updateUiForSession(key, pipelineGeneration) { state ->
                                        if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                            state.copy(progress = "封装云端音频 ${percent}%")
                                        } else state
                                    }
                                },
                            )
                            val result = io.translatetube.local.asr.CloudTranscription.transcribe(
                                provider = asrProvider,
                                apiKey = asrApiKey,
                                language = cloudLanguage,
                                wavFile = cloudWav,
                                durationMs = pcmFile.durationMs,
                                checkActive = { ownerJob?.ensureActive() },
                                onProgress = { message ->
                                    updateUiForSession(key, pipelineGeneration) { state ->
                                        if (state.key == key && state.phase == Phase.TRANSCRIBING) {
                                            state.copy(progress = message)
                                        } else state
                                    }
                                },
                            )
                            ownerJob?.ensureActive()
                            updateUiForSession(key, pipelineGeneration) { it.copy(progress = "云端转写完成，正在整理字幕…") }
                            // Sample timed cues directly instead of allocating a concatenated transcript.
                            val language = io.translatetube.local.subs.SubtitleLanguageDetector
                                .resolveTranscriptionLanguageFromCues(
                                    result.language,
                                    result.cues.asSequence().take(120).map { it.third }.asIterable(),
                                )
                            result.cues to language
                        }
                    } finally {
                        cloudWav.delete()
                    }
                }
                asrLines = decodedAsrLines
                detectedAsrLang = decodedAsrLang
                ensureCurrentSession(key, pipelineGeneration)
                transientPcmFile?.file?.delete()
                transientPcmFile = null
                }
                // Only disposable local temporary files may be removed before the transcript checkpoint.
                // YouTube audio remains cached until its ASR subtitles have been saved successfully.
                if (audioFile is File && videoId == null && remoteMediaUrl == null) {
                    audioFile.delete()
                    if (transientAudio == audioFile) transientAudio = null
                }
                // Some providers/segment boundaries can return cues out of order or with an end
                // slightly beyond the uploaded PCM duration. Normalize once before caching,
                // translating, highlighting, and dubbing so every consumer shares the same timeline.
                val timelineOwnerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                val normalizedAsrLines = io.translatetube.local.asr.TranscriptTimelinePolicy.normalize(
                    asrLines,
                    asrTimelineDurationMs,
                    checkActive = { timelineOwnerJob?.ensureActive() },
                )
                // 网页/本地视频的转写结果：去掉 [Music]、♪ 和 uh/um，清理后为空的条目直接丢弃，
                // 避免把音效标注和口头语送去翻译、再被配音读出来。
                val transcribedLines = normalizedAsrLines
                    .mapNotNull { (startMs, endMs, text) ->
                        SubtitleParsers.cleanSpokenText(text).takeIf { it.isNotEmpty() }
                            ?.let { Triple(startMs, endMs, it) }
                    }
                    .mapIndexed { index, (startMs, endMs, text) ->
                        SubLine(index, startMs, endMs, text)
                    }
                if (transcribedLines.isEmpty()) {
                    if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()) {
                        db.subtitleCacheDao().delete(partialAsrCacheKey)
                    }
                    throw IllegalStateException(
                        when {
                            videoId != null ->
                                "语音识别没有生成有效字幕；已保留下载的音频，重试时会复用缓存，不会再次下载。"
                            remoteMediaUrl != null ->
                                "网页视频语音识别没有生成有效字幕。请确认媒体源包含可读取的语音音轨；加密或不完整的分片流暂不支持。"
                            else ->
                                "语音识别没有生成有效字幕，请检查本地视频是否包含可识别语音。"
                        }
                    )
                }
                lines = transcribedLines
                srcLang = io.translatetube.local.subs.SubtitleLanguageDetector
                    .resolveTranscriptionLanguageFromCues(
                        detectedAsrLang,
                        transcribedLines.asSequence().map { it.text }.asIterable(),
                    )
                withContext(Dispatchers.IO) {
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "Transcription",
                        "原文语音转写与字幕时间轴整理完成",
                        diagnosticContext = mapOf(
                            "视频来源" to when {
                                videoId != null -> "YouTube"
                                remoteMediaUrl != null -> "网页视频/远程媒体"
                                localUri != null -> "本地视频"
                                else -> "其他"
                            },
                            "转写服务/引擎配置" to asrProvider,
                            "选中的本地转写模型" to (localAsrEngineModelId ?: "未使用或未确定"),
                            "服务商返回的语言" to detectedAsrLang.ifBlank { "未提供" },
                            "时间轴确认后的源语言" to srcLang,
                            "转写阶段原始有效时间片数量" to normalizedAsrLines.size.toString(),
                            "清理音效和口头语后的字幕行数" to transcribedLines.size.toString(),
                            "被清理掉的空/非语音字幕行数" to (normalizedAsrLines.size - transcribedLines.size).coerceAtLeast(0).toString(),
                            "转写音频时长 ms" to asrTimelineDurationMs.toString(),
                            "首句开始时间 ms" to (transcribedLines.firstOrNull()?.startMs?.toString() ?: "无"),
                            "末句结束时间 ms" to (transcribedLines.lastOrNull()?.endMs?.toString() ?: "无"),
                            "字幕时间轴是否有效" to "已通过 TranscriptTimelinePolicy.normalize",
                            "是否准备缓存原文字幕" to ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()).toString(),
                        ),
                    )
                }

                // Keep the completed ASR transcript as an independent checkpoint. The bounded
                // source-audio cache is retained for retries and later speaker diarization, while
                // this subtitle cache lets translation retry without running ASR again.
                ensureCurrentSession(key, pipelineGeneration)
                if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty() && transcribedLines.isNotEmpty()) {
                    val sourceArr = org.json.JSONArray()
                    for ((index, line) in transcribedLines.withIndex()) {
                        if ((index and 0xff) == 0) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        }
                        sourceArr.put(
                            org.json.JSONObject()
                                .put("s", line.startMs)
                                .put("e", line.endMs)
                                .put("t", line.text)
                        )
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    ensureCurrentSession(key, pipelineGeneration)
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            sourceSubtitleCacheKey,
                            srcLang,
                            sourceArr.toString(),
                            System.currentTimeMillis(),
                            videoKey = key,
                        )
                    )
                    db.subtitleCacheDao().delete(partialAsrCacheKey)
                    // Keep the bounded source-audio cache after ASR as well: user-triggered
                    // speaker diarization can reuse it without downloading the same media again.
                    if (videoId != null && audioFile is File) {
                        audioFile.setLastModified(System.currentTimeMillis())
                        pruneAsrAudioCache(asrAudioCacheDirectory(), keep = audioFile)
                    }
                    android.util.Log.i(
                        "PlaybackService",
                        "Cached ${transcribedLines.size} ASR source subtitle cues for retry reuse",
                    )
                }
                }
            }

            // 用户导入的字幕优先（SRT/VTT 导入入口）
            if (sourceImportedLines != null && sourceImportedLines!!.isNotEmpty()) {
                lines = sourceImportedLines
                srcLang = io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(
                    sourceImportedLines!!.asSequence().map { it.text }.asIterable()
                )
            }

            if (cached != null) {
                // 缓存命中：直接还原双语行
                val arr = org.json.JSONArray(cached.linesJson)
                val restored = ArrayList<SubLine>(arr.length())
                for (i in 0 until arr.length()) {
                    if ((i and 0xff) == 0) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    val o = arr.getJSONObject(i)
                    val originalText = o.getString("t")
                    val cachedTranslation = o.optString("zh")
                        .takeIf { it.isNotBlank() }
                        ?.let { SubtitleParsers.cleanTranslatedText(it) }
                    restored += SubLine(
                        i, o.getLong("s"), o.getLong("e"),
                        originalText, cachedTranslation, o.optInt("sp", -1),
                    )
                }
                // A legacy cache can have a blank source-language field. Preserve a trusted
                // language from the source cache, or re-detect from the restored original captions,
                // rather than accidentally treating Chinese source subtitles as foreign text.
                srcLang = cached.srcLang.takeIf { it.isNotBlank() }
                    ?: restoredSourceLang
                    ?: io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(restored.asSequence().map { it.text }.asIterable())
                // Re-check cached language against the original transcript too. Older releases
                // may have persisted a provider's incorrect "zh" hint for non-Chinese speech.
                srcLang = resolveSourceLanguageForCues(
                    restored.map { it.text },
                    io.translatetube.local.subs.SubtitleLanguageDetector
                        .resolveTranscriptionLanguageFromCues(
                            srcLang,
                            restored.asSequence().map { it.text }.asIterable(),
                        ),
                )
                lines = if (isChineseLanguage(srcLang)) {
                    SubtitleCacheValidationPolicy.restoreChineseSourceTranslations(restored)
                } else {
                    restored
                }
                val manualRestoredLines = awaitManualSubtitleTranslation(
                    key, lines.orEmpty(), srcLang, pipelineGeneration,
                )
                if (manualRestoredLines != null) {
                    lines = manualRestoredLines
                } else if (!isChineseLanguage(srcLang) && restored.any { line ->
                    needsSubtitleTranslation(line, srcLang)
                }) {
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            phase = Phase.TRANSLATING,
                            sourceLang = srcLang,
                            progress = "继续翻译缓存中尚未完成的字幕…",
                        )
                    }
                    lines = translateLines(key, restored, srcLang, translationCacheKey, selectedTranslationId, pipelineGeneration)
                }
            } else if (lines != null) {
                // ---- 3. 翻译 ----
                var translatedLines = lines!!
                // Validate the selected/source-cache language against the actual transcript before
                // deciding that translation can be skipped.
                srcLang = resolveSourceLanguageForCues(
                    translatedLines.map { it.text },
                    io.translatetube.local.subs.SubtitleLanguageDetector
                        .resolveTranscriptionLanguageFromCues(
                            srcLang,
                            translatedLines.asSequence().map { it.text }.asIterable(),
                        ),
                )
                val externalTranslatedLines = awaitManualSubtitleTranslation(
                    key, translatedLines, srcLang, pipelineGeneration,
                )
                if (externalTranslatedLines != null) {
                    translatedLines = externalTranslatedLines
                } else if (translatedLines.isNotEmpty() && !isChineseLanguage(srcLang)) {
                    val needsTranslation = translatedLines.any { line -> needsSubtitleTranslation(line, srcLang) }
                    if (needsTranslation) {
                        updateUiForSession(key, pipelineGeneration) { it.copy(phase = Phase.TRANSLATING, progress = "原文字幕已准备，正在翻译成中文（${translatedLines.size} 行）…") }
                        translatedLines = translateLines(key, translatedLines, srcLang, translationCacheKey, selectedTranslationId, pipelineGeneration)
                    }
                } else if (translatedLines.isNotEmpty()) {
                    // Source captions are not translator output: preserve repeated words and text that
                    // happens to resemble a progress label instead of running translation cleanup on it.
                    translatedLines = SubtitleCacheValidationPolicy.restoreChineseSourceTranslations(translatedLines)
                }
                lines = translatedLines
                // 仅在译文填充率 >50% 时写缓存（避免失败结果被永久缓存）
                ensureCurrentSession(key, pipelineGeneration)
                var translatedRowCount = 0
                for ((index, line) in translatedLines.withIndex()) {
                    if ((index and 0xff) == 0) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    if (!line.zh.isNullOrEmpty()) translatedRowCount++
                }
                if (translatedRowCount > translatedLines.size / 2) {
                    val arr = org.json.JSONArray()
                    for ((rowIndex, line) in translatedLines.withIndex()) {
                        if ((rowIndex and 0xff) == 0) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        }
                        arr.put(
                            org.json.JSONObject()
                                .put("s", line.startMs)
                                .put("e", line.endMs)
                                .put("t", line.text)
                                .put("zh", line.zh ?: "")
                        )
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    ensureCurrentSession(key, pipelineGeneration)
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(translationCacheKey, srcLang, arr.toString(), System.currentTimeMillis(), videoKey = key)
                    )
                }
            }

            ensureCurrentSession(key, pipelineGeneration)
            // 仅 YouTube：把第一句起点校准到人声真正开口的时间（网页/本地不走这里）。
            val baseLines = lines ?: emptyList()
            val calibratedLines = if (videoId != null) calibrateYouTubeFirstCue(videoId, baseLines) else baseLines
            ensureCurrentSession(key, pipelineGeneration)
            val finalLines = applySavedLineOverrides(calibratedLines, key)
            updateUiForSession(key, pipelineGeneration) {
                it.copy(
                    lines = finalLines, totalLines = finalLines.size, sourceLang = srcLang,
                    progress = "", phase = if (restoreCompletedSnapshot) Phase.READY else Phase.SYNTHESIZING,
                )
            }

            // ---- 4. 流装配 ----
            if (!reuseMountedMedia) {
            if (videoId != null) {
                val streamInfo = NewPipeRepo.streams(videoId)
                ensureCurrentSession(key, pipelineGeneration)
                val choices = buildYouTubeStreamChoices(streamInfo)
                val candidates = choices.sources
                val audioUrl = choices.audioUrl
                val defaultQuality = choices.defaultQuality
                qualitySources = candidates
                qualityAudioUrl = audioUrl
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        qualityOptions = candidates.map { q -> q.label },
                    )
                }
                ensureCurrentSession(key, pipelineGeneration)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key, pipelineGeneration)
                    val accepted = pm.setYouTubeQuality(
                        videoUrl = defaultQuality.url,
                        audioUrl = audioUrl,
                        muxed = defaultQuality.muxed,
                        title = meta.title,
                        artist = meta.channel,
                        artworkUrl = meta.thumbUrl,
                    )
                    if (!accepted) {
                        throw IllegalStateException(pm.errorSink.value ?: "YouTube 默认清晰度初始化失败")
                    }
                    updateUiForSession(key, pipelineGeneration) { it.copy(selectedQuality = defaultQuality.label) }
                }
            } else {
                ensureCurrentSession(key, pipelineGeneration)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key, pipelineGeneration)
                    val sourceUri = remoteMediaUrl?.let(Uri::parse) ?: localUri
                    ?: throw IllegalStateException("无可播放的视频地址")
                val requestHeaders = buildMap {
                    remoteMediaReferrer.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let { put("Referer", it) }
                    remoteMediaCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                }
                pm.setUriSource(
                    sourceUri,
                    meta.title,
                    if (remoteMediaUrl != null) "网页视频" else "本地视频",
                    null,
                    requestHeaders,
                    userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() },
                )
                adaptiveQualitySourceReady = true
                refreshAdaptiveVideoQualityOptions()
                }
            }
            } // Keep the existing stream for a selective stage restart.

            // Drain any retired speech engine and unload the native ASR model before
            // allocating the selected TTS runtime. Reopening a history item can reuse ASR and
            // translation checkpoints, so it may arrive here quickly while memory is still high.
            awaitProcessingResumed(key, pipelineGeneration)
            awaitRetiredDubbingEngines()
            releaseLocalAsrBeforeDubbing(key, pipelineGeneration)
            awaitProcessingResumed(key, pipelineGeneration)

            // ---- 5. 配音引擎：必须严格使用设置里明确选择的引擎 ----
            if (finalLines.isNotEmpty()) {
                val selectedTts = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("tts_engine", "edge") ?: "edge"
                val selectedTtsName = ttsDisplayName(selectedTts)
                updateUiForSession(key, pipelineGeneration) { it.copy(ttsEngineName = selectedTtsName) }

                val selectedEngineReady = when (selectedTts) {
                    "system" -> true
                    "web" -> true
                    "edge" -> true
                    "kokoro" -> kokoroRuntimeReady()
                    "piper" -> piperModelReadyForChinese()
                    else -> false
                }
                // Chinese source material still respects the explicitly selected TTS engine.
                // If the selected engine is unavailable, the common waiting state below blocks
                // playback instead of silently degrading to source-only narration.
                if (selectedEngineReady) {
                    try {
                        // Pass the saved selection explicitly so a settings change during parsing
                        // cannot cause the readiness check and actual engine to disagree.
                        val tts: TtsEngine = makeTtsOffMainThread(selectedTts)
                        try {
                            ensureCurrentSession(key, pipelineGeneration)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            runCatching { tts.close() }
                            throw e
                        }
                        val d = try {
                            DubbingEngine(this, key, scope, pm.dubbingBus)
                        } catch (e: Exception) {
                            runCatching { tts.close() }
                            throw e
                        }
                        try {
                            ensureCurrentSession(key, pipelineGeneration)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            retireDubbing(d)
                            runCatching { tts.close() }
                            throw e
                        }
                        registerDubbingFailureHandler(d, tts, key)
                        try {
                            d.attach(tts, finalLines, sourceLanguage = srcLang, forceRegenerateDubbing = forceRegenerateDubbing)
                        } catch (e: Exception) {
                            retireDubbing(d)
                            runCatching { tts.close() }
                            throw e
                        }
                        try {
                            ensureCurrentSession(key, pipelineGeneration)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            retireDubbing(d)
                            throw e
                        }
                        if (!publishDubbingEngine(d, key, pipelineGeneration)) {
                            retireDubbing(d)
                            throw kotlinx.coroutines.CancellationException(
                                "视频处理任务已被新的打开请求替代，取消旧的配音引擎初始化",
                            )
                        }
                        updateUiForSession(key, pipelineGeneration) {
                            it.copy(
                                ttsEngineName = tts.name,
                                dubbingStatus = "${tts.name} 已初始化，正在准备首句配音",
                                noModelHint = null,
                                waitingForTranscriptionEngine = false,
                                waitingForDubbingEngine = false,
                            )
                        }
                        dubCollectJob = scope.launch {
                            kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                                .collect { (prefix, readyCount) ->
                                    updateUiForCurrentDubbingEngine(d, key) {
                                        it.copy(preparedUntil = prefix, preparedCount = readyCount)
                                    }
                                }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val failureState = ui.value
                        io.translatetube.local.util.AppLogStore.error(
                            this@PlaybackService,
                            "TTS",
                            "所选配音引擎初始化失败：$selectedTtsName",
                            e,
                            diagnosticContext = mapOf(
                                "视频来源" to when {
                                    videoId != null -> "YouTube"
                                    remoteMediaUrl != null -> "网页视频/远程媒体"
                                    localUri != null -> "本地视频"
                                    else -> "本地或已导入字幕任务"
                                },
                                "配音引擎 ID" to selectedTts,
                                "配音引擎名称" to selectedTtsName,
                                "系统 TTS 合成模式" to getSharedPreferences("settings", MODE_PRIVATE).getString("system_tts_mode", "generated").orEmpty(),
                                "Piper 模型 ID" to getSharedPreferences("settings", MODE_PRIVATE).getString("piper_model", "未选择").orEmpty(),
                                "当前处理阶段" to failureState.phase.name,
                                "当前处理进度" to failureState.progress.take(180),
                                "字幕总数" to failureState.totalLines.toString(),
                                "当前已发布字幕行数" to failureState.lines.size.toString(),
                                "已准备配音行数" to failureState.preparedCount.toString(),
                                "处理代数" to pipelineGeneration.toString(),
                            ),
                        )
android.util.Log.e("PlaybackService", "Unable to initialize selected TTS engine $selectedTts", e)
                        val reason = e.message ?: e.javaClass.simpleName
                        updateUiForSession(key, pipelineGeneration) {
                            it.copy(
                                ttsEngineName = selectedTtsName,
                                dubbingStatus = "${selectedTtsName} 初始化失败",
                                noModelHint = when (selectedTts) {
                                    "system" -> "系统 TTS 初始化失败：$reason。当前严格使用手机系统 TTS，未切换本地模型。请检查手机的文字转语音服务、中文语音数据和可用音色，然后重试。"
                                    "piper" -> "已选择 Piper 中文配音，但初始化失败：$reason。不会自动切换到其他引擎。请检查是否选择并下载了中文 Piper 模型。"
                                    "edge" -> "微软 Edge TTS 初始化失败：$reason。请检查网络连接后重试。"
                                    "web" -> "在线语音引擎初始化失败：$reason。请检查网络连接后重试。"
                                    else -> "所选配音引擎「$selectedTtsName」初始化失败：$reason。请检查对应引擎配置后重试。"
                                },
                                waitingForTranscriptionEngine = false,
                                waitingForDubbingEngine = true,
                                // A constructor failure means there is no dubbing output to replace
                                // the source track; never keep the entire video muted.
                                muteOriginal = false,
                                error = null
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (videoKey == key && ui.value.key == key) safelySetOriginalVolume(1f, "source audio restore requested")
                        }
                    }
                } else {
                    val message = when (selectedTts) {
                        "kokoro" -> "已选择 Kokoro 配音模型，但模型尚未就绪。应用不会自动切换到系统 TTS；下载完整的配音模型后再试。"
                        "piper" -> {
                            val modelId = getSharedPreferences("settings", MODE_PRIVATE)
                                .getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium"
                            val definition = io.translatetube.local.tts.PiperModels.byId(modelId)
                            when {
                                definition == null -> "当前 Piper 模型编号无效，请在设置中重新选择中文 Piper 模型。"
                                !definition.supportsChineseOutput -> "当前视频的配音目标是中文，所选 Piper 模型为「${definition.langLabel}」，不能用于中文配音。请在设置中选择中文 Piper 模型。"
                                !io.translatetube.local.tts.PiperModels.modelReady(this, modelId) -> "已选择 Piper 中文配音，但模型尚未下载或文件不完整。请在设置 → 配音设置中下载中文模型。"
                                else -> "Piper 中文配音初始化失败，请检查模型文件后重试。"
                            }
                        }
                        else -> "设置中保存的配音引擎「$selectedTts」无效。请重新选择 Kokoro、Piper 或系统 TTS。应用不会擅自切换引擎。"
                    }
                    updateUiForSession(key, pipelineGeneration) {
                        it.copy(
                            ttsEngineName = if (selectedTts == "kokoro") "Kokoro（未就绪）" else selectedTtsName,
                            dubbingStatus = "${selectedTtsName} 尚未就绪",
                            noModelHint = message,
                            waitingForTranscriptionEngine = false,
                            waitingForDubbingEngine = true,
                            // The selected local model is unavailable, so no voice can be heard.
                            // Keep the selected engine unchanged but return to audible source audio.
                            muteOriginal = false,
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (videoKey == key && ui.value.key == key) safelySetOriginalVolume(1f, "source audio restore requested")
                    }
                }
            }
            // Generated-PCM engines finish the entire subtitle queue before playback starts.
            // Direct System TTS has no pre-generated PCM queue, so it keeps a bounded first-cue wait.
            val startupDubbing = dubbing
            if (startupDubbing != null) {
                val immediateMode = startupDubbing.usesImmediateTimelinePlayback
                if (!immediateMode) {
                    awaitDubbingSynthesisBeforePlayback(
                        sessionKey = key,
                        pipelineGeneration = pipelineGeneration,
                        engine = startupDubbing,
                        lines = finalLines,
                    )
                } else {
                    val startupSpeechCueIndices = DubbingStartupBufferPolicy.spokenCueIndices(finalLines)
                    val startupBuffer = DubbingStartupBufferPolicy.plan(
                        lines = finalLines,
                        spokenCueIndices = startupSpeechCueIndices,
                        immediateMode = true,
                        synthesisTimelineLagFactor = startupDubbing.synthesisTimelineLagFactor,
                        synthesisSampleCount = startupDubbing.synthesisTimelineLagSampleCount,
                    )
                    if (startupBuffer.lineIndices.isNotEmpty()) {
                        fun cueFinished(index: Int): Boolean =
                            startupDubbing.isPrepared(index) || startupDubbing.isSilent(index)

                        val waitStartedNs = System.nanoTime()
                        val deadlineNs = waitStartedNs +
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                // 用户要求视频立即开播：首句直播缓冲最多 1 秒（原 3 秒），
                                // 未就绪的句先原声，TTS 引擎就绪后自然接上时间轴。
                                startupBuffer.waitTimeoutMs.coerceAtMost(1_000L),
                            )
                        startupDubbing.prioritizeAround(
                            startupBuffer.lineIndices.first(),
                            lookAhead = minOf(12, maxOf(2, startupBuffer.lineIndices.size - 1)),
                        )
                        while (true) {
                            awaitProcessingResumed(key, pipelineGeneration)
                            ensureCurrentSession(key, pipelineGeneration)
                            val ready = startupBuffer.lineIndices
                                .take(startupBuffer.minimumCueCount)
                                .all(::cueFinished)
                            if (ready || System.nanoTime() >= deadlineNs) break
                            kotlinx.coroutines.delay(100L)
                        }
                        val firstSpeechReady = startupSpeechCueIndices.firstOrNull()?.let(::cueFinished) ?: true
                        if (!firstSpeechReady) {
                            updateUiForSession(key, pipelineGeneration) {
                                it.copy(progress = "首句系统 TTS 尚未就绪，开始播放后会按时间轴朗读")
                            }
                        }
                    }
                }
            }
            ensureCurrentSession(key, pipelineGeneration)
            saveHistory(meta)
            ensureCurrentSession(key, pipelineGeneration)
            updateUiForSession(key, pipelineGeneration) {
                it.copy(
                    phase = Phase.READY,
                    engineName = it.engineName.ifEmpty { "本地引擎" },
                    progress = VideoProcessingFlowPolicy.readyProgress(
                        waitingForTranscriptionEngine = it.waitingForTranscriptionEngine,
                        waitingForDubbingEngine = it.waitingForDubbingEngine,
                    ),
                )
            }
            io.translatetube.local.util.AppLogStore.resolveIssue(
                context = this@PlaybackService,
                issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey("PlaybackPipeline", key),
                message = "视频处理流程已重新成功完成",
                diagnosticContext = mapOf(
                    "恢复阶段" to Phase.READY.name,
                    "恢复证据" to "本次视频处理管线完成并进入 READY 阶段",
                    "字幕行数" to ui.value.lines.size.toString(),
                ),
            )
            // A changed ASR/translation/TTS configuration requires a fully fresh speech timeline.
            // Never restore the previous watch position for that run: play the newly synthesized
            // Chinese dubbing from the beginning, consistently for YouTube, remote web media and local files.
            val resumeSec = try {
                db.videoDao().byKey(key)?.watchedSec ?: 0L
            } catch (_: Exception) { 0L }
            val totalMs = meta.durationSec * 1000
            if (forceRegenerateDubbing) {
                // Do not leave the prior run's watch position as the history resume point after
                // changing engines. The new subtitle/voice timeline is about to start at zero.
                try {
                    db.videoDao().setWatched(key, 0L)
                } catch (error: Exception) {
                    io.translatetube.local.util.AppLogStore.warning(
                        this@PlaybackService,
                        "PlaybackFlow",
                        "重新合成配音后清零历史续播位置失败",
                        error,
                        diagnosticContext = mapOf(
                            "视频来源" to diagnosticMediaSourceType(ui.value),
                            "本次要求起播位置 ms" to "0",
                            "保留的旧续播位置秒" to resumeSec.toString(),
                        ),
                    )
                }
            }
            if (
                !forceRegenerateDubbing &&
                resumeSec > 10 &&
                (totalMs <= 0 || resumeSec * 1000 < totalMs - 30_000)
            ) {
                ensureCurrentSession(key, pipelineGeneration)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key, pipelineGeneration)
                    startPlaybackWithMountedDubbing(resumeSec * 1000) {
                        ensureCurrentSession(key, pipelineGeneration)
                    }
                }
            } else {
                val startPositionMs = if (forceRegenerateDubbing) 0L else null
                if (forceRegenerateDubbing) {
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "PlaybackFlow",
                        "引擎切换后的配音已重新生成，视频从开头起播",
                        diagnosticContext = mapOf(
                            "视频来源" to when {
                                key.startsWith("youtube:", ignoreCase = true) -> "YouTube"
                                key.startsWith("remote:", ignoreCase = true) -> "网页视频/远程媒体"
                                localUri != null || key.startsWith("local:", ignoreCase = true) -> "本地视频"
                                else -> "其他视频源"
                            },
                            "本次起播位置" to "0 ms（视频开头）",
                            "忽略的历史续播位置秒" to resumeSec.toString(),
                            "强制重新合成配音" to "true",
                        ),
                    )
                }
                ensureCurrentSession(key, pipelineGeneration)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key, pipelineGeneration)
                    startPlaybackWithMountedDubbing(startPositionMs) {
                        ensureCurrentSession(key, pipelineGeneration)
                    }
                }
                if (forceRegenerateDubbing) {
                    val actualStartPositionMs = runCatching { pm.position() }.getOrDefault(-1L)
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "PlaybackFlow",
                        "重新合成后的起播位置检查完成",
                        diagnosticContext = mapOf(
                            "视频来源" to diagnosticMediaSourceType(ui.value),
                            "请求起播位置 ms" to "0",
                            "起播调用后播放器位置 ms" to actualStartPositionMs.toString(),
                            "播放器播放意图" to runCatching { pm.player.playWhenReady.toString() }.getOrDefault("读取失败"),
                            "播放器实际状态" to runCatching { pm.player.isPlaying.toString() }.getOrDefault("读取失败"),
                            "是否重新合成配音" to "true",
                        ),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val failureState = ui.value
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService,
                "PlaybackPipeline",
                "视频解析、转写、翻译或配音流程失败",
                e,
                diagnosticContext = mapOf(
                    "视频来源" to when {
                        key.startsWith("youtube:") -> "YouTube"
                        key.startsWith("remote:") -> "网页视频/远程媒体"
                        key.startsWith("webpage:") -> "网页链接"
                        key.startsWith("local:") -> "本地视频"
                        else -> "未知来源"
                    },
                    "当前阶段" to failureState.phase.name,
                    "当前处理进度/子步骤" to failureState.progress.take(240),
                    "当前错误状态" to failureState.error.orEmpty().take(240),
                    "当前转写引擎是否等待就绪" to failureState.waitingForTranscriptionEngine.toString(),
                    "当前翻译引擎" to failureState.engineName.ifBlank { "未确定" },
                    "当前配音引擎" to failureState.ttsEngineName.ifBlank { "未确定" },
                    "字幕源语言" to failureState.sourceLang.ifBlank { "未确定" },
                    "字幕已发布行数" to failureState.lines.size.toString(),
                    "字幕总行数" to failureState.totalLines.toString(),
                    "已准备配音行数" to failureState.preparedCount.toString(),
                    "当前字幕索引" to failureState.currentLine.toString(),
                    "原声是否静音" to (failureState.originalVolume <= 0f).toString(),
                    "处理代数" to pipelineGeneration.toString(),
                    ),
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey("PlaybackPipeline", key),
                )
android.util.Log.e("PlaybackService", "Video pipeline failed for key=$key", e)
            if (e is TranslationFailureException) {
                if (ui.value.key == key && videoKey == key) {
                    runCatching { safelySetOriginalVolume(1f, "source audio restore requested") }
                }
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "API 翻译失败",
                        noModelHint = null,
                        muteOriginal = false,
                        waitingForTranscriptionEngine = false,
                        waitingForDubbingEngine = false,
                    )
                }
            } else {
            // A TTS constructor can fail before the subtitle list is published to UI.
            // Restore original volume immediately so default mute never silences the whole video.
            if (ui.value.key == key && videoKey == key && ui.value.dubbingOn) {
                runCatching { safelySetOriginalVolume(1f, "source audio restore requested") }
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "视频解析或配音初始化失败",
                        noModelHint = "配音初始化失败，已恢复视频原声以避免无声播放。系统 TTS 请检查手机文字转语音服务/中文语音数据；Kokoro/Piper 请检查模型文件是否完整。",
                        muteOriginal = false,
                    )
                }
            } else {
                updateUiForSession(key, pipelineGeneration) { it.copy(phase = Phase.ERROR, progress = "", error = e.message ?: "视频解析失败") }
            }
            }
        } catch (e: OutOfMemoryError) {
            // Native ASR/TTS engines can exhaust the app heap/native budget when a history replay
            // switches engines in the same service process. Prefer original-audio playback and an
            // actionable warning over letting this recoverable initialization failure kill the app.
            val memoryFailureState = ui.value
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService, "PlaybackPipeline",
                "视频处理或配音初始化时发生 OutOfMemoryError，已启动内存恢复流程",
                e,
                diagnosticContext = mapOf(
                    "故障阶段" to memoryFailureState.phase.name,
                    "最近处理步骤" to memoryFailureState.progress.ifBlank { memoryFailureState.failedProgress }.take(200),
                    "视频会话标识（去除查询参数）" to key.substringBefore('?').take(160),
                    "字幕行数" to memoryFailureState.lines.size.toString(),
                    "已翻译行数" to memoryFailureState.translatedLineCount.toString(),
                    "已准备配音行数" to memoryFailureState.preparedCount.toString(),
                    "当前字幕索引" to memoryFailureState.currentLine.toString(),
                    "配音引擎" to memoryFailureState.ttsEngineName.ifBlank { "未确定" },
                    "本次处理状态" to "捕获内存错误后释放本地转写、配音和音频缓存资源，尝试恢复原声",
                ),
            )
            android.util.Log.e("PlaybackService", "Insufficient memory while preparing video/TTS for key=${key.substringBefore('?').take(80)}", e)
            runCatching {
                withContext(Dispatchers.IO) {
                    localAsrMutex.withLock { releaseLocalAsr() }
                }
            }
            runCatching { retireCurrentDubbing() }
            runCatching { withContext(Dispatchers.IO) { awaitRetiredDubbingEngines() } }
            System.gc()
            val memoryMessage =
                "设备可用内存不足，已停止生成中文配音并恢复视频原声。请稍后重试，或在设置中选择占用更低的配音引擎。"
            if (ui.value.key == key && videoKey == key) {
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        phase = if (it.lines.isNotEmpty()) Phase.READY else Phase.ERROR,
                        progress = "",
                        error = if (it.lines.isNotEmpty()) null else memoryMessage,
                        noModelHint = memoryMessage,
                        dubbingStatus = "内存不足，配音已暂停",
                        muteOriginal = false,
                        activeDubbingLine = -1,
                        waitingForTranscriptionEngine = false,
                        waitingForDubbingEngine = false,
                        dubbingHasErrors = true,
                    )
                }
                runCatching {
                    withContext(Dispatchers.Main) {
                        if (videoKey == key && ui.value.key == key && ::pm.isInitialized) {
                            safelySetOriginalVolume(1f, "source audio restore requested")
                            pm.play()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // A non-fatal Error (for example, an extractor/library linkage error) must be shown
            // as a playback failure rather than escaping a Dispatchers.Default coroutine.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            val nonVmFailureState = ui.value
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService, "PlaybackPipeline",
                "视频处理流程捕获到非虚拟机致命错误",
                e,
                diagnosticContext = mapOf(
                    "故障阶段" to nonVmFailureState.phase.name,
                    "最近处理步骤" to nonVmFailureState.progress.ifBlank { nonVmFailureState.failedProgress }.take(200),
                    "视频会话标识（去除查询参数）" to key.substringBefore('?').take(160),
                    "配音引擎" to nonVmFailureState.ttsEngineName.ifBlank { "未确定" },
                    "字幕行数" to nonVmFailureState.lines.size.toString(),
                    "当前字幕索引" to nonVmFailureState.currentLine.toString(),
                    "错误类型" to e.javaClass.name,
                    "处理策略" to "将异常显示在播放器状态中，避免非 VM 错误逃逸到后台协程",
                ),
            )
            android.util.Log.e("PlaybackService", "Fatal-looking non-VM error in video pipeline for key=${key.substringBefore('?').take(80)}", e)
            updateUiForSession(key, pipelineGeneration) {
                it.copy(
                    phase = Phase.ERROR,
                    progress = "",
                    error = "视频解析/播放器异常（${e.javaClass.simpleName}）：${e.message ?: "请查看崩溃日志"}"
                )
            }
        } finally {
            transientPcmFile?.file?.delete()
            transientCloudUploadAudio?.delete()
            transientAudio?.delete()
        }
    }

    /** 按当前选定的翻译引擎处理字幕；配置不可用时明确报错，不静默替换引擎。 */
    /** 按小批次翻译；每批完成就写入真实进度，避免整批请求期间进度停滞。 */
    private suspend fun translateLines(
        key: String,
        lines: List<SubLine>,
        srcLang: String,
        cacheKeyOverride: String? = null,
        activeIdOverride: Long? = null,
        pipelineGeneration: Long? = null,
    ): List<SubLine> {
        val translationStartedAtNanos = System.nanoTime()
        val earlyExternalTranslation = awaitManualSubtitleTranslation(key, lines, srcLang, pipelineGeneration)
        if (earlyExternalTranslation != null) {
            withContext(Dispatchers.IO) {
                io.translatetube.local.util.AppLogStore.info(
                    this@PlaybackService,
                    "Translation",
                    "外部翻译字幕已准备完成，跳过自动翻译批次",
                    diagnosticContext = mapOf(
                        "输入字幕行数" to lines.size.toString(),
                        "输出字幕行数" to earlyExternalTranslation.size.toString(),
                        "源语言代码" to srcLang,
                        "目标语言代码" to "zh",
                        "流程" to "导入外部译文并沿用原时间轴",
                    ),
                )
            }
            return earlyExternalTranslation
        }
        // Keep previously published subtitle rows immutable so Compose observes every translated batch.
        val translationJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        updateUiForSession(key, pipelineGeneration) { state ->
            if (state.key == key && state.translatedLineCount != 0) {
                state.copy(translatedLineCount = 0)
            } else state
        }
        fun checkTranslationActive(index: Int) {
            if ((index and 0xff) == 0) translationJob?.ensureActive()
        }
        val initialRows = ArrayList<SubLine>(lines.size)
        for ((index, line) in lines.withIndex()) {
            checkTranslationActive(index)
            initialRows += line
        }
        var updatedLines: List<SubLine> = initialRows
        val db = AppDatabase.get(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val activeId = activeIdOverride ?: prefs.getLong("active_api_id", -1L)
        val profiles = db.apiProfileDao().enabled()
        val tgt = "zh"
        val plain = ArrayList<String>(lines.size)
        for ((index, line) in lines.withIndex()) {
            checkTranslationActive(index)
            plain += line.text
        }
        // Resolve every built-in translation engine through the same factory. Adding a new
        // local model to EngineFactory then automatically places it on the normal subtitle,
        // cache, retry, and dubbing pipeline instead of requiring another hand-maintained list.
        var engine: TranslationEngine? = EngineFactory.getBuiltinEngine(activeId, this)
        var engineName = when (activeId) {
            EngineFactory.ID_FREE_GEMINI -> "Google Gemini (免费)"
            EngineFactory.ID_FREE_DEEPSEEK -> "DeepSeek (免费)"
            else -> engine?.displayName.orEmpty()
        }
        val active = profiles.firstOrNull { it.id == activeId }
        if (engine == null && active != null) {
            engine = try {
                EngineFactory.fromProfile(active).also { engineName = active.name }
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "所选翻译服务“${active.name}”配置无法初始化：${failure.message ?: failure.javaClass.simpleName}。" +
                        "请检查 API 配置后重试；不会自动切换到其他翻译引擎。",
                    failure,
                )
            }
        }
        if (engine == null) {
            val localName = EngineFactory.localTranslationEngineName(activeId)
            if (localName != null) {
                throw IllegalStateException(
                    "$localName 本地翻译引擎未能创建。请进入本地翻译管理页检查模型状态；不会自动改用其他引擎。"
                )
            }
            throw IllegalStateException(
                "当前选择的翻译引擎（配置编号 $activeId）不存在或已停用。请在 API 管理中重新选择；不会自动切换到其他翻译引擎。"
            )
        }
        // Publish the selected engine before model/configuration preflight can fail. This keeps
        // the five-stage processing UI and its error details aligned with the newly selected engine.
        updateUiForSession(key, pipelineGeneration) { state ->
            if (state.key == key) state.copy(engineName = engineName) else state
        }
        // The Google hybrid engine can fall back to offline ML Kit. For these Google paths,
        // an unchanged proper name/acronym is a legitimate "no Chinese text" cue, not a reason
        // to abort every subtitle and prevent the video from playing.
        val sourceEchoAsUntranslatedFallback =
            EngineFactory.isLocalTranslationEngine(activeId) ||
                engine!!.id == "local" || engine!!.id == "mlkit-local" || engine!!.id == "opus-mt" ||
                engine!!.id == "small100" || engine!!.id == "google-hybrid" ||
                engine!!.id == "microsoft-web" || engine!!.id == "baidu-web"
        val pendingIndices = ArrayList<Int>()
        var completed = 0
        for (index in lines.indices) {
            checkTranslationActive(index)
            val line = lines[index]
            if (line.text.isBlank() || !needsSubtitleTranslation(line, srcLang)) {
                completed++
            } else {
                pendingIndices += index
            }
        }

        try {
            // Run local-engine preflight before announcing translation progress or entering retries.
            // The selected engine resolves an "auto" source using the full subtitle list.
            var src = SourceLanguagePolicy.sourceForTranslation(srcLang)
            val selectedMlKit = engine as? MlKitEngine
            val selectedOpus = engine as? OpusMtEngine
            val selectedSmall100 = engine as? Small100Engine
            val selectedSinglePair = engine as? SinglePairTranslationEngine
            if (pendingIndices.isNotEmpty()) {
                val preferredSource = prefs.getString("translation_source_language", "auto")
                    .orEmpty().ifBlank { "auto" }
                val selectedLocalId = EngineFactory.isLocalTranslationEngine(activeId)
                if (selectedLocalId && !SourceLanguagePolicy.isUndetermined(preferredSource)) {
                    val scriptDetected = io.translatetube.local.subs.SubtitleLanguageDetector.detectFromCues(plain)
                    val detectedSource = if (!SourceLanguagePolicy.isUndetermined(scriptDetected)) {
                        scriptDetected
                    } else {
                        io.translatetube.local.subs.MlKitSourceLanguageIdentifier.detect(plain)
                    }
                    if (!SourceLanguagePolicy.isUndetermined(detectedSource)) {
                        fun primaryLanguageCode(tag: String): String {
                            val primary = SourceLanguagePolicy.normalizeTag(tag)
                                .substringBefore('-').lowercase(java.util.Locale.ROOT)
                            return when (primary) {
                                "iw" -> "he"
                                "in" -> "id"
                                "nb", "nn" -> "no"
                                else -> primary
                            }
                        }
                        if (primaryLanguageCode(preferredSource) != primaryLanguageCode(detectedSource)) {
                            val zhLocale = java.util.Locale.SIMPLIFIED_CHINESE
                            val preferredName = java.util.Locale.forLanguageTag(preferredSource)
                                .getDisplayLanguage(zhLocale).ifBlank { preferredSource }
                            val detectedName = java.util.Locale.forLanguageTag(detectedSource)
                                .getDisplayLanguage(zhLocale).ifBlank { detectedSource }
                            throw IllegalStateException(
                                "手动选择的源语言是“$preferredName”，但字幕自动识别更像“$detectedName”。" +
                                    "请到“API 管理 → 本地翻译”选择正确语言或改为自动识别后重试；不会自动切换其他翻译引擎。"
                            )
                        }
                    }
                }
                when {
                    selectedMlKit != null -> src = selectedMlKit.validateConfiguration(srcLang, tgt, plain)
                    selectedOpus != null -> src = selectedOpus.validateConfiguration(srcLang, tgt, plain)
                    selectedSmall100 != null -> src = selectedSmall100.validateConfiguration(srcLang, tgt, plain)
                    selectedSinglePair != null -> src = selectedSinglePair.validateConfiguration(srcLang, tgt, plain)
                }
                if (src.isNotBlank()) {
                    updateUiForSession(key, pipelineGeneration) { it.copy(sourceLang = src) }
                }
            }
            val sourceLanguageForUi = src.ifBlank { srcLang }
            withContext(Dispatchers.IO) {
                io.translatetube.local.util.AppLogStore.info(
                    this@PlaybackService,
                    "Translation",
                    "中文字幕翻译任务已开始",
                    diagnosticContext = mapOf(
                        "视频来源" to when {
                            key.startsWith("youtube:") -> "YouTube"
                            key.startsWith("remote:") -> "网页视频/远程媒体"
                            key.startsWith("local:") -> "本地视频"
                            else -> "导入字幕/其他"
                        },
                        "翻译引擎名称" to engineName,
                        "翻译引擎 ID" to (engine?.id ?: "未知"),
                        "翻译配置编号" to activeId.toString(),
                        "服务商名称" to active?.name.orEmpty().ifBlank { "内置/本地" },
                        "模型名称" to active?.model.orEmpty().ifBlank { "不适用或未提供" },
                        "源语言代码" to sourceLanguageForUi,
                        "目标语言代码" to tgt,
                        "输入字幕行数" to lines.size.toString(),
                        "本次需要翻译的字幕行数" to pendingIndices.size.toString(),
                        "无需翻译/已具备有效译文行数" to completed.toString(),
                        "翻译批次总数" to ((pendingIndices.size + 9) / 10).toString(),
                        "单批字幕行数" to "10",
                        "是否有待翻译内容" to pendingIndices.isNotEmpty().toString(),
                        "是否处于外部译文等待模式" to ui.value.manualTranslationWaiting.toString(),
                    ),
                )
            }
            updateUiForSession(key, pipelineGeneration) {
                it.copy(
                    engineName = engineName,
                    sourceLang = sourceLanguageForUi,
                    lines = updatedLines,
                    totalLines = updatedLines.size,
                    translatedLineCount = completed.coerceAtMost(updatedLines.size),
                    progress = "翻译成中文 ${completed}/${updatedLines.size} 行",
                )
            }
            val batchSize = 10
            val translationCacheKey = cacheKeyOverride ?: "${key}|zh|${activeId}"
            // Checkpoint the first batch immediately, then every few successful batches and
            // always at the end. This bounds crash recovery loss while avoiding serializing the full
            // subtitle timeline to Room after every 10 translated cues.
            val translationBatches = pendingIndices.chunked(batchSize)
            for ((batchIndex, batchIndices) in translationBatches.withIndex()) {
                awaitManualSubtitleTranslation(key, lines, src.ifBlank { srcLang }, pipelineGeneration)?.let { return it }
                awaitProcessingResumed(key, pipelineGeneration)
                val batchStartedAtNanos = System.nanoTime()
                // The playback layer calls the engine once per small batch, so carry the previous
                // completed cues here; otherwise engine-local context resets at every boundary.
                val previousContext = batchIndices.firstOrNull()?.let { firstIndex ->
                    val contextIndices = io.translatetube.local.translate.TranslationContextPolicy
                        .mostRecentIndicesBefore(
                            size = updatedLines.size,
                            beforeIndex = firstIndex,
                            maxRows = 3,
                            checkActive = { translationJob?.ensureActive() },
                        ) { index ->
                            val line = updatedLines[index]
                            line.text.isNotBlank() &&
                                !needsSubtitleTranslation(line, src) &&
                                !SubtitleParsers.cleanTranslatedText(line.zh).isNullOrBlank()
                        }
                    contextIndices.takeIf { it.isNotEmpty() }?.let { indices ->
                        indices.map { updatedLines[it].text } to
                            indices.map { SubtitleParsers.cleanTranslatedText(updatedLines[it].zh).orEmpty() }
                    }
                }
                // Retry missing rows once. For Google/ML Kit, keep an isolated still-missing cue
                // on original audio if this batch or earlier rows already contain usable translations.
                var unresolvedIndices = batchIndices
                val resolvedBatch = linkedMapOf<Int, String>()
                var attempt = 0
                while (unresolvedIndices.isNotEmpty() && attempt < 2) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val batch = unresolvedIndices.map { plain[it] }
                    val batchTranslations = try {
                        engine!!.translateWithContext(batch, src, tgt, previousContext).also {
                            translationJob?.ensureActive()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        io.translatetube.local.util.AppLogStore.warning(
                            this@PlaybackService,
                            "Translation",
                            "字幕翻译批次失败，正在执行恢复处理",
                            e,
                            diagnosticContext = mapOf(
                                "翻译引擎 ID" to engine?.id.orEmpty(),
                                "翻译服务商名称" to active?.name.orEmpty().ifBlank { "本地/Google" },
                                "模型 ID" to active?.model.orEmpty().ifBlank { "未提供" },
                                "源语言代码" to src,
                                "目标语言代码" to tgt,
                                "本次翻译批次大小" to batch.size.toString(),
                                "本批起始字幕索引" to unresolvedIndices.firstOrNull().toString(),
                                "本批结束字幕索引" to unresolvedIndices.lastOrNull().toString(),
                                "当前重试次数" to attempt.toString(),
                                "已成功翻译行数" to completed.toString(),
                                "当前任务键类型" to when {
                                    ui.value.key.startsWith("youtube:") -> "YouTube"
                                    ui.value.key.startsWith("remote:") -> "网页视频/远程媒体"
                                    ui.value.key.startsWith("local:") -> "本地视频"
                                    else -> "其他"
                                },
                            ),
                        )
                        android.util.Log.w(
                            "PlaybackService",
                            "Translation batch failed for rows ${unresolvedIndices.firstOrNull()}..${unresolvedIndices.lastOrNull()}",
                            e,
                        )
                        // Distinguish API provider failures from Google offline language-pack errors.
                        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        val functionMissing = detail.contains("HTTP 404", ignoreCase = true) &&
                            detail.contains("Function", ignoreCase = true) &&
                            detail.contains("Not found for account", ignoreCase = true)
                        val message = if (active != null) {
                            if (functionMissing) {
                                "API 翻译失败（" + active.name + "）：服务端返回 404，当前模型的推理函数/部署未找到。模型列表能拉取并不代表该模型当前可调用；请更换同服务商可用模型，或请服务商检查后端部署。这不是 Google 离线包问题。原始响应：" + detail
                            } else {
                                "API 翻译失败（" + active.name + "）：" + detail + "。请检查服务地址、模型 ID、API Key 权限和供应商状态。"
                            }
                        } else if (activeId == EngineFactory.ID_FREE_GEMINI) {
                            if (detail.contains("API Key", ignoreCase = true) || detail.contains("401", ignoreCase = true) || detail.contains("403", ignoreCase = true)) {
                                "Google Gemini 翻译失败：需要有效 API Key。请在 API 管理中点击 Google Gemini 卡片右侧「•••」填入您的 Gemini API Key（可前往 Google AI Studio 免费获取）。"
                            } else {
                                "Google Gemini 翻译失败：" + detail + "。请检查网络，或在 API 管理中检查 Gemini 配置。"
                            }
                        } else if (activeId == EngineFactory.ID_FREE_DEEPSEEK) {
                            if (detail.contains("API Key", ignoreCase = true) || detail.contains("401", ignoreCase = true) || detail.contains("403", ignoreCase = true)) {
                                "DeepSeek 翻译失败：需要有效 API Key。请在 API 管理中点击 DeepSeek 卡片右侧「•••」填入您的 DeepSeek API Key。"
                            } else {
                                "DeepSeek 翻译失败：" + detail + "。请检查网络，或在 API 管理中检查 DeepSeek 配置。"
                            }
                        } else if (EngineFactory.isLocalTranslationEngine(activeId)) {
                            val localName = EngineFactory.localTranslationEngineName(activeId) ?: "本地翻译模型"
                            "$localName 本地翻译失败：$detail。请检查对应模型或源语言设置，或进入本地翻译管理页处理后重试。"
                        } else if (activeId == EngineFactory.ID_MICROSOFT || activeId == EngineFactory.ID_BAIDU || activeId == EngineFactory.ID_DEEPL) {
                            val name = when (activeId) {
                                EngineFactory.ID_MICROSOFT -> "微软翻译"
                                EngineFactory.ID_DEEPL -> "DeepL 翻译"
                                else -> "百度翻译"
                            }
                            name + "失败：" + detail + "。这是免密钥的网页接口，可能被限流或已调整，请稍后重试，或在 API 管理中换用其他翻译引擎。"
                        } else {
                            "翻译失败：" + detail + "。请检查网络，或在 Google 翻译中下载对应离线语言包。"
                        }
                        throw TranslationFailureException(message, e)
                    }

                    val parsedBatch = io.translatetube.local.translate.TranslationBatchResult.from(
                        indices = unresolvedIndices,
                        rawTranslations = batchTranslations,
                        sourceTexts = unresolvedIndices.associateWith { plain[it] },
                        sourceLanguage = src,
                        sourceEchoAsUntranslatedFallback = sourceEchoAsUntranslatedFallback,
                    )
                    resolvedBatch.putAll(parsedBatch.translations)
                    unresolvedIndices = parsedBatch.missingIndices
                    attempt += 1
                    if (unresolvedIndices.isNotEmpty() && attempt < 2) {
                        android.util.Log.w(
                            "PlaybackService",
                            "Retrying incomplete translation response for ${unresolvedIndices.size} subtitle rows",
                        )
                        kotlinx.coroutines.delay(250L)
                    }
                }

                val hasUsableTranslation = resolvedBatch.values.any { it.isNotBlank() } ||
                    updatedLines.any { line ->
                        line.text.isNotBlank() &&
                            !needsSubtitleTranslation(line, src) &&
                            !SubtitleParsers.cleanTranslatedText(line.zh).isNullOrBlank()
                    }
                if (
                    unresolvedIndices.isNotEmpty() &&
                    sourceEchoAsUntranslatedFallback &&
                    hasUsableTranslation
                ) {
                    // Google/ML Kit returned useful Chinese for this batch, but a small number of
                    // cues stayed empty/status-only after retry. Preserve those source subtitles
                    // without Chinese speech instead of failing the whole video over one bad row.
                    android.util.Log.w(
                        "PlaybackService",
                        "Keeping ${unresolvedIndices.size} unresolved Google subtitle cues on original audio",
                    )
                    unresolvedIndices.forEach { resolvedBatch[it] = "" }
                    unresolvedIndices = emptyList()
                }

                if (unresolvedIndices.isNotEmpty()) {
                    throw TranslationFailureException(
                        "翻译结果不完整：重试后仍有 ${unresolvedIndices.size} 行未得到有效译文。已停止处理，避免在播放页把缺失内容显示为“正在翻译”。请重试或更换翻译引擎。",
                        IllegalStateException("Missing subtitle translation rows: ${unresolvedIndices.joinToString()}"),
                    )
                }

                val nextRows = ArrayList<SubLine>(updatedLines.size)
                for ((rowIndex, line) in updatedLines.withIndex()) {
                    checkTranslationActive(rowIndex)
                    nextRows += resolvedBatch[rowIndex]?.let { translation ->
                        line.copy(zh = translation)
                    } ?: line
                }
                translationJob?.ensureActive()
                updatedLines = nextRows
                completed += batchIndices.size
                updateUiForSession(key, pipelineGeneration) {
                    it.copy(
                        lines = updatedLines,
                        totalLines = updatedLines.size,
                        translatedLineCount = completed.coerceAtMost(updatedLines.size),
                        sourceLang = src,
                        progress = "翻译成中文 ${completed.coerceAtMost(updatedLines.size)}/${updatedLines.size} 行",
                    )
                }
                // Keep structured logs bounded: record the first, final and periodic successful batches.
                val completedBatchCount = batchIndex + 1
                if (batchIndex == 0 || completedBatchCount == translationBatches.size || completedBatchCount % 5 == 0) {
                    val batchResolvedCount = resolvedBatch.values.count { it.isNotBlank() }
                    val batchFallbackCount = resolvedBatch.values.count { it.isBlank() }
                    withContext(Dispatchers.IO) {
                        io.translatetube.local.util.AppLogStore.info(
                            this@PlaybackService,
                            "Translation",
                            "中文字幕翻译批次处理完成",
                            diagnosticContext = mapOf(
                                "翻译引擎名称" to engineName,
                                "翻译引擎 ID" to (engine?.id ?: "未知"),
                                "批次进度" to "${completedBatchCount}/${translationBatches.size}",
                                "本批字幕行数" to batchIndices.size.toString(),
                                "本批已获得有效译文行数" to batchResolvedCount.toString(),
                                "本批空译文/原声兜底行数" to batchFallbackCount.toString(),
                                "本批重试次数" to attempt.toString(),
                                "本批处理耗时 ms" to (((System.nanoTime() - batchStartedAtNanos).coerceAtLeast(0L)) / 1_000_000L).toString(),
                                "源语言代码" to src,
                                "目标语言代码" to tgt,
                                "当前累计已完成行数" to completed.coerceAtMost(updatedLines.size).toString(),
                                "字幕总行数" to updatedLines.size.toString(),
                                "翻译失败后是否采用原声兜底" to (batchFallbackCount > 0).toString(),
                            ),
                        )
                    }
                }
                // The first successful batch and final batch are always checkpointed.
                if (!io.translatetube.local.translate.TranslationCheckpointPolicy.shouldPersistCheckpoint(
                        completedBatchCount = completedBatchCount,
                        totalBatches = translationBatches.size,
                    )
                ) {
                    continue
                }
                // Persist only after the triggering batch is complete and every pending row is valid.
                val cacheRows = org.json.JSONArray()
                for ((rowIndex, line) in updatedLines.withIndex()) {
                    if ((rowIndex and 0xff) == 0) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    cacheRows.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                            .put("zh", line.zh ?: "")
                            .put("sp", line.speakerId)
                    )
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ensureCurrentSession(key, pipelineGeneration)
                db.subtitleCacheDao().put(
                    io.translatetube.local.data.SubtitleCacheEntity(
                        translationCacheKey, src, cacheRows.toString(), System.currentTimeMillis(),
                        videoKey = key,
                    )
                )
            }
            val externallyTranslatedFinal = awaitManualSubtitleTranslation(key, lines, src.ifBlank { srcLang }, pipelineGeneration)
            if (externallyTranslatedFinal != null) {
                withContext(Dispatchers.IO) {
                    io.translatetube.local.util.AppLogStore.info(
                        this@PlaybackService,
                        "Translation",
                        "外部翻译字幕已导入并匹配到原字幕时间轴",
                        diagnosticContext = mapOf(
                            "输入原文字幕行数" to lines.size.toString(),
                            "合并后字幕行数" to externallyTranslatedFinal.size.toString(),
                            "源语言代码" to src,
                            "目标语言代码" to "zh",
                            "合并规则" to "以原字幕时间戳为准，匹配译文文本",
                        ),
                    )
                }
                return externallyTranslatedFinal
            }
            withContext(Dispatchers.IO) {
                io.translatetube.local.util.AppLogStore.info(
                    this@PlaybackService,
                    "Translation",
                    "中文字幕翻译任务完成",
                    diagnosticContext = mapOf(
                        "翻译引擎名称" to engineName,
                        "翻译引擎 ID" to (engine?.id ?: "未知"),
                        "翻译配置编号" to activeId.toString(),
                        "源语言代码" to src,
                        "目标语言代码" to "zh",
                        "字幕总行数" to updatedLines.size.toString(),
                        "已获得非空中文译文行数" to updatedLines.count { !it.zh.isNullOrBlank() }.toString(),
                        "仍缺少中文译文行数" to updatedLines.count { needsSubtitleTranslation(it, src) && it.zh.isNullOrBlank() }.toString(),
                        "处理批次数" to translationBatches.size.toString(),
                        "累计耗时 ms" to (((System.nanoTime() - translationStartedAtNanos).coerceAtLeast(0L)) / 1_000_000L).toString(),
                        "本次是否有在线/本地引擎请求" to pendingIndices.isNotEmpty().toString(),
                        "结果已写入字幕缓存的检查方式" to "由调用方根据译文填充率与缓存完整性另行判断",
                    ),
                )
            }
            return updatedLines
        } finally {
            // Release native resources on success, failure, or cancellation, keeping memory bounded.
            runCatching { engine?.close() }
        }
    }

    private fun buildYouTubeStreamChoices(
        info: org.schabi.newpipe.extractor.stream.StreamInfo,
    ): YouTubeStreamChoices {
        val allVideoStreams = (info.videoStreams + info.videoOnlyStreams)
            .distinctBy { stream -> "${stream.height}|${stream.content}|${stream.isVideoOnly}" }

        android.util.Log.i(
            "PlaybackService",
            "YouTube stream inventory: muxed=" + info.videoStreams.size +
                ", videoOnly=" + info.videoOnlyStreams.size +
                ", muxedRes=" + info.videoStreams.map { it.height }.distinct().sorted() +
                ", videoOnlyRes=" + info.videoOnlyStreams.map { it.height }.distinct().sorted()
        )

        val normalized = allVideoStreams.map { stream ->
            YouTubeVideoCandidate(
                height = stream.height,
                url = stream.content,
                videoOnly = stream.isVideoOnly,
                formatSuffix = stream.format?.suffix,
                bitrate = stream.bitrate,
            )
        }
        val selectedAudio = YouTubeStreamSelector.chooseAudioStream(
            info.audioStreams.map { stream ->
                YouTubeAudioCandidate(
                    url = stream.content,
                    formatSuffix = stream.format?.suffix,
                    bitrate = stream.averageBitrate,
                )
            }
        )
        val selected = YouTubeStreamSelector.chooseVideoStreams(
            normalized,
            separateAudioAvailable = selectedAudio != null,
        )
        val candidates = selected.map { stream ->
            YouTubeQualitySource(
                label = "${stream.height}p",
                height = stream.height,
                url = stream.url,
                muxed = !stream.videoOnly,
            )
        }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                if (selectedAudio == null)
                    "YouTube 没有可用的合并音视频流，也没有独立音频轨"
                else
                    "YouTube 未提供可播放的视频流。请检查网络/VPN 后重试"
            )
        }
        val audioUrl = selectedAudio?.url.orEmpty()

        android.util.Log.i("PlaybackService", "YouTube selectable qualities=" + candidates.map { it.label })
        val defaultIndex = YouTubeStreamSelector.defaultQualityIndex(selected)
        val default = candidates.getOrNull(defaultIndex)
            ?: throw IllegalStateException("没有可用的 YouTube 视频清晰度")
        return YouTubeStreamChoices(candidates, audioUrl, default)
    }

    /**
     * If an extracted YouTube URL has expired or cannot be decoded, refresh the extraction once,
     * then try a compatible lower-resolution stream. The attempt is bounded to prevent retry loops.
     */
    private suspend fun recoverYouTubePlayback(
        sessionKey: String,
        failedVideoId: String,
        originalError: String,
    ) {
        val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            val oldHeight = qualitySources.firstOrNull { it.label == ui.value.selectedQuality }?.height
            updateUiForSession(sessionKey) { state ->
                if (state.key == sessionKey) state.copy(
                    phase = Phase.PARSING,
                    progress = "YouTube 视频流加载失败，正在刷新播放地址…",
                    error = null,
                ) else state
            }
            val position = withContext(Dispatchers.Main) { pm.position() }
            val shouldResume = withContext(Dispatchers.Main) { pm.player.playWhenReady }
            NewPipeRepo.invalidate(failedVideoId)
            val refreshedInfo = NewPipeRepo.streams(failedVideoId)
            val choices = buildYouTubeStreamChoices(refreshedInfo)
            val lowerCandidates = if (oldHeight != null) choices.sources.filter { it.height < oldHeight } else emptyList()
            val fallback = lowerCandidates.firstOrNull { it.muxed }
                ?: lowerCandidates.firstOrNull()
                ?: choices.sources.firstOrNull { it.muxed }
                ?: choices.defaultQuality

            withContext(Dispatchers.Main) {
                if (ui.value.key != sessionKey || videoId != failedVideoId) return@withContext
                qualitySources = choices.sources
                qualityAudioUrl = choices.audioUrl
                pm.errorSink.value = null
                updateUiForSession(sessionKey) { state ->
                    state.copy(
                        phase = Phase.PARSING,
                        progress = "正在尝试兼容清晰度 ${fallback.label}…",
                        error = null,
                        qualityOptions = choices.sources.map { it.label },
                    )
                }
                val accepted = pm.setYouTubeQuality(
                    videoUrl = fallback.url,
                    audioUrl = choices.audioUrl,
                    muxed = fallback.muxed,
                    title = ui.value.title,
                    artist = ui.value.channel,
                    artworkUrl = ui.value.thumbUrl,
                )
                if (!accepted) {
                    throw IllegalStateException(pm.errorSink.value ?: "刷新后的视频流初始化失败")
                }
                pm.seekTo(position)
                if (shouldResume) pm.play() else pm.pause()
                updateUiForSession(sessionKey) { state ->
                    if (state.key == sessionKey) state.copy(
                        phase = Phase.READY,
                        progress = "",
                        error = null,
                        selectedQuality = fallback.label,
                    ) else state
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            io.translatetube.local.util.AppLogStore.error(
    this@PlaybackService, "YouTubePlayback", "刷新 YouTube 播放资源失败", e,
)
android.util.Log.e("PlaybackService", "YouTube playback recovery failed", e)
            withContext(Dispatchers.Main) {
                if (ui.value.key == sessionKey && videoId == failedVideoId) {
                    pm.errorSink.value = null
                    runCatching { safelySetOriginalVolume(if (ui.value.originalVolume <= 0f) 0f else 1f, "source audio fallback") }
                    updateUiForSession(sessionKey) {
                        it.copy(
                            phase = Phase.ERROR,
                            progress = "",
                            error = "YouTube 播放失败，刷新视频流后仍无法播放：${e.message ?: originalError}",
                        )
                    }
                }
            }
        } finally {
            if (qualityRecoveryJob === thisJob) qualityRecoveryJob = null
        }
    }

    private fun asrAudioCacheDirectory(): File =
        File(cacheDir, "asr_audio_retry").apply { mkdirs() }

    private fun asrAudioCacheFile(videoId: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
        val safeId = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(asrAudioCacheDirectory(), "$safeId.bin")
    }

    /**
     * Serialize the lookup/download per video so ASR and speaker recognition can never download
     * the same source audio concurrently. The second caller rechecks the cache after acquiring it.
     */
    private suspend fun obtainAsrAudioFile(
        videoId: String,
        sessionKey: String,
        cacheHitProgress: String,
    ): File {
        return asrAudioDownloadLocks.withLock(videoId) {
            reusableAsrAudioFile(videoId)?.let { cached ->
                updateUiForSession(sessionKey) { state ->
                    if (state.key == sessionKey) state.copy(progress = cacheHitProgress) else state
                }
                return@withLock cached
            }
            updateUiForSession(sessionKey) { state ->
                if (state.key == sessionKey) {
                    state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "YouTube · 正在选择独立音轨…")
                } else state
            }
            awaitProcessingResumed(sessionKey)
            val stream = NewPipeRepo.audioForAsr(videoId)
                ?: throw IllegalStateException("无法获取视频独立音轨")
            updateUiForSession(sessionKey) { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        phase = Phase.DOWNLOADING_AUDIO,
                        progress = "YouTube · 独立音轨 · 正在下载…",
                    )
                } else state
            }
            awaitProcessingResumed(sessionKey)
            val destination = asrAudioCacheFile(videoId)
            VideoHistoryCacheIndex.rememberAudioCacheFile(this@PlaybackService, sessionKey, destination.name)
            suspend fun downloadStream(url: String): File = try {
                downloadAudio(url, destination, sessionKey, progressLabel = "YouTube · 独立音轨")
            } catch (manifest: HlsManifestPayloadException) {
                HlsAudioDownloader.download(
                    url,
                    destination,
                    awaitResume = { awaitProcessingResumed(sessionKey) },
                    userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() },
                    initialSourceKind = HlsAudioDownloader.AudioSourceKind.INDEPENDENT,
                ) { detail ->
                    updateUiForSession(sessionKey) { state ->
                        if (state.key == sessionKey) {
                            state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = detail)
                        } else state
                    }
                }
            }

            // Lease the destination before publishing a completed cache file, preventing a
            // concurrent cache prune from deleting it before the caller starts decoding.
            activeAsrAudioPaths.acquire(destination.absolutePath)
            var leaseTransferred = false
            try {
                val downloaded = try {
                    downloadStream(stream.content)
                } catch (firstError: Exception) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!MediaDownloadRetryPolicy.shouldRefreshSignedStream(firstError)) throw firstError
                    // StreamInfo caches expiring signed URLs. Invalidate and extract once before
                    // asking the user to retry the same stale URL again.
                    NewPipeRepo.invalidate(videoId)
                    updateUiForSession(sessionKey) { state ->
                        if (state.key == sessionKey) {
                            state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "音频链接已失效，正在刷新视频流…")
                        } else state
                    }
                    awaitProcessingResumed(sessionKey)
                    val refreshed = NewPipeRepo.audioForAsr(videoId) ?: throw firstError
                    awaitProcessingResumed(sessionKey)
                    try {
                        downloadStream(refreshed.content)
                    } catch (refreshError: Exception) {
                        refreshError.addSuppressed(firstError)
                        throw refreshError
                    }
                }
                pruneAsrAudioCache(asrAudioCacheDirectory(), keep = downloaded)
                leaseTransferred = true
                downloaded
            } finally {
                if (!leaseTransferred) activeAsrAudioPaths.release(destination.absolutePath)
            }
        }
    }


    private fun remoteAudioBaseDiagnostics(
        mediaUrl: String,
        referrer: String,
        cookie: String?,
        stage: String,
    ): Map<String, String> {
        val uri = runCatching { android.net.Uri.parse(mediaUrl) }.getOrNull()
        val extension = uri?.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mediaType = when (extension) {
            "m3u8" -> "HLS/M3U8 清单"
            "mpd" -> "DASH/MPD 清单"
            "mp4", "m4v", "webm", "mkv", "mov", "flv", "ts" -> "视频或混合媒体"
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> "纯音频候选"
            else -> "扩展名无法确认"
        }
        val pathSegments = uri?.pathSegments.orEmpty()
        val sensitivePathToken = Regex("""(?i)(token|signature|auth|secret|password|credential|session|jwt|expires|access.?key)""")
        val safePath = pathSegments.map { segment ->
            when {
                sensitivePathToken.containsMatchIn(segment) -> "[疑似凭据路径段已隐藏]"
                segment.length > 48 -> "[长路径段已隐藏:${segment.length}字符]"
                else -> segment
            }
        }.joinToString("/", prefix = "/").take(240)
        val queryNames = runCatching {
            uri?.queryParameterNames.orEmpty().sorted().joinToString(", ").take(300)
        }.getOrDefault("读取失败")
        return mapOf(
            "处理阶段" to stage.take(120),
            "媒体源协议" to uri?.scheme.orEmpty().ifBlank { "未知" },
            "媒体源主机名" to uri?.host?.take(120).orEmpty().ifBlank { "未知" },
            "媒体地址路径（已脱敏）" to safePath,
            "媒体地址查询参数名（不含值）" to queryNames.ifBlank { "无" },
            "媒体地址是否带查询参数" to (!uri?.encodedQuery.isNullOrBlank()).toString(),
            "媒体源类型" to mediaType,
            "媒体源扩展名提示" to extension.ifBlank { "无" },
            "媒体源诊断编号" to io.translatetube.local.util.AppLogStore.stableIssueKey("WebAudioSource", mediaUrl),
            "页面主机名" to runCatching { android.net.Uri.parse(referrer).host }.getOrNull()?.take(120).orEmpty().ifBlank { "未知" },
            "是否携带 Cookie" to (!cookie.isNullOrBlank()).toString(),
            "是否携带 Referer" to referrer.startsWith("http", ignoreCase = true).toString(),
        )
    }

    private fun logRemoteAudioFailure(
        mediaUrl: String,
        referrer: String,
        cookie: String?,
        stage: String,
        failure: Throwable,
        extra: Map<String, String> = emptyMap(),
        tag: String = "WebAudioExtraction",
    ) {
        val diagnosis = RemoteAudioFailurePolicy.classify(failure)
        val sourceUri = runCatching { android.net.Uri.parse(mediaUrl) }.getOrNull()
        val sourceIdentity = listOf(sourceUri?.host.orEmpty(), sourceUri?.path.orEmpty(), diagnosis.code).joinToString("|")
        val exceptionChain = generateSequence(failure) { it.cause }.take(12).toList()
        val combinedExceptionMessages = exceptionChain.mapNotNull { it.message }.joinToString("\n")
        val httpStatus = Regex("""(?i)HTTP\s+([0-9]{3})""")
            .findAll(combinedExceptionMessages)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        io.translatetube.local.util.AppLogStore.error(
            context = applicationContext,
            tag = tag,
            message = "网页视频音轨处理失败：${diagnosis.label}",
            failure = failure,
            diagnosticContext = remoteAudioBaseDiagnostics(mediaUrl, referrer, cookie, stage) + mapOf(
                "失败分类代码" to diagnosis.code,
                "可能原因" to diagnosis.likelyCause,
                "建议排查方向" to diagnosis.nextStep,
                "异常根因类型" to exceptionChain.lastOrNull()?.javaClass?.name.orEmpty().ifBlank { "未知" },
                "异常链类型（外层到根因）" to exceptionChain.joinToString(" -> ") { it.javaClass.name }.take(1_200),
                "从异常中提取的 HTTP 状态码" to httpStatus.ifBlank { "未找到 HTTP 状态码" },
                "当前是否为 HLS/M3U8 地址" to HlsAudioDownloader.isManifestUrl(mediaUrl).toString(),
            ) + extra,
            issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(tag, sourceIdentity),
        )
    }

    private fun validateRemoteAudioFile(
        mediaUrl: String,
        referrer: String,
        cookie: String?,
        stage: String,
        file: java.io.File,
    ): io.translatetube.local.asr.AudioExtract.MediaProbe {
        val probe = try {
            io.translatetube.local.asr.AudioExtract.probeMediaFile(file)
        } catch (error: Exception) {
            val originalSizeBytes = file.length()
            file.delete()
            val wrapped = IllegalStateException(
                "媒体数据已下载，但 Android MediaExtractor 无法读取缓存文件（${error.message ?: error.javaClass.simpleName}）",
                error,
            )
            logRemoteAudioFailure(
                mediaUrl, referrer, cookie, "$stage · 实际媒体文件复核", wrapped,
                mapOf("缓存文件大小字节" to originalSizeBytes.toString()),
                tag = "WebAudioTrackProbe",
            )
            throw wrapped
        }
        val details = mapOf(
            "缓存文件大小字节" to file.length().toString(),
            "实际检测到的音轨数" to probe.audioTrackCount.toString(),
            "实际检测到的视频轨数" to probe.videoTrackCount.toString(),
            "媒体时长毫秒" to probe.durationMs.toString(),
            "全部音轨明细" to probe.audioTrackDetails.joinToString("；").ifBlank { "没有识别到音轨" },
            "最终选中音频 MIME" to probe.selectedAudioMimeType.orEmpty().ifBlank { "未识别" },
            "最终选中音频语言" to probe.selectedAudioLanguage.orEmpty().ifBlank { "未提供" },
            "最终选中音频采样率" to (probe.selectedAudioSampleRateHz?.toString() ?: "未提供"),
            "最终选中音频声道数" to (probe.selectedAudioChannelCount?.toString() ?: "未提供"),
            "最终选中音频码率 bps" to (probe.selectedAudioBitrate?.toString() ?: "未提供"),
            "Android 解码器兼容性" to when (probe.selectedAudioDecoderAvailable) {
                true -> "已找到匹配解码器"
                false -> "未找到匹配解码器"
                null -> "无法明确判断"
            },
        )
        if (probe.audioTrackCount <= 0) {
            file.delete()
            val failure = IllegalStateException("下载结果中没有可读取的音频轨，候选媒体源可能是纯视频或错误内容")
            logRemoteAudioFailure(mediaUrl, referrer, cookie, "$stage · 实际媒体文件复核", failure, details, "WebAudioTrackProbe")
            throw failure
        }
        if (probe.selectedAudioDecoderAvailable == false) {
            file.delete()
            val failure = IllegalStateException(
                "音轨存在，但当前 Android 设备没有发现可用的音频解码器（${probe.selectedAudioMimeType ?: "未知编码"}）",
            )
            logRemoteAudioFailure(mediaUrl, referrer, cookie, "$stage · 音频解码器兼容性检查", failure, details, "WebAudioTrackProbe")
            throw failure
        }
        io.translatetube.local.util.AppLogStore.info(
            context = applicationContext,
            tag = "WebAudioTrackProbe",
            message = "网页媒体下载结果已通过真实音轨与编码兼容性检查",
            diagnosticContext = remoteAudioBaseDiagnostics(mediaUrl, referrer, cookie, "$stage · 实际媒体文件复核") + details,
        )
        return probe
    }

    /**
     * Download a direct or finite HLS webpage media URL into a bounded cache for Whisper.
     * The referrer/cookie are obtained from the current WebView session and are never persisted.
     */
    /**
     * 直链视频流式抽轨：MediaExtractor 以带请求头的 http 源直接读取（系统层自带
     * Range 分块拉流），选音频轨经 MediaMuxer 写成独立音频文件——视频数据全程
     * 不落盘，缓存里只有音频（几十 MB 视频换几 MB 音轨）。
     */
    private suspend fun extractAudioTrackStreaming(
        mediaUrl: String,
        destination: File,
        headers: Map<String, String>,
        awaitResume: suspend () -> Unit = {},
        onSourceType: (HlsAudioDownloader.AudioSourceKind) -> Unit = {},
        onProgressPercent: (Int?, Long, Long?, Boolean) -> Unit = { _, _, _, _ -> },
    ): File {
        val work = File(destination.parentFile, destination.name + ".part")
        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null
        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            // A process can be killed before the normal finally block runs. Remove its unfinished
            // output before MediaMuxer opens the same path for a fresh extraction attempt.
            work.parentFile?.mkdirs()
            if (work.exists() && !work.delete()) {
                throw IllegalStateException("无法清理上次未完成的音轨暂存文件，请稍后重试。")
            }
            awaitResume()
            ownerJob?.ensureActive()
            val streamHeaders = buildMap {
                putAll(headers)
                remoteMediaUserAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            }
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // MediaExtractor 的 URL 读取是平台级同步 I/O；至少不要在任务已取消或暂停时启动它。
                extractor.setDataSource(mediaUrl, streamHeaders)
            }
            ownerJob?.ensureActive()
            val audioCandidates = ArrayList<AudioTrackSelectionPolicy.Candidate>()
            var audioTrackCount = 0
            var videoTrackCount = 0
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackCount++
                    continue
                }
                if (!mime.startsWith("audio/")) continue
                audioTrackCount++
                val channels = runCatching {
                    fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(1)
                val isDefault = runCatching {
                    fmt.containsKey("is-default") && fmt.getInteger("is-default") != 0
                }.getOrDefault(false)
                audioCandidates += AudioTrackSelectionPolicy.Candidate(
                    index = i,
                    isDefault = isDefault,
                    channelCount = channels,
                )
            }
            val chosen = AudioTrackSelectionPolicy.choose(audioCandidates)
                ?: throw IllegalStateException("该视频流中没有可提取的音频轨")
            val sourceKind = when {
                videoTrackCount > 0 && audioTrackCount > 0 ->
                    HlsAudioDownloader.AudioSourceKind.MUXED
                audioTrackCount > 0 && videoTrackCount == 0 ->
                    HlsAudioDownloader.AudioSourceKind.INDEPENDENT
                else -> HlsAudioDownloader.AudioSourceKind.UNKNOWN
            }
            onSourceType(sourceKind)
            val trackFormat = extractor.getTrackFormat(chosen)
            val mediaDurationUs = runCatching {
                if (trackFormat.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    trackFormat.getLong(android.media.MediaFormat.KEY_DURATION)
                } else 0L
            }.getOrDefault(0L).coerceAtLeast(0L)
            var lastProgressPercent = -1
            val audioBitRateBitsPerSecond = runCatching {
                trackFormat.getInteger(android.media.MediaFormat.KEY_BIT_RATE).toLong()
            }.getOrNull()
            var lastReportedOutputBytes = 0L
            var lastReportedProgressAtMs = android.os.SystemClock.elapsedRealtime()
            val trackMime = trackFormat.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            val outputFormat = when (AudioTrackContainerPolicy.choose(trackMime)) {
                AudioTrackContainerPolicy.Container.WEBM ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                AudioTrackContainerPolicy.Container.MP4 ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                null -> throw IllegalStateException(
                    "当前设备无法安全封装网页音频编码（$trackMime）；已停止直接抽轨，避免生成损坏的音频缓存。请重试或使用 yt-dlp 解析。"
                )
            }
            muxer = android.media.MediaMuxer(work.absolutePath, outputFormat)
            val muxerTrack = muxer.addTrack(trackFormat)
            muxer.start()
            extractor.selectTrack(chosen)
            val buffer = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var extractedSampleBytes = 0L
            withContext(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    awaitResume()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (!AsrAudioCachePolicy.canAppendBytes(extractedSampleBytes, size.toLong())) {
                        throw IllegalStateException(
                            "提取出的网页音轨大小超过文件系统可表示的范围，已停止处理。"
                        )
                    }
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    extractedSampleBytes += size.toLong()
                    val nextPercent = if (size > 0 && mediaDurationUs > 0L) {
                        io.translatetube.local.asr.MediaTimelineProgressPolicy.nextPercent(
                            positionUs = info.presentationTimeUs,
                            durationUs = mediaDurationUs,
                            previousPercent = lastProgressPercent,
                        )
                    } else {
                        null
                    }
                    if (nextPercent != null) lastProgressPercent = nextPercent
                    val outputBytes = work.length()
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val shouldPublishByteProgress = outputBytes > lastReportedOutputBytes &&
                        (outputBytes - lastReportedOutputBytes >= 512L * 1024L ||
                            nowMs - lastReportedProgressAtMs >= 350L)
                    if (nextPercent != null || shouldPublishByteProgress) {
                        val estimatedTotalBytes =
                            AudioDownloadProgressFormatter.estimateBytesFromBitrateAndDuration(
                                bitRateBitsPerSecond = audioBitRateBitsPerSecond,
                                durationUs = mediaDurationUs,
                            ) ?: AudioDownloadProgressFormatter.estimateBytesFromObservedProgress(
                                downloadedBytes = outputBytes,
                                elapsedDurationUs = info.presentationTimeUs.coerceAtLeast(0L),
                                totalDurationUs = mediaDurationUs,
                            )
                        val safeEstimatedTotalBytes = estimatedTotalBytes?.coerceAtLeast(outputBytes)
                        onProgressPercent(
                            nextPercent ?: lastProgressPercent.takeIf { it >= 0 },
                            outputBytes,
                            safeEstimatedTotalBytes,
                            safeEstimatedTotalBytes != null,
                        )
                        lastReportedOutputBytes = outputBytes
                        lastReportedProgressAtMs = nowMs
                    }
                    extractor.advance()
                }
            }
            muxer.stop()
            muxer.release()
            muxer = null
            if (!AsrAudioCachePolicy.isCompleteDownload(-1L, work.length())) {
                throw IllegalStateException(
                    "提取出的音轨为空，无法继续处理。"
                )
            }
            // Flush the completed muxer output before publishing it as the reusable cache file.
            java.io.RandomAccessFile(work, "rw").use { it.fd.sync() }
            if (!work.renameTo(destination)) {
                // Never copy directly over the old cache: a failed/partial copy could destroy the
                // last good file. The caller's catch removes only this .part file on failure.
                throw IllegalStateException(
                    "音轨提取完成，但无法原子保存缓存文件；原有缓存已保留，请重试。"
                )
            }
            onProgressPercent(100, destination.length(), destination.length(), false)
            return destination
        } catch (t: Throwable) {
            runCatching { muxer?.release() }
            runCatching { work.delete() }
            throw t
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Inspect at most 512 bytes to identify an extension-less HLS playlist. This probe does not
     * write the response to disk, even if a server ignores the Range request and returns HTTP 200.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun probeHlsManifestPayload(
        mediaUrl: String,
        headers: Map<String, String>,
    ): Boolean {
        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val probeHeaders = HlsAudioDownloader.headersWithoutInheritedRange(headers) +
            ("Range" to "bytes=0-511")
        return withContext(Dispatchers.IO) {
            ownerJob?.ensureActive()
            SafeMediaHttp.execute(
                url = mediaUrl,
                requestHeaders = probeHeaders,
                userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() }
                    ?: SafeMediaHttp.DEFAULT_USER_AGENT,
                ownerJob = ownerJob,
            ).use responseUse@ { response ->
                if (response.code !in setOf(200, 206)) {
                    return@responseUse false
                }
                val body = response.body ?: return@responseUse false
                val contentType = body.contentType()?.toString()
                    ?.lowercase(java.util.Locale.ROOT)
                    .orEmpty()
                if (contentType.contains("mpegurl")) {
                    return@responseUse true
                }
                val prefix = ByteArray(512)
                val count = body.byteStream().use { input ->
                    var total = 0
                    while (total < prefix.size) {
                        ownerJob?.ensureActive()
                        val read = input.read(prefix, total, prefix.size - total)
                        if (read <= 0) break
                        total += read
                        // "#EXTM3U" (including a possible UTF-8 BOM) is at most 10 bytes.
                        if (isHlsManifestPayload(prefix, total)) break
                    }
                    total
                }
                isHlsManifestPayload(prefix, count)
            }
        }
    }

    private suspend fun obtainRemoteMediaAsrFile(
        mediaUrl: String,
        referrer: String,
        cookie: String?,
        sessionKey: String,
        cacheHitProgress: String,
    ): File {
        // Reuse downloaded bytes across rotating signed URLs, but never across page origins or
        // different WebView cookie sessions. The raw cookie/media URL is not written to this key.
        val identity = MediaUrlCacheIdentity.audioKey(mediaUrl, referrer, cookie)
        return asrAudioDownloadLocks.withLock(identity) {
            reusableAsrAudioFile(identity)?.let { cached ->
                updateUiForSession(sessionKey) { state ->
                    if (state.key == sessionKey) state.copy(progress = cacheHitProgress) else state
                }
                return@withLock cached
            }
            updateUiForSession(sessionKey) { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        phase = Phase.DOWNLOADING_AUDIO,
                        progress = "音轨类型待确认 · 正在检查网页媒体源…",
                    )
                } else state
            }
            val headers = buildMap {
                referrer.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { put("Referer", it) }
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            val destination = asrAudioCacheFile(identity)
            VideoHistoryCacheIndex.rememberAudioCacheFile(this@PlaybackService, sessionKey, destination.name)
            activeAsrAudioPaths.acquire(destination.absolutePath)
            var leaseTransferred = false
            var extractionStage = "网页媒体音轨获取"
            try {
                // yt-dlp 源优先：直接下纯音频（bestaudio）进转写，比流式拉整视频省流量省电；
                // 失败（站点不支持纯音频/网络异常/产物异常）自动回落现有 HLS/直链管线。
                val ytdlpOrigin = io.translatetube.local.yt.YtDlpRepo.claimForMediaUrl(mediaUrl)
                if (ytdlpOrigin != null) {
                    extractionStage = "yt-dlp bestaudio 纯音频下载"
                    try {
                        val directAudio = io.translatetube.local.yt.YtDlpRepo.downloadAudio(
                            applicationContext,
                            ytdlpOrigin.pageUrl,
                            destination,
                            onProgress = { percent, downloadedBytes, totalBytes ->
                                kotlinx.coroutines.runBlocking { awaitProcessingResumed(sessionKey) }
                                updateUiForSession(sessionKey) { state ->
                                    if (state.key == sessionKey) {
                                        val progress = if (downloadedBytes > 0L || totalBytes != null) {
                                            AudioDownloadProgressFormatter.formatProgress(
                                                label = "独立音轨 · yt-dlp 正在下载",
                                                downloadedBytes = downloadedBytes,
                                                totalBytes = totalBytes,
                                                percent = percent.takeIf { it >= 0 },
                                            )
                                        } else if (percent >= 0) {
                                            "独立音轨 · yt-dlp 正在下载 $percent%"
                                        } else {
                                            "独立音轨 · yt-dlp 正在下载…"
                                        }
                                        state.copy(
                                            phase = Phase.DOWNLOADING_AUDIO,
                                            progress = progress,
                                        )
                                    } else state
                                }
                            },
                            cookieHeader = ytdlpOrigin.cookie,
                        )
                        validateRemoteAudioFile(
                            mediaUrl, referrer, cookie, "yt-dlp bestaudio 纯音频下载", directAudio,
                        )
                        pruneAsrAudioCache(asrAudioCacheDirectory(), keep = directAudio)
                        leaseTransferred = true
                        return@withLock directAudio
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        val coroutineActive = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false
                        if (!MediaDownloadRetryPolicy.shouldFallbackToStreaming(e, coroutineActive)) {
                            // Preserve wrapped cancellation too: do not start network fallback when
                            // the current session has already been cancelled or retired.
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            throw e
                        }
                        android.util.Log.w("PlaybackService", "yt-dlp 音频直下失败，回落流式提取", e)
                        updateUiForSession(sessionKey) { state ->
                            if (state.key == sessionKey) {
                                state.copy(progress = "独立音轨下载失败，正在检查网页媒体源并提取音轨…")
                            } else state
                        }
                    }
                }
                val cachedAudio = try {
                    if (HlsAudioDownloader.isManifestUrl(mediaUrl)) {
                        extractionStage = "HLS 主清单音轨分段下载"
                        HlsAudioDownloader.download(mediaUrl, destination, requestHeaders = headers, awaitResume = { awaitProcessingResumed(sessionKey) }, userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() }) { detail ->
                            updateUiForSession(sessionKey) { state ->
                                if (state.key == sessionKey) {
                                    state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = detail)
                                } else state
                            }
                        }
                    } else {
                        // 直链视频优先「流式抽轨」：系统 MediaExtractor 直接读取带请求头的
                        // http 源，边拉边把音频轨写进 m4a/webm，不把整个视频下载到本地。
                        extractionStage = "远程容器流式读取并提取音轨"
                        try {
                            var detectedSourceKind = HlsAudioDownloader.AudioSourceKind.UNKNOWN
                            extractAudioTrackStreaming(
                                mediaUrl,
                                destination,
                                headers,
                                awaitResume = { awaitProcessingResumed(sessionKey) },
                                onSourceType = { kind ->
                                    detectedSourceKind = kind
                                    updateUiForSession(sessionKey) { state ->
                                        if (state.key == sessionKey && state.phase == Phase.DOWNLOADING_AUDIO) {
                                            state.copy(
                                                progress = "${kind.displayName} · 正在从网页媒体源提取音轨…",
                                            )
                                        } else state
                                    }
                                },
                                onProgressPercent = { percent, extractedBytes, totalBytes, totalIsEstimate ->
                                    updateUiForSession(sessionKey) { state ->
                                        if (state.key == sessionKey && state.phase == Phase.DOWNLOADING_AUDIO) {
                                            state.copy(
                                                progress = AudioDownloadProgressFormatter.formatProgress(
                                                    label = "${detectedSourceKind.displayName} · 网页媒体流音轨提取",
                                                    downloadedBytes = extractedBytes,
                                                    totalBytes = totalBytes,
                                                    percent = percent,
                                                    totalIsEstimate = totalIsEstimate,
                                                )
                                            )
                                        } else state
                                    }
                                },
                            )
                        } catch (streamingError: Exception) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (streamingError is kotlinx.coroutines.CancellationException) throw streamingError

                            // Never download an entire MP4/WebM just because MediaExtractor failed.
                            // A small Range probe preserves HLS support for URLs without an extension.
                            val isOpaqueHlsManifest = try {
                                probeHlsManifestPayload(mediaUrl, headers)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (probeError: Exception) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val sourceUri = runCatching { android.net.Uri.parse(mediaUrl) }.getOrNull()
                                io.translatetube.local.util.AppLogStore.error(
                                    context = this@PlaybackService,
                                    tag = "WebHlsManifestProbe",
                                    message = "流式音轨提取失败后，检测网页媒体是否为 HLS 清单时发生异常",
                                    failure = probeError,
                                    diagnosticContext = remoteAudioBaseDiagnostics(
                                        mediaUrl,
                                        referrer,
                                        cookie,
                                        "流式音轨提取失败后的 HLS 清单探测",
                                    ) + mapOf(
                                        "前序流式提取异常类型" to streamingError.javaClass.name,
                                        "前序流式提取异常详情" to
                                            (streamingError.message ?: "无异常消息").take(300),
                                        "前序异常分类" to
                                            io.translatetube.local.player.RemoteAudioFailurePolicy
                                                .classify(streamingError).code,
                                        "HLS 探测异常类型" to probeError.javaClass.name,
                                        "HLS 探测异常详情" to
                                            (probeError.message ?: "无异常消息").take(300),
                                    ),
                                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                                        "WebHlsManifestProbe",
                                        listOf(
                                            sourceUri?.host.orEmpty(),
                                            sourceUri?.path.orEmpty(),
                                        ).joinToString("|"),
                                    ),
                                )
                                android.util.Log.w(
                                    "PlaybackService",
                                    "检查网页媒体是否为 HLS 清单失败",
                                    probeError,
                                )
                                false
                            }

                            if (isOpaqueHlsManifest) {
                                extractionStage = "无扩展名 HLS 清单音轨分段下载"
                                HlsAudioDownloader.download(
                                    mediaUrl,
                                    destination,
                                    requestHeaders = headers,
                                    awaitResume = { awaitProcessingResumed(sessionKey) },
                                    userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() },
                                ) { detail ->
                                    updateUiForSession(sessionKey) { state ->
                                        if (state.key == sessionKey) {
                                            state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = detail)
                                        } else state
                                    }
                                }
                            } else if (
                                io.translatetube.local.web.WebMediaUrlPolicy
                                    .isExplicitAudioOnlyResource(mediaUrl)
                            ) {
                                extractionStage = "纯音频直链下载兜底"
                                // A URL explicitly ending in an audio-only extension is safe to fetch whole.
                                updateUiForSession(sessionKey) { state ->
                                    if (state.key == sessionKey) {
                                        state.copy(progress = "音轨流式读取失败，正在下载独立音频文件…")
                                    } else state
                                }
                                downloadAudio(
                                    mediaUrl,
                                    destination,
                                    sessionKey,
                                    headers,
                                    progressLabel = "独立音轨",
                                )
                            } else {
                                android.util.Log.w(
                                    "PlaybackService",
                                    "网页视频流式音轨提取失败；已阻止整段视频下载",
                                    streamingError,
                                )
                                throw IllegalStateException(
                                    "网页视频音轨提取失败。为避免把整段视频下载到手机，本次已停止整文件兜底。请重试，或在网页页面选择“用 yt-dlp 解析本页视频”。原因：${streamingError.message ?: streamingError.javaClass.simpleName}",
                                    streamingError,
                                )
                            }
                        }
                    }
                } catch (mediaError: Exception) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (mediaError is kotlinx.coroutines.CancellationException) throw mediaError
                    logRemoteAudioFailure(
                        mediaUrl, referrer, cookie, extractionStage, mediaError,
                        mapOf(
                            "转写缓存文件存在" to destination.isFile.toString(),
                            "转写缓存文件大小字节" to destination.length().toString(),
                        ),
                    )
                    throw mediaError
                }
                validateRemoteAudioFile(mediaUrl, referrer, cookie, extractionStage, cachedAudio)
                pruneAsrAudioCache(asrAudioCacheDirectory(), keep = cachedAudio)
                leaseTransferred = true
                cachedAudio
            } finally {
                if (!leaseTransferred) activeAsrAudioPaths.release(destination.absolutePath)
            }
        }
    }

    /** Reuse only a complete, reasonably recent file; a failed ASR attempt keeps it for retry. */
    private fun reusableAsrAudioFile(videoId: String): File? {
        val file = asrAudioCacheFile(videoId)
        val now = System.currentTimeMillis()
        // Protect the candidate before checking its metadata: a concurrent cache pruner must not
        // delete it in the gap between validation and handing the file to its reader.
        activeAsrAudioPaths.acquire(file.absolutePath)
        if (AsrAudioCachePolicy.canReuse(file.isFile, file.length(), file.lastModified(), now)) {
            file.setLastModified(now)
            file.parentFile?.let { pruneAsrAudioCache(it, keep = file) }
            return file
        }
        activeAsrAudioPaths.release(file.absolutePath)
        // Release and deletion must be coordinated with acquisition, not a separate contains()
        // check: a new reader could otherwise acquire this path just before file.delete().
        activeAsrAudioPaths.deleteIfUnleased(file.absolutePath) { file.delete() }
        File(file.parentFile, file.name + ".part").delete()
        return null
    }

    /** Keep cached audio through its TTL; never evict files currently being reused/downloaded. */
    private fun releaseActiveAsrAudio(path: String?) {
        if (path.isNullOrBlank()) return
        activeAsrAudioPaths.release(path)
        val file = File(path)
        file.parentFile?.let { parent ->
            pruneAsrAudioCache(parent, keep = file.takeIf { it.isFile })
        }
    }

    private fun pruneAsrAudioCache(directory: File, keep: File?) {
        val allFiles = directory.listFiles() ?: return
        val now = System.currentTimeMillis()
        // A process killed during download may leave a large .part file. Retire it only after
        // six hours, and coordinate deletion with the final .bin lease so an old-but-active
        // download cannot lose its partial payload while another cache is being pruned.
        allFiles.filter { file ->
            file.isFile && file.name.endsWith(".part") &&
                AsrAudioCachePolicy.isStalePartial(file.lastModified(), now)
        }.forEach { file ->
            val destinationPath = file.absolutePath.removeSuffix(".part")
            activeAsrAudioPaths.deleteIfUnleased(destinationPath) { file.delete() }
        }
        val files = allFiles.filter { it.isFile && it.name.endsWith(".bin") }
        // Enforce TTL without imposing a total cache byte budget.
        files.forEach { file ->
            val isKept = keep != null && file.absolutePath == keep.absolutePath
            val age = now - file.lastModified()
            val shouldExpire = !isKept &&
                (file.length() <= 0L || age < 0L || age > AsrAudioCachePolicy.TTL_MS)
            if (shouldExpire) {
                // The lease registry holds its monitor through deletion. A simultaneous reader
                // either acquires first (and protects the file) or waits until deletion finishes.
                activeAsrAudioPaths.deleteIfUnleased(file.absolutePath) { file.delete() }
            }
        }
    }

    /**
     * Some media servers use chunked transfer encoding and omit Content-Length on the download
     * response. A one-byte range probe can still reveal the exact entity length from Content-Range.
     * The probe is only attempted when the main response has no usable size.
     */
    private suspend fun probeRemoteMediaTotalBytes(
        url: String,
        requestHeaders: Map<String, String>,
        ownerJob: Job?,
    ): Long? = withContext(Dispatchers.IO) {
        ownerJob?.ensureActive()
        val probeHeaders = HlsAudioDownloader.headersWithoutInheritedRange(requestHeaders) +
            ("Range" to "bytes=0-0")
        SafeMediaHttp.execute(
            url = url,
            requestHeaders = probeHeaders,
            userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() }
                ?: SafeMediaHttp.DEFAULT_USER_AGENT,
            ownerJob = ownerJob,
        ).use { response ->
            if (response.code !in setOf(200, 206)) return@use null
            val rangeTotal = response.header("Content-Range")
                ?.let { header ->
                    Regex("""(?i)^bytes\s+\d+-\d+/(\d+)$""")
                        .matchEntire(header.trim())
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                }
                ?.takeIf { it > 0L }
            rangeTotal ?: response.header("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }
    }

    private suspend fun downloadAudio(
        url: String,
        destination: File? = null,
        sessionKey: String,
        requestHeaders: Map<String, String> = emptyMap(),
        progressLabel: String = "音轨类型待确认 · 音轨",
    ): File {
        val target = destination ?: File(cacheDir, "asr_audio_${System.nanoTime()}.bin")
        target.parentFile?.mkdirs()
        // Capture the owning pipeline before switching to IO. SafeMediaHttp uses it to cancel
        // the active socket read immediately when the user changes video or aborts transcription.
        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        // Always write to a sibling temporary file. A process killed mid-download must never
        // leave a partial payload under the final .bin name where a later cache scan can reuse it.
        val writeTarget = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null

        for (attempt in 1..3) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            writeTarget.delete()
            try {
                withContext(Dispatchers.IO) {
                    SafeMediaHttp.execute(
                        url = url,
                        // This operation needs the full track, not a Range captured from a WebView request.
                        requestHeaders = HlsAudioDownloader.headersWithoutInheritedRange(requestHeaders),
                        // 站点防护（Cloudflare 等）的 Cookie 与 WebView 的 UA 绑定，
                        // 音频提取必须沿用同一 UA，否则会被判无效返回挑战页。
                        userAgent = remoteMediaUserAgent.takeIf { it.isNotBlank() }
                            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
                        ownerJob = ownerJob,
                    ).use { resp ->
                        SafeMediaHttp.validateCompleteGetResponse(resp.code, "音频文件")
                        val body = resp.body ?: throw IllegalStateException("音频下载响应为空")
                        if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                            throw IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频；链接可能过期或需要登录。")
                        }
                        val responseLength = body.contentLength()
                        val total = if (responseLength > 0L) {
                            responseLength
                        } else {
                            try {
                                probeRemoteMediaTotalBytes(url, requestHeaders, ownerJob)
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (probeError: Exception) {
                                android.util.Log.d(
                                    "PlaybackService",
                                    "音轨响应未提供总长度，范围探测也未取得可用大小",
                                    probeError,
                                )
                                null
                            } ?: -1L
                        }
                        body.byteStream().use { ins ->
                            var got = 0L
                            var lastProgressBytes = 0L
                            var lastProgressAtMs = android.os.SystemClock.elapsedRealtime()
                            val payloadPrefix = ByteArray(512)
                            var payloadPrefixSize = 0
                            writeTarget.outputStream().buffered().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    awaitProcessingResumed(sessionKey)
                                    val n = io.translatetube.local.util.ProgressSafeInput.read(ins, buf)
                                    if (n < 0) break
                                    if (payloadPrefixSize < payloadPrefix.size) {
                                        val copyCount = minOf(n, payloadPrefix.size - payloadPrefixSize)
                                        System.arraycopy(buf, 0, payloadPrefix, payloadPrefixSize, copyCount)
                                        payloadPrefixSize += copyCount
                                        if (isHlsManifestPayload(payloadPrefix, payloadPrefixSize)) {
                                            throw HlsManifestPayloadException(url)
                                        }
                                        if (isLikelyTextErrorPayload(payloadPrefix, payloadPrefixSize)) {
                                            throw IllegalStateException(
                                                "服务器返回了登录页或错误文本，而不是音频数据；链接可能过期或需要登录。"
                                            )
                                        }
                                    }
                                    if (got > Long.MAX_VALUE - n.toLong()) {
                                        throw IllegalStateException("音频大小超过当前文件格式可表示的范围。")
                                    }
                                    got += n.toLong()
                                    out.write(buf, 0, n)
                                    val nowMs = android.os.SystemClock.elapsedRealtime()
                                    val shouldPublishProgress =
                                        got - lastProgressBytes >= 512L * 1024L ||
                                            nowMs - lastProgressAtMs >= 350L ||
                                            (total > 0L && got >= total)
                                    if (shouldPublishProgress) {
                                        val percent = if (total > 0L) {
                                            (got * 100.0 / total.toDouble()).toInt().coerceIn(0, 100)
                                        } else null
                                        val progress = AudioDownloadProgressFormatter.formatProgress(
                                            label = "${progressLabel} · 正在下载",
                                            downloadedBytes = got,
                                            totalBytes = total.takeIf { it > 0L },
                                            percent = percent,
                                        )
                                        updateUiForSession(sessionKey) { state ->
                                            if (state.key == sessionKey) state.copy(progress = progress) else state
                                        }
                                        lastProgressBytes = got
                                        lastProgressAtMs = nowMs
                                    }
                                }
                                out.flush()
                            }
                            if (!AsrAudioCachePolicy.isCompleteDownload(total, got)) {
                                throw IllegalStateException(
                                    "音频下载不完整：响应声明 ${total} 字节，实际收到 ${got} 字节"
                                )
                            }
                            updateUiForSession(sessionKey) { state ->
                                if (state.key == sessionKey) {
                                    state.copy(
                                        progress = AudioDownloadProgressFormatter.formatProgress(
                                            label = "${progressLabel} · 下载完成",
                                            downloadedBytes = got,
                                            totalBytes = total.takeIf { it > 0L },
                                            percent = 100,
                                        )
                                    )
                                } else state
                            }
                        }
                    }
                }

                // Make the complete temporary payload durable before publishing the cache path.
                if (writeTarget.isFile && writeTarget.length() > 0L) {
                    java.io.RandomAccessFile(writeTarget, "rw").use { it.fd.sync() }
                }
                if (!writeTarget.isFile || writeTarget.length() == 0L) {
                    throw IllegalStateException("音频下载结果为空")
                }
                // Rename only after the complete response has been validated. On Android/Linux,
                // same-directory rename replaces the previous regular file atomically; if it fails,
                // leave the previous cache intact rather than deleting it first.
                if (!writeTarget.renameTo(target)) {
                    throw IllegalStateException("下载完成但无法原子保存音频缓存")
                }
                target.setLastModified(System.currentTimeMillis())
                return target
            } catch (error: Exception) {
                writeTarget.delete()
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
                if (attempt == 3 || !MediaDownloadRetryPolicy.shouldRetry(error)) throw error
                updateUiForSession(sessionKey) { state ->
                    if (state.key == sessionKey) {
                        state.copy(progress = "网络不稳定，正在重试音频下载（第 ${attempt + 1} 次）")
                    } else state
                }
                kotlinx.coroutines.delay(300L * attempt)
            }
        }
        throw IllegalStateException("音频下载失败，已达到重试次数上限。", lastError)
    }

    private fun kokoroRuntimeReady(): Boolean = runCatching {
        val files = io.translatetube.local.data.ModelFiles
        if (files.ttsCoreReady(this)) files.installBundledTtsSupportFiles(this)
        files.ttsReady(this)
    }.onFailure {
        android.util.Log.w("PlaybackService", "Kokoro runtime readiness check failed", it)
    }.getOrDefault(false)

    private fun ttsDisplayName(engineId: String): String = when (engineId) {
        "system" -> "系统 TTS"
        "edge" -> "微软 Edge TTS"
        "web" -> "在线语音"
        "kokoro" -> "Kokoro"
        "piper" -> {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val id = prefs.getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium"
            val def = io.translatetube.local.tts.PiperModels.byId(id)
            if (def == null) "Piper" else "Piper · ${def.voiceLabel}"
        }
        else -> "未知配音引擎"
    }

    private fun piperModelReadyForChinese(): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val id = prefs.getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium"
        val definition = io.translatetube.local.tts.PiperModels.byId(id) ?: return false
        return definition.supportsChineseOutput &&
            io.translatetube.local.tts.PiperModels.modelReady(this, id)
    }

    private fun makeTts(engineId: String? = null): TtsEngine {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val selected = engineId ?: (prefs.getString("tts_engine", "edge") ?: "edge")
        return when (selected) {
            "system" -> io.translatetube.local.tts.SystemTts(
                this,
                io.translatetube.local.tts.SystemTtsPlaybackMode.fromPreference(
                    prefs.getString("system_tts_mode", "generated"),
                ),
            )
            "kokoro" -> {
                if (!kokoroRuntimeReady()) {
                    throw IllegalStateException("已选择 Kokoro，但配音模型尚未就绪")
                }
                KokoroTts(this, speakerId = prefs.getInt("kokoro_speaker", 14))
            }
            "piper" -> {
                val modelId = prefs.getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium"
                val definition = io.translatetube.local.tts.PiperModels.byId(modelId)
                    ?: throw IllegalStateException("未知 Piper 模型「$modelId」，请在设置中重新选择")
                if (!definition.supportsChineseOutput) {
                    throw IllegalStateException("当前翻译目标是中文，所选 Piper 模型为「${definition.langLabel}」。请在设置中选择中文 Piper 模型。")
                }
                if (!io.translatetube.local.tts.PiperModels.modelReady(this, modelId)) {
                    throw IllegalStateException("已选择 Piper 中文配音，但模型尚未下载或文件不完整，请在设置中下载中文 Piper 模型")
                }
                io.translatetube.local.tts.PiperTts(this, modelId)
            }
            "edge" -> io.translatetube.local.tts.EdgeTts()
            "web" -> io.translatetube.local.tts.WebTts()
            else -> throw IllegalStateException("未知配音引擎「$selected」，请在设置中重新选择")
        }
    }

    /**
     * TTS constructors may load native runtimes or wait for Android's system TTS service.
     * Keep those blocking operations off the main thread and close an instance if cancellation
     * wins after construction but before the caller can take ownership.
     */
    private suspend fun makeTtsOffMainThread(engineId: String? = null): TtsEngine {
        val created = java.util.concurrent.atomic.AtomicReference<TtsEngine?>(null)
        try {
            return withContext(Dispatchers.IO) {
                val engine = makeTts(engineId)
                created.set(engine)
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    engine
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    created.compareAndSet(engine, null)
                    runCatching { engine.close() }
                    throw cancelled
                }
            }.also { created.compareAndSet(it, null) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            created.getAndSet(null)?.let { engine -> runCatching { engine.close() } }
            throw cancelled
        }
    }

    /**
     * Report synthesis failures without silently replacing the engine selected in Settings.
     * When the selected engine cannot produce voice output, pause the video rather than
     * silently substituting the original audio as an automatic fallback.
     */
    private fun pausePlaybackAfterDubbingFailure(
        engine: DubbingEngine,
        sessionKey: String,
        reason: String,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!::pm.isInitialized || !isCurrentDubbingEngine(engine, sessionKey)) return@launch
            cancelPendingSeekResume()
            cancelDubbingDrainAfterVideoEnd()
            runCatching { pm.pause() }
            safelyControlDubbing("配音失败后停止语音输出：$reason") { engine.stop() }

            val current = ui.value
            if (current.key != sessionKey || !isCurrentDubbingEngine(engine, sessionKey)) return@launch
            safelySetOriginalVolume(
                if (current.originalVolume <= 0f) 0f else current.originalVolume,
                "配音失败后保持视频暂停并保留原声音量设置",
            )
            ui.update { state ->
                if (state.key == sessionKey && dubbing === engine) {
                    state.copy(
                        isPlaying = false,
                        currentLine = -1,
                        activeDubbingLine = -1,
                        dubbingHasErrors = true,
                        dubbingStatus = "${state.ttsEngineName.ifBlank { "配音引擎" }}合成失败，视频已暂停",
                    )
                } else state
            }
            android.util.Log.w(
                "PlaybackService",
                "Video paused after dubbing failure: session=$sessionKey reason=${reason.take(180)}",
            )
        }
    }

    private fun registerDubbingFailureHandler(
        engine: DubbingEngine,
        tts: TtsEngine,
        sessionKey: String = ui.value.key,
    ) {
        engine.onFirstSynthesisReady = { index, sampleCount, sampleRate ->
            val durationMs = sampleCount.toLong() * 1_000L / sampleRate.coerceAtLeast(1)
            updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                state.copy(
                    dubbingStatus = "${engine.currentTtsName} 已生成第 ${index + 1} 句 PCM（约 ${durationMs} ms）"
                )
            }
        }
        engine.onPlaybackStarted = { index ->
            // Cut the original track at speech start; waiting for the next player tick would
            // briefly overlap two narrations on slower devices.
            if (::pm.isInitialized) {
                scope.launch(Dispatchers.Main) {
                    if (
                        ::pm.isInitialized &&
                        isCurrentDubbingEngine(engine, sessionKey) &&
                        ui.value.dubbingOn
                    ) {
                        runCatching { pm.originalVolume = 0f }
                    }
                }
            }
            // Highlight the actual spoken cue, not just the video-time subtitle. AudioTrack
            // startup is asynchronous, so the first cue can otherwise miss its visual emphasis.
            updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                state.copy(
                    dubbingStatus = "${engine.currentTtsName} 正在播放中文配音",
                    activeDubbingLine = index,
                )
            }
        }
        engine.onFallbackRequested = { _, detail ->
            if (engine.currentTtsName != "系统TTS" || !isCurrentDubbingEngine(engine, sessionKey)) {
                false
            } else {
                // Return false so DubbingEngine marks this cue unavailable. Never replace the
                // user's explicitly selected System TTS engine; pause video instead of source fallback.
                updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                    state.copy(
                        dubbingStatus = "系统 TTS 合成失败，保持当前引擎",
                        noModelHint = "系统 TTS 合成失败：$detail。应用不会自动切换到本地模型，也不会要求下载 Kokoro/Piper。视频已暂停；请检查手机设置中的文字转语音服务、中文语音数据和可用音色后重新处理。",
                        dubbingHasErrors = true,
                        activeDubbingLine = -1,
                    )
                }
                pausePlaybackAfterDubbingFailure(engine, sessionKey, "系统 TTS 合成失败：${detail.take(180)}")
                false
            }
        }

        engine.onSynthesisError = { detail ->
            val activeName = engine.currentTtsName.ifBlank { tts.name }
            val systemTtsSelected = activeName.contains("系统TTS")
            val message = if (systemTtsSelected) {
                "系统 TTS 合成或输出失败：$detail。当前保持系统 TTS，不会自动切换或要求下载本地模型。视频已暂停，请检查手机的文字转语音服务、中文语音数据和可用音色后重新处理。"
            } else {
                "当前配音引擎「$activeName」合成或输出失败，视频已暂停。$detail"
            }
            updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                state.copy(
                    ttsEngineName = activeName,
                    dubbingStatus = if (systemTtsSelected) {
                        "系统 TTS 合成失败，视频已暂停"
                    } else {
                        "$activeName 合成或播放失败，视频已暂停"
                    },
                    noModelHint = message,
                    // Keep the selected engine and the user's audio preference; the error hint
                    // makes a failed voice explicit instead of silently switching audio settings.
                    dubbingHasErrors = true,
                    activeDubbingLine = -1,
                )
            }
            pausePlaybackAfterDubbingFailure(engine, sessionKey, "$activeName 合成/输出失败：${detail.take(180)}")
            if (detail.contains("设备内存不足")) {
                // Retire even if attach() reported the OOM just before publishing the engine.
                // retireDubbing only clears the service field when this exact engine owns it.
                scope.launch(Dispatchers.IO) {
                    retireDubbing(engine)
                    runCatching { awaitRetiredDubbingEngines() }
                        .onFailure { android.util.Log.w("PlaybackService", "TTS cleanup after memory failure did not finish cleanly", it) }
                }
            }
        }
    }

    private suspend fun saveHistory(meta: VideoEntity) {
        val db = AppDatabase.get(this)
        val old = db.videoDao().byKey(meta.key)
        if (old != null) db.videoDao().upsert(old.copy(lastPlayedAt = System.currentTimeMillis()))
        else db.videoDao().upsert(meta.copy())
    }

    // ============ 时间轴驱动 ============
    private fun subtitleHighlightLineAt(lines: List<SubLine>, positionMs: Long): Int {
        if (highlightTimelineSource !== lines) {
            highlightTimeline = DubbingTimeline(lines)
            highlightTimelineSource = lines
            // Don't carry a line index across a newly published subtitle snapshot.
            lastLineIdx = Int.MIN_VALUE
        }
        val activeLine = highlightTimeline.lineAt(positionMs)
        if (activeLine in lines.indices) return activeLine

        // 首句宽限期：ASR 给第一句的起点普遍偏晚（视频已开口、时间戳还没到）。
        // 播放头处于片头空白且第一句在 2.5s 内开始时，直接提前显示第一句。
        val firstSpoken = lines.indexOfFirst { it.text.isNotBlank() }
        if (firstSpoken >= 0) {
            val first = lines[firstSpoken]
            val lead = first.startMs - positionMs
            if (lead in 0L..2_500L) return firstSpoken
        }

        // 短间隙预告：下一句即将开始（≤600ms 且行间隙紧凑）时提前切入，
        // 消除"上一句话音刚落、屏幕空白半秒、下一句才弹出"的迟滞感。
        val previousLine = lines.getOrNull(lastLineIdx)
        if (previousLine != null && lastLineIdx + 1 in lines.indices) {
            val next = lines[lastLineIdx + 1]
            val cueGap = next.startMs - previousLine.endMs
            if (cueGap in 0L..2_000L && next.startMs - positionMs in 0L..600L) {
                return lastLineIdx + 1
            }
        }

        // Small timing gaps between cues should not make the unified caption strip flicker.
        // Keep the previous cue briefly, until the next cue starts or the gap is substantial.
        val fallbackPrevious = previousLine ?: return -1
        val gapAfterPreviousCue = positionMs - fallbackPrevious.endMs
        return if (gapAfterPreviousCue in 0L..1_200L) lastLineIdx else -1
    }
    /**
     * ExoPlayer invokes some callbacks synchronously from seek()/play() on its application looper.
     * A stale subtitle snapshot or a TTS transition must not let an ordinary runtime exception
     * escape that callback and terminate the app process.
     */
    /**
     * TTS engines and AudioTrack implementations can reject stop/pause during teardown or a seek.
     * These calls originate in Media3 callbacks; an ordinary engine exception must not escape them
     * and terminate the Android process.
     */
    private fun safelyControlDubbing(source: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            val state = ui.value
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService, "PlaybackSync",
                "播放器事件中同步配音状态失败：$source",
                error,
                diagnosticContext = mapOf(
                    "触发来源" to source,
                    "视频会话标识（去除查询参数）" to state.key.substringBefore('?').take(160),
                    "播放器阶段" to state.phase.name,
                    "视频播放位置 ms" to runCatching { if (::pm.isInitialized) pm.position() else -1L }.getOrDefault(-1L).toString(),
                    "当前字幕索引" to state.currentLine.toString(),
                    "当前配音字幕索引" to state.activeDubbingLine.toString(),
                    "是否开启配音" to state.dubbingOn.toString(),
                    "是否静音原声" to (state.originalVolume <= 0f).toString(),
                    "当前配音引擎" to state.ttsEngineName.ifBlank { "未确定" },
                    "恢复策略" to "恢复原声音量并清除活动配音行，继续视频播放",
                ),
            )
            android.util.Log.e("PlaybackService", "Unable to synchronize dubbing during $source", error)
            safelySetOriginalVolume(if (ui.value.originalVolume <= 0f) 0f else 1f, source)
            ui.update { state ->
                state.copy(
                    activeDubbingLine = -1,
                    dubbingStatus = "配音音频正在恢复，视频播放继续",
                )
            }
        }
    }

    private fun safelySetOriginalVolume(volume: Float, source: String) {
        try {
            if (playerReady && ::pm.isInitialized) {
                // Each path has its own user gain. Live System TTS must not force source audio to zero.
                val state = ui.value
                pm.originalVolume = if (state.originalVolume <= 0f) {
                    0f
                } else {
                    state.originalVolume.coerceIn(0f, 1f) * volume.coerceIn(0f, 1f)
                }
            }
        } catch (error: Exception) {
            io.translatetube.local.util.AppLogStore.warning(
                this@PlaybackService, "PlaybackSync",
                "播放期间调整原视频音量失败：$source",
                error,
                diagnosticContext = mapOf(
                    "触发来源" to source,
                    "视频会话标识（去除查询参数）" to ui.value.key.substringBefore('?').take(160),
                    "播放器阶段" to ui.value.phase.name,
                    "请求音量" to volume.toString(),
                    "当前是否静音原声" to (ui.value.originalVolume <= 0f).toString(),
                    "播放器是否初始化" to (::pm.isInitialized).toString(),
                ),
            )
            android.util.Log.w("PlaybackService", "Unable to set source volume during $source", error)
        }
    }

    private fun hasPendingDubbingAfterVideoEnd(sessionKey: String): Boolean {
        if (!::pm.isInitialized ||
            pm.player.playbackState != androidx.media3.common.Player.STATE_ENDED
        ) return false
        val state = ui.value
        if (state.key != sessionKey || videoKey != sessionKey || !state.dubbingOn) return false
        if (hasDubbingCatchUpCues(sessionKey)) return true
        val engine = dubbing ?: return false
        val index = engine.currentLineIdx()
        return index >= 0 && (
            engine.shouldContinueCurrentCue(-1) ||
                (!engine.hasStartedPlaybackFor(index) &&
                    engine.isPrepared(index) && !engine.isSilent(index))
            )
    }

    private fun clearDubbingCatchUpQueue(sessionKey: String? = null, positionMs: Long = -1L) {
        synchronized(dubbingCatchUpLock) {
            dubbingCatchUpQueue.clear()
            dubbingCatchUpSessionKey = sessionKey.orEmpty()
            lastDubbingCatchUpPositionMs = positionMs
        }
    }

    private fun peekDubbingCatchUpCue(sessionKey: String): Int? = synchronized(dubbingCatchUpLock) {
        if (dubbingCatchUpSessionKey == sessionKey) dubbingCatchUpQueue.firstOrNull() else null
    }

    private fun enqueueDubbingCatchUpCue(sessionKey: String, index: Int) {
        synchronized(dubbingCatchUpLock) {
            if (dubbingCatchUpSessionKey == sessionKey) dubbingCatchUpQueue.add(index)
        }
    }

    private fun removeDubbingCatchUpCue(sessionKey: String, index: Int) {
        synchronized(dubbingCatchUpLock) {
            if (dubbingCatchUpSessionKey == sessionKey && dubbingCatchUpQueue.firstOrNull() == index) {
                dubbingCatchUpQueue.remove(index)
            }
        }
    }

    private fun hasDubbingCatchUpCues(sessionKey: String): Boolean =
        peekDubbingCatchUpCue(sessionKey) != null

    private fun dubbingCatchUpQueueDepth(sessionKey: String): Int = synchronized(dubbingCatchUpLock) {
        if (dubbingCatchUpSessionKey == sessionKey) dubbingCatchUpQueue.size else 0
    }

    /** Record every playable cue whose start time passes while another sentence owns output. */
    private fun updateDubbingCatchUpQueue(
        state: PlayerUiState,
        positionMs: Long,
        captureMissedCues: Boolean,
        activeDubbingIndex: Int,
    ) {
        val previousPositionMs: Long
        synchronized(dubbingCatchUpLock) {
            if (dubbingCatchUpSessionKey != state.key) {
                dubbingCatchUpQueue.clear()
                dubbingCatchUpSessionKey = state.key
                lastDubbingCatchUpPositionMs = positionMs
                return
            }
            if (!state.dubbingOn) {
                dubbingCatchUpQueue.clear()
                lastDubbingCatchUpPositionMs = positionMs
                return
            }
            val previous = lastDubbingCatchUpPositionMs
            if (previous < 0L || positionMs < previous) {
                dubbingCatchUpQueue.clear()
                lastDubbingCatchUpPositionMs = positionMs
                return
            }
            previousPositionMs = previous
            lastDubbingCatchUpPositionMs = positionMs
        }
        if (!captureMissedCues || positionMs <= previousPositionMs) return

        val crossed = state.lines.indices.filter { index ->
            if (index == activeDubbingIndex) return@filter false
            val line = state.lines[index]
            DubbingTimeline.isPlayableCue(line) && line.startMs > previousPositionMs && line.startMs <= positionMs
        }.sortedWith(compareBy<Int> { state.lines[it].startMs }.thenBy { it })

        synchronized(dubbingCatchUpLock) {
            if (dubbingCatchUpSessionKey != state.key || !state.dubbingOn) return
            crossed.forEach { dubbingCatchUpQueue.add(it) }
        }
    }

    private fun isDubbingDrainActiveFor(engine: DubbingEngine, sessionKey: String): Boolean =
        dubbingDrainAfterVideoEnd &&
            dubbingDrainEngine === engine &&
            dubbingDrainSessionKey == sessionKey &&
            videoKey == sessionKey &&
            ui.value.key == sessionKey &&
            ui.value.dubbingOn

    private fun cancelDubbingDrainAfterVideoEnd() {
        val poll = dubbingDrainPoll
        dubbingDrainPoll = null
        dubbingDrainAfterVideoEnd = false
        dubbingDrainEngine = null
        dubbingDrainSessionKey = ""
        poll?.let(dubbingDrainHandler::removeCallbacks)
    }

    private fun startDubbingDrainAfterVideoEnd(engine: DubbingEngine, sessionKey: String) {
        if (!isCurrentDubbingEngine(engine, sessionKey) || !ui.value.dubbingOn) return
        if (isDubbingDrainActiveFor(engine, sessionKey)) return
        cancelDubbingDrainAfterVideoEnd()
        dubbingDrainAfterVideoEnd = true
        dubbingDrainEngine = engine
        dubbingDrainSessionKey = sessionKey

        lateinit var poll: Runnable
        poll = object : Runnable {
            override fun run() {
                if (!dubbingDrainAfterVideoEnd || dubbingDrainEngine !== engine ||
                    dubbingDrainSessionKey != sessionKey
                ) return
                if (!isDubbingDrainActiveFor(engine, sessionKey)) {
                    cancelDubbingDrainAfterVideoEnd()
                    if (videoKey == sessionKey && ui.value.key == sessionKey && !ui.value.dubbingOn) {
                        clearDubbingCatchUpQueue(sessionKey = sessionKey, positionMs = pm.position())
                        engine.stop()
                    }
                    return
                }
                if (!playerReady || !isCurrentDubbingEngine(engine, sessionKey)) {
                    cancelDubbingDrainAfterVideoEnd()
                    return
                }

                runCatching { tick(pm.position()) }.onFailure { error ->
                    io.translatetube.local.util.AppLogStore.error(
                        this@PlaybackService,
                        "PlaybackTimeline",
                        "视频结束后的剩余配音调度失败",
                        error,
                        diagnosticContext = mapOf(
                            "视频会话标识（去除查询参数）" to sessionKey.substringBefore('?').take(160),
                            "当前配音引擎" to engine.currentTtsName,
                            "当前字幕索引" to engine.currentLineIdx().toString(),
                            "剩余补播队列长度" to synchronized(dubbingCatchUpLock) {
                                dubbingCatchUpQueue.size
                            }.toString(),
                        ),
                    )
                }

                val index = engine.currentLineIdx()
                val activeSpeechRemains = index >= 0 && (
                    engine.shouldContinueCurrentCue(-1) ||
                        (!engine.hasStartedPlaybackFor(index) &&
                            engine.isPrepared(index) && !engine.isSilent(index))
                    )
                if (hasDubbingCatchUpCues(sessionKey) || activeSpeechRemains) {
                    dubbingDrainHandler.postDelayed(this, 50L)
                } else {
                    cancelDubbingDrainAfterVideoEnd()
                    engine.stop()
                    safelySetOriginalVolume(1f, "remaining dubbing completed")
                    updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                        state.copy(
                            isPlaying = false,
                            currentLine = -1,
                            activeDubbingLine = -1,
                            dubbingStatus = "视频已结束，剩余字幕配音已完成",
                        )
                    }
                }
            }
        }
        dubbingDrainPoll = poll
        dubbingDrainHandler.post(poll)
    }

    /**
     * Publish the initial visible subtitle before the first video/audio frame starts.
     * Waiting for onIsPlayingChanged means the playhead has already advanced before the
     * first highlight reaches Compose; this is noticeable for the first cue in both audio modes.
     */
    private fun primeSubtitleHighlightAtPlaybackStart(positionMs: Long) {
        if (positionMs < 0L) return
        val snapshot = ui.value
        if (snapshot.lines.isEmpty()) return

        val target = try {
            subtitleHighlightLineAt(
                snapshot.lines,
                positionMs + subtitleTimingOffsetMs(),
            )
        } catch (error: Exception) {
            android.util.Log.w(
                "PlaybackService",
                "Unable to prime the initial subtitle highlight",
                error,
            )
            return
        }

        lastLineIdx = target
        ui.update { state ->
            if (state.key == snapshot.key && state.lines === snapshot.lines) {
                state.copy(currentLine = target)
            } else {
                state
            }
        }
    }

    private fun tickFromPlayerCallback(ms: Long, source: String) {
        try {
            // 固定提前量：让配音/字幕更早出现（对冲转写字幕时间戳的系统性偏晚），
            // 配音与字幕共用同一个值保证内容一致。
            tick(ms + subtitleTimingOffsetMs())
        } catch (error: Exception) {
            val state = ui.value
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService, "PlaybackTimeline",
                "视频时间轴更新失败：$source",
                error,
                diagnosticContext = mapOf(
                    "触发来源" to source,
                    "视频会话标识（去除查询参数）" to state.key.substringBefore('?').take(160),
                    "触发时间位置 ms" to ms.toString(),
                    "播放器阶段" to state.phase.name,
                    "最近处理步骤" to state.progress.ifBlank { state.failedProgress }.take(200),
                    "当前字幕索引" to state.currentLine.toString(),
                    "当前配音字幕索引" to state.activeDubbingLine.toString(),
                    "配音引擎" to state.ttsEngineName.ifBlank { "未确定" },
                ),
            )
            android.util.Log.e("PlaybackService", "Timeline update failed during $source", error)
            val key = ui.value.key
            updateUiForSession(key) { state ->
                if (state.key == key) {
                    state.copy(
                        dubbingStatus = "字幕时间轴正在恢复，请稍后重试",
                        activeDubbingLine = -1,
                    )
                } else state
            }
        }
    }

    // 与 DubbingEngine 挂载配音时使用同一个读取逻辑：字幕和配音一起提前/延后。
    private fun subtitleTimingOffsetMs(): Long = SubtitleTimingOffset.LEAD_MS

    private fun tick(ms: Long) {
        val st = ui.value
        val d = dubbing
        if (d != null && st.dubbingOn) d.primeUpcomingCues(ms)

        // 配音必须严格使用 lineAt 的时间区间结果；字幕高亮可以回退到最近一行，
        // 但不能把“字幕间隙中的上一行”当成当前配音行，否则会压低间隙里的原声。
        val dubLine = if (d != null) d.lineAt(ms) else -1
        // AudioTrack startup is asynchronous; publish the newest media playhead even while PCM
        // is loading so the audio executor can skip elapsed speech instead of starting late.
        val previousDubbingLine = d?.currentLineIdx() ?: -1
        d?.updateTimelinePosition(dubLine, ms)
        val keepingTimelineCue = d?.usesImmediateTimelinePlayback == true &&
            d.shouldContinueCurrentCue(dubLine)
        // Retire a queue head only after its audio output actually starts.
        if (d != null && previousDubbingLine >= 0 && d.hasStartedPlaybackFor(previousDubbingLine)) {
            removeDubbingCatchUpCue(st.key, previousDubbingLine)
        }
        val queuedHead = if (d?.usesImmediateTimelinePlayback == true) peekDubbingCatchUpCue(st.key) else null
        val keepingQueuedCue = d != null && queuedHead != null && previousDubbingLine >= 0 &&
            previousDubbingLine != queuedHead && d.shouldContinueCurrentCue(queuedHead)
        val keepingPreviousDubbingCue = keepingTimelineCue || keepingQueuedCue
        updateDubbingCatchUpQueue(
            state = st,
            positionMs = ms,
            captureMissedCues = d?.usesImmediateTimelinePlayback == true &&
                (keepingPreviousDubbingCue || hasDubbingCatchUpCues(st.key) ||
                    (previousDubbingLine >= 0 && previousDubbingLine != dubLine)),
            activeDubbingIndex = previousDubbingLine,
        )
        // Highlight against the exact subtitle snapshot rendered by the UI. Its cached interval
        // index uses the same overlap/time-window rules as dubbing, without depending on an engine
        // that may be between old and replacement subtitle sessions.
        val highlightLine = subtitleHighlightLineAt(st.lines, ms)
        // 同时对照界面状态本身：别处（播放结束、暂停恢复、换字幕快照）把 currentLine 改掉但没有重置
        // lastLineIdx 时，只比较 lastLineIdx 会让字幕条一直停在错误的行，直到下一句才恢复。
        if (highlightLine != lastLineIdx || st.currentLine != highlightLine) {
            lastLineIdx = highlightLine
            ui.update { it.copy(currentLine = highlightLine) }
        }
        // 相邻两句合并配音时，配音念的是整块文本：字幕条跟着显示整块，听到的和看到的一致。
        val spokenCaption = if (d != null && st.dubbingOn && highlightLine in st.lines.indices) {
            runCatching { d.spokenCaptionFor(highlightLine) }.getOrNull()
        } else null
        if (spokenCaption != st.captionOverride) {
            ui.update { it.copy(captionOverride = spokenCaption) }
        }

        if (d != null && st.dubbingOn) {
            if (keepingPreviousDubbingCue) {
                // Finish the current sentence without pausing video. Captions crossed meanwhile
                // stay in the ordered catch-up queue instead of being skipped.
                d.updatePlayerSpeed(pm.playbackSpeed())
                // Voice volume is independent of source-audio state: never mix the two narrations.
                d.updateVolume(st.dubbingVolume)
                safelySetOriginalVolume(if (st.originalVolume <= 0f) 0f else 1f, "continuing a dubbed cue")
                d.prioritizeAround(peekDubbingCatchUpCue(st.key) ?: dubLine, lookAhead = 8)
            } else if (queuedHead != null) {
                // Live System TTS stays serialized; generated PCM playback is scheduled by bus timestamps.
                var catchUpIndex = queuedHead
                while (catchUpIndex != null && d.isPrepared(catchUpIndex) && d.isSilent(catchUpIndex)) {
                    removeDubbingCatchUpCue(st.key, catchUpIndex)
                    catchUpIndex = peekDubbingCatchUpCue(st.key)
                }
                if (catchUpIndex != null) {
                    val catchUpLine = st.lines.getOrNull(catchUpIndex)
                    if (catchUpLine == null || !DubbingTimeline.isPlayableCue(catchUpLine)) {
                        removeDubbingCatchUpCue(st.key, catchUpIndex)
                    } else if (d.currentLineIdx() != catchUpIndex) {
                        if (d.isPrepared(catchUpIndex)) {
                            d.playAt(
                                catchUpIndex,
                                st.dubbingVolume,
                                positionMs = catchUpLine.startMs,
                                playerSpeed = pm.playbackSpeed(),
                                completeCueWhenLate = true,
                                catchUpSyncRate = DubbingRecoveryRatePolicy.rateForBacklog(
                                    queueDepth = dubbingCatchUpQueueDepth(st.key),
                                    latenessMs = (pm.position() - catchUpLine.startMs).coerceAtLeast(0L),
                                ),
                                playbackAllowed = {
                                    val latest = ui.value
                                    ::pm.isInitialized &&
                                        (pm.isPlaying.value || isDubbingDrainActiveFor(d, st.key)) &&
                                        latest.key == st.key && latest.dubbingOn && dubbing === d
                                },
                            )
                        } else {
                            d.prioritizeAround(catchUpIndex, lookAhead = 8)
                            if (d.currentLineIdx() >= 0) d.stop()
                        }
                    }
                }
                safelySetOriginalVolume(if (st.originalVolume <= 0f) 0f else 1f, "catch-up dubbing playback")
            } else if (dubLine >= 0 && d.isSilent(dubLine)) {
                d.logSilentCueFallback(dubLine)
                if (d.currentLineIdx() >= 0) d.stop()
                // No synthesized voice is playing for this cue, so source audio is the only
                // available narration; it can never overlap a live dubbed cue.
                safelySetOriginalVolume(1f, "source audio restore requested")
                ui.update { state ->
                    if (state.key == st.key) {
                        state.copy(
                            noModelHint = state.noModelHint
                                ?: io.translatetube.local.tts.TtsFailureAdvice.missingAudioHint(st.ttsEngineName),
                            activeDubbingLine = -1,
                        )
                    } else state
                }
            } else if (dubLine >= 0 && d.isPrepared(dubLine)) {
                if (!pm.player.isPlaying) {
                    // seekTo() emits an immediate tick: don't start speech while video is paused.
                    // After the video ends, the explicit drain may finish already-queued speech; no
                    // timing-overrun path is allowed to pause or hold the video during normal playback.
                    if (d.currentLineIdx() >= 0 && !isDubbingDrainActiveFor(d, st.key)) d.stop()
                } else if (d.currentLineIdx() != dubLine) {
                    d.playAt(
                        dubLine,
                        st.dubbingVolume,
                        positionMs = ms,
                        playerSpeed = pm.playbackSpeed(),
                        playbackAllowed = {
                            val latest = ui.value
                            ::pm.isInitialized &&
                                (pm.isPlaying.value || isDubbingDrainActiveFor(d, st.key)) &&
                                latest.key == st.key &&
                                latest.dubbingOn &&
                                dubbing === d
                        },
                    )
                } else {
                    d.updatePlayerSpeed(pm.playbackSpeed())
                    // Voice volume is independent of source-audio state: never mix the two narrations.
                    d.updateVolume(st.dubbingVolume)
                }
                safelySetOriginalVolume(if (st.originalVolume <= 0f) 0f else 1f, "dubbing cue playback")
            } else if (dubLine >= 0) {
                // If generation is falling behind real time, continuously enlarge the look-ahead
                // priority window so the single TTS worker prepares more upcoming cues first.
                // Immediate mode has no PCM queue and therefore keeps the normal small window.
                val synthesisRtf = d.synthesisRealtimeFactor
                val lookAhead = when {
                    d.usesImmediateTimelinePlayback -> 2
                    synthesisRtf.isFinite() && synthesisRtf >= 1.6f -> 7
                    synthesisRtf.isFinite() && synthesisRtf >= 1.2f -> 6
                    synthesisRtf.isFinite() && synthesisRtf < 0.75f -> 2
                    else -> 4
                }
                d.prioritizeAround(dubLine, lookAhead = lookAhead)
                if (!d.usesImmediateTimelinePlayback && synthesisRtf.isFinite() && synthesisRtf >= 1.2f) {
                    updateUiForCurrentDubbingEngine(d, st.key) { state ->
                        if (state.dubbingStatus.startsWith("配音生成偏慢")) state
                        else state.copy(
                            dubbingStatus = "配音生成偏慢，正在优先准备后续字幕；未就绪片段按原声开关设置处理",
                        )
                    }
                }
                if (d.currentLineIdx() >= 0) d.stop()
                safelySetOriginalVolume(1f, "source audio restore requested")
            } else {
                // Outside a dubbed cue, keep the original track as the only audio source.
                if (d.currentLineIdx() >= 0) d.stop()
                safelySetOriginalVolume(if (st.originalVolume <= 0f) 0f else 1f, "source audio timeline policy")
            }
        } else {
            if (d != null && !st.dubbingOn) {
                // Source-only playback still drives the synthesis queue. Prioritize captions near
                // the current playhead so the corresponding Chinese PCM is ready before the user
                // switches back to dubbed mode; this never starts audible dubbing output.
                val synthesisTarget = when {
                    dubLine in st.lines.indices -> dubLine
                    highlightLine in st.lines.indices -> highlightLine
                    else -> -1
                }
                d.prepareForBackgroundPlayback(synthesisTarget, lookAhead = 8)
            }
            // With no active dubbed speech, only the original track can be heard.
            safelySetOriginalVolume(if (st.originalVolume <= 0f) 0f else 1f, "source audio timeline policy")
            if (
                st.dubbingOn &&
                st.originalVolume <= 0f &&
                st.lines.isNotEmpty() &&
                st.noModelHint == null &&
                st.phase == Phase.READY &&
                !st.waitingForDubbingEngine
            ) {
                ui.update {
                    if (it.key == st.key) {
                        it.copy(
                            noModelHint = "配音引擎尚未启动或未能创建。视频音量仍按播放页的原声开关处理。系统 TTS 请检查手机文字转语音服务与中文语音数据；其他引擎请检查模型是否就绪。"
                        )
                    } else it
                }
            }
        }

        // A pause or dubbing toggle can race with a tick that already captured the previous UI
        // state. Re-check the live owner after scheduling playback so stale work cannot leave an
        // AudioTrack speaking after pause, disable, or engine replacement.
        val latestState = ui.value
        if (
            d != null &&
            DubbingPlaybackOwnerPolicy.shouldStop(
                hasSelectedLine = d.currentLineIdx() >= 0,
                dubbingEnabled = latestState.dubbingOn,
                playerIsPlaying = ::pm.isInitialized &&
                    (pm.player.isPlaying || isDubbingDrainActiveFor(d, st.key)),
                engineIsCurrent = dubbing === d,
            )
        ) {
            d.stop()
            if (::pm.isInitialized) {
                safelySetOriginalVolume(if (latestState.originalVolume <= 0f) 0f else 1f, "stale dubbing playback stop")
            }
        }

        // Keep the displayed duration synchronized with ExoPlayer in case the extractor metadata
        // omitted or misreported the duration. This also prevents a permanent 00:00:00 total time.
        val activeDubbingLine = d?.currentLineIdx()?.takeIf { index ->
            latestState.dubbingOn &&
                ::pm.isInitialized &&
                pm.player.isPlaying &&
                dubbing === d &&
                d.hasStartedPlaybackFor(index)
        } ?: -1
        ui.update { state ->
            if (state.activeDubbingLine == activeDubbingLine) state
            else state.copy(activeDubbingLine = activeDubbingLine)
        }

        val actualDuration = if (::pm.isInitialized) pm.duration() else 0L
        if (actualDuration > 0L && ui.value.durationMs != actualDuration) {
            ui.update { it.copy(durationMs = actualDuration) }
        }
    }

    // ============ UI 控制命令 ============
    /**
     * 手动为某条字幕指定 Kokoro 音色。修改后重建配音缓存队列，
     * 文件缓存按音色分开，避免修改人物音色后仍播放旧声音。
     */
    /**
     * 从当前视频音轨执行本地说话人分离，并把说话人编号映射到不同 Kokoro 预设音色。
     * 模型仅在用户点击自动区分时下载，不会拖慢普通视频打开流程。
     */
    fun autoAssignSpeakers() {
        if (speakerJob?.isActive == true) return
        val selectedTts = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("tts_engine", "edge") ?: "edge"
        val originalLines = ui.value.lines
        if (originalLines.isEmpty()) {
            ui.update { it.copy(error = "当前没有字幕，先生成或导入字幕再区分人物") }
            return
        }
        if (selectedTts == "kokoro" && !kokoroRuntimeReady()) {
            ui.update { it.copy(error = "Kokoro 配音模型尚未就绪；已保留当前播放和字幕，请先在设置中准备模型后再区分人物。") }
            return
        }
        val sessionKey = videoKey
        val sessionVideoId = videoId
        val sessionLocalUri = localUri
        val sessionRemoteMediaUrl = remoteMediaUrl
        val sessionRemoteMediaReferrer = remoteMediaReferrer
        val sessionRemoteMediaCookie = remoteMediaCookie
        val sessionState = ui.value
        val translationCacheKey = sessionState.translationCacheKey.ifBlank {
            "$sessionKey|zh|${getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)}"
        }
        val sessionSourceLang = sessionState.sourceLang
        val preferredVoice = getSharedPreferences("settings", MODE_PRIVATE).getInt("kokoro_speaker", 14).coerceIn(0, 53)
        val pipelineGeneration = activePipelineGeneration
        val mutationGeneration = beginDubbingMutationGeneration()
        speakerJob = scope.launch {
            pipelineExecutionMutex.withLock {
            var pendingTts: TtsEngine? = null
            var oldEngineRetired = false
            var detectedSpeakerCount = 0
            try {
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                    it.copy(
                        error = null,
                        progress = if (selectedTts == "system") "检查手机可用的中文音色…" else "准备人物识别音频（为荣耀 10 限制为前 3 分钟）…",
                        phase = Phase.TRANSCRIBING,
                    )
                }
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                    it.copy(
                        progress = if (selectedTts == "system") "检查手机可用的中文音色…" else "初始化所选配音引擎…",
                    )
                }
                // Initialize the selected engine before the expensive diarization step and before
                // retiring the current dubbing engine. If TTS setup fails, playback remains intact.
                val preflightEngine = makeTtsOffMainThread(selectedTts)
                pendingTts = preflightEngine
                val systemChineseVoiceCount =
                    (preflightEngine as? io.translatetube.local.tts.SystemTts)?.chineseVoiceCount ?: 0
                if (!io.translatetube.local.tts.TtsSpeakerCapabilityPolicy
                        .supportsDistinctVoices(selectedTts, systemChineseVoiceCount)
                ) {
                    val message = when (selectedTts) {
                        "piper" ->
                            "当前 Piper 中文模型为单人音色，不能为不同人物分配不同声音。已取消人物识别；普通 Piper 配音仍可使用。请切换到 Kokoro 或支持多种中文音色的系统 TTS。"
                        "system" ->
                            "手机系统 TTS 只提供一个可用中文音色，无法为不同人物分配不同音色；普通系统配音仍可使用。"
                        else -> "当前选择的配音引擎不支持多人物音色分配。"
                    }
                    updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                        it.copy(phase = Phase.READY, progress = "", error = message)
                    }
                    return@launch
                }
                val speakerDecodeJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                val pcm = withContext(Dispatchers.IO) {
                    val sourceFile: File?
                    val sourceUri: Uri?
                    if (sessionVideoId != null) {
                        val id = sessionVideoId
                        val audio = obtainAsrAudioFile(id, sessionKey, "复用已下载音频，正在分析人物声音…")
                        updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                            if (state.key == sessionKey) {
                                state.copy(phase = Phase.TRANSCRIBING, progress = "解码音频，准备分析人物声音…")
                            } else state
                        }
                        sourceFile = audio
                        sourceUri = null
                    } else if (sessionRemoteMediaUrl != null) {
                        // Web media uses the same bounded direct/HLS audio cache as ASR so
                        // speaker analysis never falls back to a missing local URI or redownloads.
                        val audio = obtainRemoteMediaAsrFile(
                            sessionRemoteMediaUrl,
                            sessionRemoteMediaReferrer,
                            sessionRemoteMediaCookie,
                            sessionKey,
                            "复用网页视频音频，正在分析人物声音…",
                        )
                        updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                            if (state.key == sessionKey) {
                                state.copy(phase = Phase.TRANSCRIBING, progress = "解码网页音频，准备分析人物声音…")
                            } else state
                        }
                        sourceFile = audio
                        sourceUri = null
                    } else {
                        sourceFile = null
                        sourceUri = sessionLocalUri ?: throw IllegalStateException("找不到当前视频文件")
                    }
                    val leasedPath = sourceFile?.absolutePath
                    try {
                        AudioExtract.decodeTo16kMono(
                            this@PlaybackService,
                            sourceUri,
                            sourceFile,
                            maxDurationMs = 3 * 60 * 1000L,
                            checkActive = { speakerDecodeJob?.ensureActive() },
                        )
                    } finally {
                        releaseActiveAsrAudio(leasedPath)
                    }
                }
                val segments = SpeakerDiarizer.diarize(this@PlaybackService, pcm.samples) { message ->
                    updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                        if (state.key == sessionKey) state.copy(progress = message) else state
                    }
                }
                detectedSpeakerCount = segments.map { it.speaker }.distinct().size
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                if (segments.isEmpty()) throw IllegalStateException("没有识别到可区分的说话人")
                val assignmentSnapshot = loadSpeakerAssignments(sessionKey, selectedTts)
                val speakerVoiceAssignments = assignmentSnapshot.speakerVoices.toMutableMap()
                val lineVoiceAssignments = assignmentSnapshot.lineVoices.toMutableMap()
                val linePersonAssignments = assignmentSnapshot.lineSpeakers.toMutableMap()
                // sherpa-onnx cluster labels are arbitrary identifiers, not persistent person IDs.
                // Normalize them by first appearance so a fresh diarization run cannot swap voices
                // merely because the model numbered the same people differently.
                val canonicalSpeakerIds = SpeakerAssignmentPolicy.canonicalSpeakerIds(
                    segments.map { segment ->
                        SpeakerAssignmentPolicy.SpeakerObservation(
                            speakerId = segment.speaker,
                            startMs = (segment.startSec * 1000f).toLong(),
                            endMs = (segment.endSec * 1000f).toLong(),
                        )
                    }
                )
                val speakerObservations = segments.map { segment ->
                    SpeakerAssignmentPolicy.SpeakerObservation(
                        speakerId = segment.speaker,
                        startMs = (segment.startSec * 1000f).toLong(),
                        endMs = (segment.endSec * 1000f).toLong(),
                        confidence = segment.confidence,
                    )
                }
                val updated = originalLines.map { line ->
                    val cueDurationMs = if (line.startMs >= 0L && line.endMs > line.startMs) {
                        line.endMs - line.startMs
                    } else {
                        0L
                    }
                    val minimumOverlapMs = minOf(250L, (cueDurationMs.toDouble() * 0.2).toLong())
                    val detectedSpeaker = SpeakerAssignmentPolicy.bestSpeakerForCue(
                        observations = speakerObservations,
                        cueStartMs = line.startMs,
                        cueEndMs = line.endMs,
                        minimumOverlapMs = minimumOverlapMs,
                    )
                    val speaker = canonicalSpeakerIds[detectedSpeaker] ?: -1
                    // Assign each diarized person a stable voice for this video. Manual cue
                    // choices take precedence over the automatic speaker mapping.
                    val suggestedVoice = io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy
                        .voiceForCanonicalSpeaker(
                            engineId = selectedTts,
                            canonicalSpeakerId = speaker,
                            systemChineseVoiceCount = systemChineseVoiceCount,
                            preferredKokoroVoice = preferredVoice,
                        )
                    val keyForLine = SpeakerAssignmentCodec.lineKey(line.startMs, line.endMs)
                    val voice = assignmentSnapshot.lineOverrides[keyForLine] ?: if (speaker in 0..999) {
                        speakerVoiceAssignments.getOrPut(speaker) { suggestedVoice }
                    } else {
                        suggestedVoice
                    }
                    lineVoiceAssignments[keyForLine] = voice
                    if (speaker >= 0) {
                        linePersonAssignments[keyForLine] = speaker
                    } else {
                        linePersonAssignments.remove(keyForLine)
                    }
                    line.copy(speakerId = voice)
                }
                saveSpeakerAssignments(
                    assignmentSnapshot.copy(
                        speakerVoices = speakerVoiceAssignments,
                        lineVoices = lineVoiceAssignments,
                        lineSpeakers = linePersonAssignments,
                    ),
                    sessionKey,
                    selectedTts,
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val distinctVoices = updated.map { it.speakerId }.distinct().size
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                    if (state.key == sessionKey) {
                        state.copy(
                            lines = updated,
                            progress = "已区分人物，使用 ${distinctVoices} 种音色",
                            phase = Phase.SYNTHESIZING,
                            totalLines = updated.size,
                            preparedUntil = 0,
                            preparedCount = 0,
                            currentLine = -1,
                        )
                    } else state
                }
                // Keep the configured engine fixed for this operation. The selected TTS was
                // preflighted before diarization so construction failures cannot stop the current dub.
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val previousDubbing = retireCurrentDubbing()
                oldEngineRetired = previousDubbing != null
                awaitRetiredDubbingEngines()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val ttsWasPreflighted = pendingTts != null
                val tts = pendingTts ?: makeTtsOffMainThread(selectedTts)
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!ttsWasPreflighted) runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope, pm.dubbingBus)
                } catch (e: Exception) {
                    if (!ttsWasPreflighted) runCatching { tts.close() }
                    throw e
                }
                pendingTts = null
                registerDubbingFailureHandler(d, tts, sessionKey)
                d.attach(tts, updated, sourceLanguage = sessionSourceLang)
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                if (!publishDubbingEngine(d, sessionKey, pipelineGeneration, mutationGeneration)) {
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("人物配音任务已过期")
                }
                dubCollectJob?.cancel()
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForCurrentDubbingEngine(d, sessionKey) { state ->
                                if (state.key == sessionKey) {
                                    state.copy(preparedUntil = prefix, preparedCount = readyCount)
                                } else state
                            }
                        }
                }
                val arr = org.json.JSONArray()
                for (line in updated) arr.put(
                    org.json.JSONObject().put("s", line.startMs).put("e", line.endMs)
                        .put("t", line.text).put("zh", line.zh ?: "").put("sp", line.speakerId)
                )
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                if (updated.count { !it.zh.isNullOrEmpty() } > updated.size / 2) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            translationCacheKey,
                            sessionSourceLang, arr.toString(), System.currentTimeMillis(),
                            videoKey = sessionKey,
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                    if (state.key == sessionKey) {
                        state.copy(
                            phase = Phase.READY,
                            progress = "人物区分完成：已识别 ${segments.map { speaker -> speaker.speaker }.distinct().size} 位说话人",
                        )
                    } else state
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
                    this@PlaybackService, "SpeakerDiarization",
                    "自动区分人物失败，未能完成字幕人物与配音引擎重新装配",
                    e,
                    diagnosticContext = mapOf(
                        "视频会话标识（去除查询参数）" to sessionKey.substringBefore('?').take(160),
                        "故障阶段" to ui.value.phase.name,
                        "原字幕行数" to originalLines.size.toString(),
                        "识别到人物数量" to detectedSpeakerCount.toString(),
                        "配音引擎" to selectedTts,
                        "旧配音引擎是否已释放" to oldEngineRetired.toString(),
                        "错误后的恢复" to "恢复原字幕和原声；保留原有音色分配以便重试",
                    ),
                )
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) { state ->
                    if (state.key == sessionKey && videoKey == sessionKey) {
                        state.copy(
                            phase = Phase.READY,
                            progress = "",
                            muteOriginal = state.originalVolume <= 0f,
                            error = "自动区分人物失败：${e.message}",
                        )
                    } else state
                }
                if (oldEngineRetired && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) { safelySetOriginalVolume(1f, "source audio restore requested") }
                }
            } finally {
                pendingTts?.close()
            }
            }
        }
    }

    fun setLineSpeaker(index: Int, speakerId: Int, applyToPerson: Boolean = false) {
        val sessionState = ui.value
        val sessionKey = sessionState.key
        if (sessionKey.isBlank() || videoKey != sessionKey) return
        val oldLines = sessionState.lines
        if (index !in oldLines.indices) return
        val selectedVoice = speakerId.coerceIn(-1, 53)
        // Applying the same tone to every cue of a person is still a meaningful action even
        // when the currently selected cue already has that tone.
        if (oldLines[index].speakerId == selectedVoice && !applyToPerson) return

        val selectedTts = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("tts_engine", "edge") ?: "edge"
        if (selectedTts == "kokoro" && !kokoroRuntimeReady()) {
            updateUiForSession(sessionKey) {
                it.copy(error = "Kokoro 配音模型尚未就绪，当前人物音色未更改。")
            }
            return
        }

        speakerJob?.cancel()
        val selectedLine = oldLines[index]
        val translationCacheKey = sessionState.translationCacheKey.ifBlank {
            "$sessionKey|zh|${getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)}"
        }
        val sourceLang = sessionState.sourceLang
        val assignmentSnapshot = loadSpeakerAssignments(sessionKey, selectedTts)
        val selectedLineKey = SpeakerAssignmentCodec.lineKey(selectedLine.startMs, selectedLine.endMs)
        val personId = assignmentSnapshot.lineSpeakers[selectedLineKey]
        if (applyToPerson && personId == null) {
            updateUiForSession(sessionKey) {
                it.copy(error = "当前字幕还没有可靠的人物编号，请先执行“识别并区分人物”，再应用到该人物的全部字幕。")
            }
            return
        }
        val personLineKeys = if (applyToPerson && personId != null) {
            SpeakerAssignmentResolver.lineKeysForPerson(oldLines, assignmentSnapshot, personId)
        } else {
            setOf(selectedLineKey)
        }
        val updated = oldLines.mapIndexed { i, line ->
            val key = SpeakerAssignmentCodec.lineKey(line.startMs, line.endMs)
            if (key in personLineKeys) line.copy(speakerId = selectedVoice) else line
        }
        val updatedAssignments = if (applyToPerson && personId != null) {
            SpeakerAssignmentResolver.applyToneToPerson(
                assignmentSnapshot,
                personId,
                selectedVoice,
                personLineKeys,
            )
        } else {
            assignmentSnapshot.copy(
                lineOverrides = assignmentSnapshot.lineOverrides + (selectedLineKey to selectedVoice),
            )
        }

        val pipelineGeneration = activePipelineGeneration
        val mutationGeneration = beginDubbingMutationGeneration()
        speakerJob = scope.launch {
            pipelineExecutionMutex.withLock {
            var pendingTts: TtsEngine? = null
            var replacementDubbing: DubbingEngine? = null
            var previousDubbing: DubbingEngine? = null
            var oldEngineRetired = false
            var assignmentsSaved = false
            try {
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                    it.copy(error = null, progress = "检查可用音色…")
                }

                // Initialize the selected engine before touching the active voice. A phone that
                // offers only one Chinese system voice cannot perform manual multi-speaker changes;
                // in that case the existing dub keeps playing unchanged.
                pendingTts = makeTtsOffMainThread(selectedTts)
                val systemChineseVoiceCount =
                    (pendingTts as? io.translatetube.local.tts.SystemTts)?.chineseVoiceCount ?: 0
                if (!io.translatetube.local.tts.TtsSpeakerCapabilityPolicy
                        .supportsDistinctVoices(selectedTts, systemChineseVoiceCount)
                ) {
                    val message = when (selectedTts) {
                        "piper" ->
                            "当前 Piper 中文模型为单人音色，人物音色未更改。若要多人物使用不同声音，请切换到 Kokoro 或支持多种中文音色的系统 TTS。"
                        "system" ->
                            "手机系统 TTS 只提供一个可用中文音色，当前人物音色未更改；普通系统配音仍可使用。"
                        else -> "当前选择的配音引擎不支持多人物音色分配，人物音色未更改。"
                    }
                    updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                        it.copy(progress = "", error = message)
                    }
                    return@launch
                }
                if (selectedTts == "system" &&
                    selectedVoice >= (systemChineseVoiceCount - 1).coerceAtLeast(0)
                ) {
                    updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                        it.copy(
                            progress = "",
                            error = "所选系统音色在当前手机上不可用，请从现有中文音色列表中选择。",
                        )
                    }
                    return@launch
                }
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)

                previousDubbing = retireCurrentDubbing()
                oldEngineRetired = previousDubbing != null
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)

                saveSpeakerAssignments(updatedAssignments, sessionKey, selectedTts)
                assignmentsSaved = true
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                    it.copy(
                        lines = updated,
                        preparedUntil = 0,
                        preparedCount = 0,
                        totalLines = updated.size,
                        currentLine = -1,
                        phase = Phase.SYNTHESIZING,
                        progress = "更新人物音色…",
                    )
                }

                val tts = pendingTts ?: makeTtsOffMainThread(selectedTts)
                val engine = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope, pm.dubbingBus)
                } catch (e: Exception) {
                    if (pendingTts == null) runCatching { tts.close() }
                    throw e
                }
                pendingTts = null
                replacementDubbing = engine
                registerDubbingFailureHandler(engine, tts, sessionKey)
                engine.attach(tts, updated, sourceLanguage = sourceLang)
                try {
                    ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(engine)
                    replacementDubbing = null
                    throw e
                }
                if (!publishDubbingEngine(engine, sessionKey, pipelineGeneration, mutationGeneration)) {
                    replacementDubbing = null
                    retireDubbing(engine)
                    throw kotlinx.coroutines.CancellationException("人物音色修改任务已过期")
                }

                dubCollectJob?.cancel()
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(engine.preparedUntil, engine.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForCurrentDubbingEngine(engine, sessionKey) { state ->
                                state.copy(
                                    preparedUntil = prefix,
                                    preparedCount = readyCount,
                                    phase = if (prefix >= updated.size) Phase.READY else Phase.SYNTHESIZING,
                                    progress = if (prefix >= updated.size) "" else "正在准备多人物配音…",
                                )
                            }
                        }
                }

                val cacheRows = org.json.JSONArray()
                updated.forEach { line ->
                    cacheRows.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                            .put("zh", line.zh ?: "")
                            .put("sp", line.speakerId)
                    )
                }
                ensureCurrentSession(sessionKey, pipelineGeneration, mutationGeneration)
                if (updated.isNotEmpty() &&
                    updated.count { !it.zh.isNullOrBlank() } > updated.size / 2
                ) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            translationCacheKey,
                            sourceLang,
                            cacheRows.toString(),
                            System.currentTimeMillis(),
                            videoKey = sessionKey,
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.translatetube.local.util.AppLogStore.error(
                    this@PlaybackService,
                    "SpeakerVoiceAssignment",
                    "切换人物或字幕音色失败，配音引擎重新装配未完成",
                    e,
                    diagnosticContext = mapOf(
                        "视频会话标识（去除查询参数）" to sessionKey.substringBefore('?').take(160),
                        "故障阶段" to ui.value.phase.name,
                        "原字幕行数" to oldLines.size.toString(),
                        "目标配音引擎" to selectedTts,
                        "音色分配是否已经保存" to assignmentsSaved.toString(),
                        "旧配音引擎是否已释放" to oldEngineRetired.toString(),
                        "失败后的恢复" to "恢复原字幕和可用原声，必要时回滚音色分配",
                    ),
                )
                // Restore the persisted assignment and translated captions if setup failed before
                // a replacement engine became usable. If the old native engine was already retired,
                // restore original audio rather than leaving the video silent.
                if (replacementDubbing != null && dubbing === replacementDubbing) {
                    dubbing = null
                }
                replacementDubbing?.let { retireDubbing(it) }
                awaitRetiredDubbingEngines()
                if (assignmentsSaved && videoKey == sessionKey && ui.value.key == sessionKey) {
                    runCatching { saveSpeakerAssignments(assignmentSnapshot, sessionKey, selectedTts) }
                }
                updateUiForSession(sessionKey, pipelineGeneration, mutationGeneration) {
                    it.copy(
                        lines = oldLines,
                        totalLines = oldLines.size,
                        phase = Phase.ERROR,
                        progress = "",
                        // If preflight failed before retiring the old engine, preserve its audio mode.
                        muteOriginal = it.originalVolume <= 0f,
                        error = "切换人物音色失败：${e.message}",
                    )
                }
                if (oldEngineRetired && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) { safelySetOriginalVolume(1f, "source audio restore requested") }
                }
            } finally {
                pendingTts?.close()
            }
            }
        }
    }

    private fun currentTtsEngineId(): String =
        getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "edge") ?: "edge"

    private fun assignmentStorageKey(identity: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return "video_" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assignmentPrefsKey(
        key: String,
        engineId: String = currentTtsEngineId(),
    ): String = assignmentStorageKey(SpeakerAssignmentCodec.storageIdentity(key, engineId))

    /** Key used before assignments were scoped by TTS engine; read it once for migration only. */
    private fun legacyAssignmentPrefsKey(key: String): String = assignmentStorageKey(key)

    private fun migrateLegacyAssignments(
        snapshot: SpeakerAssignmentSnapshot,
        engineId: String,
    ): SpeakerAssignmentSnapshot {
        return when (engineId) {
            "piper" -> SpeakerAssignmentSnapshot()
            "system" -> {
                val names = io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(
                    getSharedPreferences("settings", MODE_PRIVATE).getString(
                        io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY,
                        null,
                    )
                )
                // If the current ROM catalogue is known, convert invalid old Kokoro tone IDs to
                // the default instead of silently wrapping them to an unrelated system voice.
                val alternativeCount = (names.size - 1).coerceAtLeast(0)
                if (names.isEmpty()) snapshot else {
                    fun validTone(tone: Int): Int =
                        if (tone == -1 || tone in 0 until alternativeCount) tone else -1
                    snapshot.copy(
                        speakerVoices = snapshot.speakerVoices.mapValues { (_, tone) -> validTone(tone) },
                        lineVoices = snapshot.lineVoices.mapValues { (_, tone) -> validTone(tone) },
                        lineOverrides = snapshot.lineOverrides.mapValues { (_, tone) -> validTone(tone) },
                    )
                }
            }
            else -> snapshot
        }
    }

    private fun loadSpeakerAssignments(
        key: String = videoKey,
        engineId: String = currentTtsEngineId(),
    ): SpeakerAssignmentSnapshot {
        if (key.isBlank()) return SpeakerAssignmentSnapshot()
        val prefs = getSharedPreferences("speaker_assignments_v1", MODE_PRIVATE)
        val scopedKey = assignmentPrefsKey(key, engineId)
        val scopedRaw = prefs.getString(scopedKey, null)
        if (scopedRaw != null) return SpeakerAssignmentCodec.decode(scopedRaw)

        val legacyRaw = prefs.getString(legacyAssignmentPrefsKey(key), null)
            ?: return SpeakerAssignmentSnapshot()
        // On the first upgraded run, the player may load subtitle rows before SystemTts has
        // persisted its real voice catalogue. Don't permanently migrate legacy Kokoro/system tone
        // IDs without knowing what the system IDs refer to; retry migration after TTS initializes.
        if (engineId == "system" &&
            io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(
                getSharedPreferences("settings", MODE_PRIVATE).getString(
                    io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY,
                    null,
                )
            ).isEmpty()
        ) {
            return SpeakerAssignmentSnapshot()
        }
        val migrated = migrateLegacyAssignments(SpeakerAssignmentCodec.decode(legacyRaw), engineId)
        prefs.edit().putString(scopedKey, SpeakerAssignmentCodec.encode(migrated)).apply()
        return migrated
    }

    private fun saveSpeakerAssignments(
        snapshot: SpeakerAssignmentSnapshot,
        key: String = videoKey,
        engineId: String = currentTtsEngineId(),
    ) {
        if (key.isBlank()) return
        getSharedPreferences("speaker_assignments_v1", MODE_PRIVATE)
            .edit()
            .putString(assignmentPrefsKey(key, engineId), SpeakerAssignmentCodec.encode(snapshot))
            .apply()
    }

    /**
     * 只给 YouTube 用：YouTube 自带字幕的起点不是按人声开口量的，第一句配音会比原声慢一点。
     * 这里解码音轨开头几秒，量出人真正开口的时间，把第一句的起点提前到
     * "实测开口 + 统一提前量"（字幕与配音共用同一个起点，所以两者一起准）。
     * 只往前挪；量不出来、超时、出错都保持原样，不影响正常流程。
     * 结果按 视频ID+原起点 缓存，同一个视频只会量一次。
     */
    private suspend fun calibrateYouTubeFirstCue(videoId: String, lines: List<SubLine>): List<SubLine> {
        var firstPos = -1
        for (index in lines.indices) {
            if (lines[index].text.isBlank()) continue
            if (firstPos < 0 || lines[index].startMs < lines[firstPos].startMs) firstPos = index
        }
        if (firstPos < 0) return lines
        val first = lines[firstPos]
        // 片头太久才开口，或一开始就在 0 附近：没什么可校准的。
        if (first.startMs !in 200L..60_000L) return lines
        var nextStartMs: Long? = null
        for (index in lines.indices) {
            if (index == firstPos) continue
            val start = lines[index].startMs
            if (nextStartMs == null || start < nextStartMs) nextStartMs = start
        }

        val prefs = getSharedPreferences("yt_first_onset_v1", MODE_PRIVATE)
        val cacheKey = "$videoId@${first.startMs}"
        val onsetMs: Long? = if (prefs.contains(cacheKey)) {
            prefs.getLong(cacheKey, -1L)
        } else {
            val measured = measureYouTubeFirstOnset(videoId, first.startMs)
            // 只缓存"确实量过了"的结果（含"没量出来"=-1）；超时/出错下次再试。
            if (measured != null) prefs.edit().putLong(cacheKey, measured).apply()
            measured
        }
        if (onsetMs == null || onsetMs < 0L) return lines

        val newStart = SpeechOnsetDetector.calibratedStartMs(onsetMs, first.startMs, first.endMs, nextStartMs)
        io.translatetube.local.util.AppLogStore.info(
            this,
            "PlaybackFlow",
            if (newStart != null) "YouTube 第一句起点已按实测开口校准" else "YouTube 第一句起点无需校准",
            diagnosticContext = mapOf(
                "字幕原起点ms" to first.startMs.toString(),
                "实测开口ms" to onsetMs.toString(),
                "校准后起点ms" to (newStart ?: first.startMs).toString(),
            ),
        )
        if (newStart == null) return lines
        return lines.toMutableList().also { it[firstPos] = first.copy(startMs = newStart) }
    }

    /** @return 实测开口毫秒；-1 = 量过但没把握；null = 失败/超时（不缓存）。 */
    private suspend fun measureYouTubeFirstOnset(videoId: String, captionStartMs: Long): Long? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.async<Long?> {
            val self = kotlin.coroutines.coroutineContext[Job]
            val stream = NewPipeRepo.audioForAsr(videoId) ?: return@async null
            val (fromMs, toMs) = SpeechOnsetDetector.readRangeMs(captionStartMs, Long.MAX_VALUE)
            val pcm = AudioExtract.decodeTo16kMonoFile(
                this@PlaybackService,
                Uri.parse(stream.content),
                null,
                maxDurationMs = toMs + 200L,
                checkActive = { self?.ensureActive() },
            )
            try {
                val rate = pcm.sampleRate
                val startSample = fromMs * rate / 1000L
                if (startSample >= pcm.sampleCount) return@async -1L
                val wanted = (toMs - fromMs) * rate / 1000L
                val count = minOf(pcm.sampleCount - startSample, wanted).toInt()
                val samples = pcm.readFloatSamples(startSample, count, checkActive = { self?.ensureActive() })
                SpeechOnsetDetector.findOnsetMs(samples, rate, fromMs, captionStartMs) ?: -1L
            } finally {
                pcm.file.delete()
            }
        }
        return try {
            // 网络读取可能卡住：用独立协程 + 超时，卡住就放弃，字幕/配音照常用原起点。
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { job.await() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            if (!job.isCompleted) job.cancel()
            scope.cancel()
        }
    }

    private fun applySavedLineOverrides(
        lines: List<SubLine>,
        key: String = videoKey,
        engineId: String = currentTtsEngineId(),
    ): List<SubLine> {
        // Older subtitle-cache rows also persisted speaker IDs. Clear that engine-specific value
        // before applying this engine's own saved timeline map, so a cache hit cannot leak tones
        // from Kokoro into System TTS (or vice versa).
        val neutralLines = lines.map { line ->
            if (line.speakerId == -1) line else line.copy(speakerId = -1)
        }
        return SpeakerAssignmentResolver.apply(neutralLines, loadSpeakerAssignments(key, engineId))
    }

    fun toggleDub() {
        val nowOn = !ui.value.dubbingOn
        val sessionKey = ui.value.key
        val position = if (::pm.isInitialized) pm.position() else -1L

        // Gate the actual mixer first. Turning the switch off must silence already queued PCM,
        // not just prevent the next timeline tick from choosing a subtitle.
        if (::pm.isInitialized) pm.dubbingBus.setDubbingEnabled(nowOn)
        if (!nowOn) {
            // Publish the off state before cancellation so stale ticks fail their live-owner check.
            ui.update { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        dubbingOn = false,
                        activeDubbingLine = -1,
                        dubbingStatus = "仅播放原声",
                    )
                } else state
            }
            // Stop the active sentence before doing queue bookkeeping.
            safelyControlDubbing("switch to original playback") { dubbing?.stop() }
        }

        // Mode changes invalidate any speech queued for the previous mode, including end-of-video
        // draining. The next dub tick will select a cue from the current video timestamp.
        cancelDubbingDrainAfterVideoEnd()
        clearDubbingCatchUpQueue(sessionKey = sessionKey, positionMs = position)
        if (!nowOn) {
            if (::pm.isInitialized) safelySetOriginalVolume(1f, "source audio restore requested")
            val currentDubbing = dubbing
            if (position >= 0L) currentDubbing?.prepareForBackgroundPlayback(currentDubbing.lineAt(position))
        } else {
            ui.update { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        dubbingOn = true,
                        activeDubbingLine = -1,
                        dubbingStatus = "正在等待中文配音启动",
                    )
                } else state
            }
            // Source audio and Chinese dubbing now use independent volume gains; users may hear both.
            if (::pm.isInitialized) safelySetOriginalVolume(1f, "source audio restore requested")
            lastLineIdx = Int.MIN_VALUE
            if (::pm.isInitialized) {
                tickFromPlayerCallback(pm.position(), "dubbing enabled")
            }
        }
    }

    /** Ensure source audio is available without disabling the Chinese dubbing timeline. */
    fun selectOriginalPlayback() {
        if (ui.value.originalVolume <= 0f) {
            toggleOriginalVolume()
        } else if (::pm.isInitialized) {
            safelySetOriginalVolume(1f, "source audio restore requested")
        }
    }

    private fun persistOriginalVolume(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        val editor = getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat(PREF_PLAYER_ORIGINAL_VOLUME, normalized)
        if (normalized > 0f) editor.putFloat(PREF_PLAYER_ORIGINAL_VOLUME_LAST, normalized)
        editor.apply()
    }

    private fun persistDubbingVolume(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        val editor = getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putFloat(PREF_PLAYER_DUBBING_VOLUME, normalized)
        if (normalized > 0f) editor.putFloat(PREF_PLAYER_DUBBING_VOLUME_LAST, normalized)
        editor.apply()
    }

    /** Slider updates stay live while dragging; persist once when the gesture ends. */
    fun setOriginalVolume(volume: Float, persist: Boolean = true) {
        val normalized = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (persist) persistOriginalVolume(normalized)
        ui.update { state ->
            state.copy(
                originalVolume = normalized,
                muteOriginal = normalized <= 0f,
            )
        }
        if (::pm.isInitialized) safelySetOriginalVolume(1f, "original volume slider")
    }

    fun finishOriginalVolumeAdjustment() {
        persistOriginalVolume(ui.value.originalVolume)
    }

    /** Slider updates stay live while dragging; persist once when the gesture ends. */
    fun setDubbingVolume(volume: Float, persist: Boolean = true) {
        val previousVolume = ui.value.dubbingVolume
        val normalized = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (persist) persistDubbingVolume(normalized)
        ui.update { state -> state.copy(dubbingVolume = normalized) }
        if (::pm.isInitialized) pm.dubbingVolume = normalized
        val engine = dubbing
        engine?.updateVolume(normalized)

        // Android's immediate System TTS receives volume per utterance, so changing its gain to
        // zero cannot mute a sentence already speaking. Stop only that live utterance at 0%;
        // the background synthesis request remains enabled and can prepare later cues.
        if (
            normalized <= 0f &&
            previousVolume > 0f &&
            engine?.usesImmediateTimelinePlayback == true &&
            engine.currentLineIdx() >= 0
        ) {
            safelyControlDubbing("中文配音音量归零，停止当前即时 TTS") { engine.stop() }
            ui.update { state -> state.copy(activeDubbingLine = -1) }
            val position = if (::pm.isInitialized) pm.position() else -1L
            if (position >= 0L) engine.prepareForBackgroundPlayback(engine.lineAt(position))
        }

        // At zero volume the synthesis timeline remains available in the background. When sound
        // is restored, resume selection at the current video position without forcing original off.
        if (normalized > 0f && !ui.value.dubbingOn) {
            toggleDub()
        } else if (
            normalized > 0f &&
            previousVolume <= 0f &&
            ui.value.dubbingOn &&
            ::pm.isInitialized
        ) {
            tickFromPlayerCallback(pm.position(), "dubbing volume restored")
        }
    }

    fun finishDubbingVolumeAdjustment() {
        persistDubbingVolume(ui.value.dubbingVolume)
    }

    fun toggleOriginalVolume() {
        val current = ui.value.originalVolume
        val target = if (current > 0f) {
            0f
        } else {
            getSharedPreferences("settings", MODE_PRIVATE)
                .getFloat(PREF_PLAYER_ORIGINAL_VOLUME_LAST, 1f)
                .coerceIn(0.01f, 1f)
        }
        setOriginalVolume(target)
    }

    fun toggleDubbingVolume() {
        val current = ui.value.dubbingVolume
        val target = if (current > 0f) {
            0f
        } else {
            getSharedPreferences("settings", MODE_PRIVATE)
                .getFloat(PREF_PLAYER_DUBBING_VOLUME_LAST, 1f)
                .coerceIn(0.01f, 1f)
        }
        setDubbingVolume(target)
    }

    /** Legacy call path now toggles the source gain itself, not a mutually-exclusive mode. */
    fun toggleMute() = toggleOriginalVolume()

    fun toggleAudioOnly() {
        val nowOnly = !ui.value.audioOnly
        ui.update { it.copy(audioOnly = nowOnly) }
        // 关画面：禁视频轨（省电）；重开恢复
        pm.player.trackSelectionParameters = pm.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, nowOnly)
            .build()
    }
    private fun cancelPendingSeekResume() {
        synchronized(seekResumeLock) {
            seekResumeGeneration += 1L
            pendingSeekResume?.let(seekResumeHandler::removeCallbacks)
            pendingSeekResume = null
        }
    }

    /**
     * Reassert play intent once after Media3 has processed the seek discontinuity.
     * Some web/segmented streams briefly leave isPlaying=false after seek even when play()
     * was requested. An explicit play/pause tap invalidates this one-shot retry.
     */
    private fun scheduleSeekResume(sessionKey: String) {
        val token = synchronized(seekResumeLock) {
            seekResumeGeneration += 1L
            pendingSeekResume?.let(seekResumeHandler::removeCallbacks)
            pendingSeekResume = null
            seekResumeGeneration
        }
        val retry = Runnable {
            val stillCurrent = synchronized(seekResumeLock) { token == seekResumeGeneration }
            if (!stillCurrent) return@Runnable
            if (
                !playerReady ||
                !::pm.isInitialized ||
                instance !== this@PlaybackService ||
                videoKey != sessionKey ||
                ui.value.key != sessionKey
            ) {
                synchronized(seekResumeLock) {
                    if (token == seekResumeGeneration) pendingSeekResume = null
                }
                return@Runnable
            }
            try {
                // Do not use isPlaying to decide whether the user wants playback: it is false
                // while buffering. This is one final play() nudge after the seek transition.
                if (!pm.player.playWhenReady || !pm.player.isPlaying) pm.play()
            } catch (error: Exception) {
                io.translatetube.local.util.AppLogStore.warning(
                    this@PlaybackService,
                    "PlaybackSeek",
                    "跳转后恢复播放失败",
                    error,
                    diagnosticContext = mapOf(
                        "视频会话指纹" to safeDiagnosticSessionFingerprint(sessionKey),
                        "延迟恢复代次" to token.toString(),
                        "当前恢复代次" to synchronized(seekResumeLock) { seekResumeGeneration }.toString(),
                        "播放器初始化状态" to playerReady.toString(),
                        "播放意图 playWhenReady" to runCatching { pm.player.playWhenReady.toString() }.getOrDefault("读取失败"),
                        "播放器当前是否实际播放" to runCatching { pm.player.isPlaying.toString() }.getOrDefault("读取失败"),
                        "播放器状态码" to runCatching { pm.player.playbackState.toString() }.getOrDefault("读取失败"),
                        "跳转目标位置 ms" to runCatching { pm.position().toString() }.getOrDefault("读取失败"),
                    ),
                )
                android.util.Log.w("PlaybackService", "Playback resume after seek did not complete", error)
            } finally {
                synchronized(seekResumeLock) {
                    if (token == seekResumeGeneration) pendingSeekResume = null
                }
            }
        }
        synchronized(seekResumeLock) {
            if (token == seekResumeGeneration) {
                pendingSeekResume = retry
                seekResumeHandler.postDelayed(retry, 180L)
            }
        }
    }

    private fun seekAndResumeInternal(ms: Long, reason: String, failureMessage: String) {
        if (!playerReady || !::pm.isInitialized || ui.value.key != videoKey) {
            android.util.Log.w("PlaybackService", "Ignoring $reason for an inactive playback service")
            return
        }
        val sessionKey = videoKey
        cancelPendingSeekResume()
        try {
            // Stop speech associated with the old timestamp before applying the seek.
            safelyControlDubbing(reason) { dubbing?.stop() }
            lastLineIdx = Int.MIN_VALUE
            pm.seekTo(ms.coerceAtLeast(0L))
            if (
                playerReady &&
                ::pm.isInitialized &&
                instance === this &&
                videoKey == sessionKey &&
                ui.value.key == sessionKey
            ) {
                // Seeking from either the progress bar or a subtitle row should resume playback
                // automatically in both original-audio and dubbing modes.
                pm.play()
                scheduleSeekResume(sessionKey)
            }
        } catch (error: Exception) {
            io.translatetube.local.util.AppLogStore.error(
                this@PlaybackService,
                "PlaybackSeek",
                "视频跳转操作失败：$reason",
                error,
                diagnosticContext = mapOf(
                    "触发原因" to reason,
                    "视频会话指纹" to safeDiagnosticSessionFingerprint(sessionKey),
                    "请求跳转位置 ms" to ms.coerceAtLeast(0L).toString(),
                    "播放器初始化状态" to playerReady.toString(),
                    "播放器阶段" to ui.value.phase.name,
                    "当前视频位置 ms" to runCatching { if (::pm.isInitialized) pm.position().toString() else "未初始化" }.getOrDefault("读取失败"),
                    "当前字幕索引" to ui.value.currentLine.toString(),
                    "当前配音字幕索引" to ui.value.activeDubbingLine.toString(),
                    "恢复播放是否已排队" to synchronized(seekResumeLock) { pendingSeekResume != null }.toString(),
                ),
            )
            android.util.Log.e("PlaybackService", "$reason failed", error)
            updateUiForSession(sessionKey) { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        activeDubbingLine = -1,
                        dubbingStatus = failureMessage,
                    )
                } else state
            }
        }
    }

    /** Progress-bar seeking always resumes playback at the selected timestamp. */
    fun seek(ms: Long) {
        val liveService = instance
        if (liveService !== this) {
            liveService?.takeIf { it.isReady }?.seek(ms)
            return
        }
        seekAndResumeInternal(
            ms = ms,
            reason = "progress seek",
            failureMessage = "进度跳转时正在恢复播放，请稍后再试",
        )
    }

    /** Jump to a subtitle and start playback, even when the video was paused before the tap. */
    fun seekAndPlay(ms: Long) {
        val liveService = instance
        if (liveService !== this) {
            liveService?.takeIf { it.isReady }?.seekAndPlay(ms)
            return
        }
        seekAndResumeInternal(
            ms = ms,
            reason = "subtitle seek",
            failureMessage = "跳转到字幕时间点失败",
        )
    }

    /** Explicit play/pause actions cancel a delayed seek retry so it cannot override the user. */
    fun playPlayback() {
        val liveService = instance
        if (liveService !== this) {
            liveService?.takeIf { it.isReady }?.playPlayback()
            return
        }
        if (!playerReady || !::pm.isInitialized) return
        cancelPendingSeekResume()

        val player = pm.player
        if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
            // The floating-window and in-app controls share this service/player. Restart centrally
            // so either surface resumes the same video, timeline, subtitles and dubbed audio from 0.
            // Clear any post-video dubbing drain before the first cue is scheduled again.
            val sessionKey = videoKey
            cancelDubbingDrainAfterVideoEnd()
            clearDubbingCatchUpQueue(sessionKey = sessionKey, positionMs = 0L)
            seekAndResumeInternal(
                ms = 0L,
                reason = "replay after video ended",
                failureMessage = "视频结束后重新播放失败",
            )
            return
        }
        pm.play()
    }

    fun pausePlayback() {
        val liveService = instance
        if (liveService !== this) {
            liveService?.takeIf { it.isReady }?.pausePlayback()
            return
        }
        if (!playerReady || !::pm.isInitialized) return
        cancelPendingSeekResume()
        pm.pause()
    }

    fun playPause() {
        val liveService = instance
        if (liveService != null && liveService !== this) {
            liveService.takeIf { it.isReady }?.playPause()
            return
        }
        if (!playerReady || !::pm.isInitialized) return
        // When ExoPlayer is buffering, isPlaying can be false although playWhenReady is true.
        if (pm.player.playWhenReady) pausePlayback() else playPlayback()
    }

    /** 记录观看进度（退出播放页时调用，历史列表续看用） */
    fun saveProgress() {
        val key = videoKey
        if (key.isEmpty()) return
        scope.launch {
            try {
                AppDatabase.get(this@PlaybackService).videoDao().setWatched(key, pm.position() / 1000)
            } catch (error: Exception) {
                io.translatetube.local.util.AppLogStore.warning(
                    this@PlaybackService,
                    "PlaybackProgress",
                    "保存观看进度失败",
                    error,
                    diagnosticContext = mapOf(
                        "视频会话指纹" to safeDiagnosticSessionFingerprint(key),
                        "视频会话标识长度" to key.length.toString(),
                        "播放器初始化状态" to ::pm.isInitialized.toString(),
                        "保存位置秒" to runCatching { if (::pm.isInitialized) (pm.position() / 1000).toString() else "不可用" }.getOrDefault("读取失败"),
                    ),
                )
            }
        }
    }
    fun setSpeed(s: Float) { pm.speed(s) }
    fun position(): Long = pm.position()
    fun duration(): Long = pm.duration()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val phase = ui.value.phase
        val isProcessing = phase in setOf(
            Phase.PARSING,
            Phase.DOWNLOADING_AUDIO,
            Phase.TRANSCRIBING,
            Phase.TRANSLATING,
            Phase.SYNTHESIZING,
        )
        val isDubbingPreparing = isDubbingPreparationIncomplete(ui.value)
        if (isProcessing || isDubbingPreparing) {
            // READY starts video playback after the first speech cues are warmed, while the rest
            // of the subtitles may still be synthesizing. Do not let recents removal kill that work.
            android.util.Log.i(
                "PlaybackService",
                "Keeping background task alive after task removal: phase=$phase, dubbingPreparing=$isDubbingPreparing",
            )
            return
        }
        super.onTaskRemoved(rootIntent)
    }
}

/** 通知渠道小工具（避免直接 import 冲突） */
object NotificationManagerCompat_c {
    fun create(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        val ch = NotificationChannel(PlaybackService.CH_MAIN, "播放与配音", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        return nm
    }
}

private typealias NotificationManager = android.app.NotificationManager
