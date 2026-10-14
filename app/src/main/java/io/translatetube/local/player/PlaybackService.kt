package io.translatetube.local.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.translatetube.local.R
import io.translatetube.local.asr.AudioExtract
import io.translatetube.local.asr.SpeakerDiarizer
import io.translatetube.local.asr.WhisperEngine
import io.translatetube.local.asr.vadSegments
import io.translatetube.local.asr.vadSegmentsFromFile
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.SourceType
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.subs.SubLine
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.translate.Crypto
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.LangMap
import io.translatetube.local.translate.MlKitEngine
import io.translatetube.local.translate.GoogleHybridEngine
import io.translatetube.local.translate.TranslationEngine
import io.translatetube.local.tts.KokoroTts
import io.translatetube.local.tts.TtsEngine
import io.translatetube.local.yt.NewPipeRepo
import io.translatetube.local.yt.SubtitleTrackOption
import io.translatetube.local.yt.YouTubeAudioCandidate
import io.translatetube.local.yt.YouTubeStreamSelector
import io.translatetube.local.yt.YouTubeVideoCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

enum class Phase { IDLE, PARSING, DOWNLOADING_AUDIO, TRANSCRIBING, TRANSLATING, SYNTHESIZING, READY, ERROR }
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
    val currentLine: Int = -1,
    val preparedUntil: Int = 0,
    val preparedCount: Int = 0,
    val totalLines: Int = 0,
    val dubbingOn: Boolean = true,
    val muteOriginal: Boolean = true,
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
    val noModelHint: String? = null,     // 方案 B：模型未下载引导提示
    val qualityOptions: List<String> = emptyList(),
    val selectedQuality: String = "",
    val availableSubtitleTracks: List<SubtitleTrackOption> = emptyList(),
    val subtitleTrackStatus: String = "",
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

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var pm: PlayerManager
    @Volatile private var playerReady = false
    private var session: MediaSession? = null
    @Volatile private var dubbing: DubbingEngine? = null
    private val retiringDubbingEngines = java.util.concurrent.CopyOnWriteArrayList<DubbingEngine>()
    private var whisper: WhisperEngine? = null
    // sherpa-onnx OfflineRecognizer is native state and must never decode two videos concurrently.
    private val whisperMutex = kotlinx.coroutines.sync.Mutex()

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
    private var videoKey = ""
    private var videoId: String? = null
    private var localUri: Uri? = null
    private var remoteMediaUrl: String? = null
    private var remoteMediaTitle: String = ""
    private var remoteMediaReferrer: String = ""
    private var remoteMediaCookie: String? = null
    @Volatile private var qualitySources: List<YouTubeQualitySource> = emptyList()
    @Volatile private var qualityAudioUrl: String = ""
    @Volatile private var qualityRecoveryAttempted = false
    private var qualityRecoveryJob: Job? = null
    private var qualityJob: Job? = null
    private var importedLines: List<SubLine>? = null
    private var lastLineIdx = Int.MIN_VALUE
    private var dubCollectJob: Job? = null
    private var speakerJob: Job? = null
    private val asrAudioDownloadLocks = KeyedMutex<String>()
    // Do not evict downloaded audio while MediaExtractor is actively reading it.
    private val activeAsrAudioPaths = ActiveFileLeaseRegistry()

    companion object {
        @Volatile var instance: PlaybackService? = null
            private set
        val ui = MutableStateFlow(PlayerUiState())
        fun state(): StateFlow<PlayerUiState> = ui
        /** 播放画面 TextureView（供悬浮窗摘取 reparent） */
        @Volatile var videoSurfaceView: android.view.TextureView? = null
        const val CH_MAIN = "playback"
    }

    /** 服务初始化状态，供播放页在初始化失败后安全重试。 */
    val isReady: Boolean get() = playerReady && ::pm.isInitialized

    private suspend fun ensureCurrentSession(sessionKey: String) {
        // Extractor/JNI calls are sometimes synchronous. A canceled job may return from one
        // without suspending, so explicitly check before publishing results or opening engines.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        PlaybackSessionGuard.ensureCurrent(
            sessionKey = sessionKey,
            activeVideoKey = videoKey,
            activeUiKey = ui.value.key,
        )
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
            startForeground(1001, boot)

            pm = PlayerManager(this)
            pm.onTick = { ms -> tick(ms) }
            pm.onEnded = { ui.update { it.copy(isPlaying = false, currentLine = -1) } }
            pm.onVideoAspect = { ratio -> ui.update { it.copy(videoAspect = ratio) } }

            // Keep dubbed AudioTrack locked to ExoPlayer's playback state. This callback
            // runs with the player's state change, so voice stops immediately on pause and
            // resumes from the paused sample position instead of finishing the current line.
            pm.player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && ui.value.dubbingOn) {
                        // Re-select the cue for the exact resumed video timestamp. Do not resume
                        // a stale AudioTrack if a seek or a disabled dubbing state changed meanwhile.
                        dubbing?.stop()
                        lastLineIdx = Int.MIN_VALUE
                        if (::pm.isInitialized) tick(pm.position())
                    } else {
                        dubbing?.pausePlayback()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: androidx.media3.common.Player.PositionInfo,
                    newPosition: androidx.media3.common.Player.PositionInfo,
                    reason: Int,
                ) {
                    // A seek can land inside the same subtitle cue, so comparing line indexes
                    // alone is not enough. Invalidate any queued/playing PCM and re-evaluate at
                    // Media3's new playhead immediately; this also covers seeks from media controls.
                    dubbing?.stop()
                    lastLineIdx = Int.MIN_VALUE
                    if (::pm.isInitialized) tick(newPosition.positionMs)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        ui.update { state ->
                            if (state.progress.startsWith("正在尝试兼容清晰度 ")) {
                                state.copy(phase = Phase.READY, progress = "", error = null)
                            } else state
                        }
                    }
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
                        // An error after the one recovery attempt is a real failure; stop retrying.
                        activeRecovery.cancel()
                        ui.update { state ->
                            if (videoId != null && state.key == ui.value.key) {
                                state.copy(
                                    phase = Phase.ERROR,
                                    progress = "",
                                    error = "重新获取 YouTube 视频流后仍无法播放：$err",
                                )
                            } else state
                        }
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


    private fun updateProcessingNotification(state: PlayerUiState) {
        if (!playerReady && state.phase == Phase.IDLE) return
        val (title, detail) = when (state.phase) {
            Phase.PARSING -> "正在分析视频" to state.progress.ifBlank { "正在获取视频信息与字幕" }
            Phase.DOWNLOADING_AUDIO -> "正在准备音轨" to state.progress.ifBlank { "正在检查并读取视频音频" }
            Phase.TRANSCRIBING -> "正在识别语音" to state.progress.ifBlank { "正在生成字幕时间轴" }
            Phase.TRANSLATING -> "正在翻译字幕" to state.progress.ifBlank {
                if (state.totalLines > 0) "已翻译 ${state.lines.count { SubtitleParsers.cleanTranslatedText(it.zh) != null }}/${state.totalLines} 行" else "正在逐句翻译"
            }
            Phase.SYNTHESIZING -> "正在生成配音" to if (state.totalLines > 0) {
                "已准备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音"
            } else state.progress.ifBlank { "正在准备语音" }
            Phase.READY -> when {
                dubbing?.isPreparing == true -> (if (state.isPlaying) "播放中 · 配音准备" else "正在准备配音") to if (state.totalLines > 0) {
                    "已准备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音"
                } else state.progress.ifBlank { "正在生成中文配音" }
                state.isPlaying -> "正在播放" to state.title.ifBlank { "视频正在播放" }
                else -> "配音已准备完成" to state.title.ifBlank { "返回应用即可查看字幕与配音" }
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
                    (state.lines.count { SubtitleParsers.cleanTranslatedText(it.zh) != null } * 100f / state.totalLines).toInt().coerceIn(0, 100)
                } else null
            Phase.SYNTHESIZING -> if (state.totalLines > 0) {
                (state.preparedCount.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
            } else ratioPercentFromProgress(state.progress)
            Phase.READY -> if (dubbing?.isPreparing == true && state.totalLines > 0) {
                (state.preparedCount.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
            } else null
            else -> null
        }
        val displayDetail = if (
            measuredPercent != null &&
            !Regex("\\d+(?:\\.\\d+)?%").containsMatchIn(detail)
        ) "$detail · $measuredPercent%" else detail

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(this, CH_MAIN)
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle(title)
            .setContentText(displayDetail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayDetail))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(
                state.isPlaying ||
                    dubbing?.isPreparing == true ||
                    state.phase !in setOf(Phase.READY, Phase.ERROR, Phase.IDLE)
            )
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, io.translatetube.local.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .apply {
                // Always replace or clear the previous stage's progress state.
                // Only measurable work receives a percentage; other phases show no stale bar.
                if (measuredPercent != null) {
                    setProgress(100, measuredPercent.coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
        manager.notify(1001, notification)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Prevent a canceled pipeline from publishing progress/results into a newer video session. */
    private fun updateUiForSession(
        sessionKey: String,
        transform: (PlayerUiState) -> PlayerUiState,
    ) {
        ui.update { current -> if (current.key == sessionKey) transform(current) else current }
    }

    override fun onDestroy() {
        // Cancel work before releasing services it may still be using. In particular, the
        // Whisper JNI recognizer must not be released while a synchronous decode call is active.
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
            activePipeline.invokeOnCompletion { releaseWhisperOffMainThread() }
        } else {
            releaseWhisperOffMainThread()
        }
        runCatching { session?.release() }
        if (::pm.isInitialized) runCatching { pm.release() }
        playerReady = false
        if (instance === this) instance = null
        super.onDestroy()
    }


    private fun shouldReleaseWhisperForMemoryPressure(): Boolean {
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

    private fun releaseWhisperOffMainThread() {
        Thread(
            { releaseWhisper() },
            "tt-whisper-release",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseWhisper() {
        val engine = whisper
        whisper = null
        runCatching { engine?.close() }
            .onFailure { android.util.Log.w("PlaybackService", "Whisper recognizer cleanup failed", it) }
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
    ) {
        if (!playerReady || !::pm.isInitialized) {
            ui.update {
                it.copy(phase = Phase.ERROR, progress = "", error = it.error ?: "播放器服务未初始化成功，请返回主页后重试")
            }
            return
        }
        prepJob?.cancel()
        speakerJob?.cancel(); speakerJob = null
        dubCollectJob?.cancel(); dubCollectJob = null
        qualityRecoveryJob?.cancel(); qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        retireCurrentDubbing()
        this.videoKey = key; this.videoId = videoId; this.localUri = localUri
        this.remoteMediaUrl = remoteMediaUrl
        this.remoteMediaTitle = remoteMediaTitle
        this.remoteMediaReferrer = remoteMediaReferrer
        this.remoteMediaCookie = remoteMediaCookie
        val importedLinesSnapshot = importedLines?.toList()
        this.importedLines = importedLinesSnapshot
        // Preserve parsed metadata/subtitles when resuming a translation failure.
        if (videoId != null && resumeState == null) NewPipeRepo.invalidate(videoId)
        qualityJob?.cancel()
        qualitySources = emptyList()
        qualityAudioUrl = ""
        lastLineIdx = Int.MIN_VALUE
        val configuredTts = getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "kokoro") ?: "kokoro"
        val activeTranslationId = getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)
        val effectiveTranslationCacheKey = resumeState?.translationCacheKey?.takeIf { it.isNotBlank() }
            ?: if (!importedLines.isNullOrEmpty()) {
                importedTranslationCacheKey(key, activeTranslationId, importedLines)
            } else {
                "$key|zh|$activeTranslationId"
            }
        val configuredTtsName = when (configuredTts) {
            "system" -> "系统 TTS"
            "kokoro" -> "Kokoro"
            else -> "未知配音引擎"
        }
        ui.value = PlayerUiState(
            phase = if (resumeState != null) Phase.TRANSLATING else Phase.PARSING,
            key = key,
            progress = if (resumeState != null) "恢复已获取的字幕，继续未完成的翻译…" else "解析中…",
            title = resumeState?.title.orEmpty(),
            channel = resumeState?.channel.orEmpty(),
            thumbUrl = resumeState?.thumbUrl,
            durationMs = resumeState?.durationMs ?: 0L,
            lines = resumeState?.lines ?: emptyList(),
            totalLines = resumeState?.totalLines ?: 0,
            sourceLang = resumeState?.sourceLang.orEmpty(),
            engineName = resumeState?.engineName.orEmpty(),
            translationCacheKey = effectiveTranslationCacheKey,
            ttsEngineName = configuredTtsName,
        )
        pm.pause()
        prepJob = scope.launch {
            runPipeline(key, videoId, localUri, resumeState, importedLinesSnapshot, remoteMediaUrl, remoteMediaTitle, remoteMediaReferrer, remoteMediaCookie)
        }
    }

    /** 切换 YouTube 清晰度：保留当前播放位置与播放/暂停状态。 */
    fun selectQuality(label: String) {
        val sessionKey = ui.value.key
        val sessionVideoId = videoId ?: return
        val source = qualitySources.firstOrNull { it.label == label } ?: return
        if (!::pm.isInitialized || sessionKey.isBlank() || source.label == ui.value.selectedQuality) return
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
                    pm.setYouTubeQuality(
                        videoUrl = source.url,
                        audioUrl = qualityAudioUrl,
                        muxed = source.muxed,
                        title = ui.value.title,
                        artist = ui.value.channel,
                        artworkUrl = ui.value.thumbUrl,
                    )
                    pm.seekTo(positionMs)
                    if (wasPlaying) pm.play() else pm.pause()
                    updateUiForSession(sessionKey) { it.copy(selectedQuality = source.label, progress = "") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Unable to switch YouTube quality", e)
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
        scope.launch {
            ensureCurrentSession(sessionKey)
            updateUiForSession(sessionKey) { it.copy(subtitleTrackStatus = "正在下载 " + track.languageTag + " 字幕…") }
            try {
                if (sessionVideoId != videoId) throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕下载")
                val content = NewPipeRepo.downloadSubtitleContent(track)
                val cueCount = SubtitleParsers.parseAuto(content).size
                if (cueCount == 0) throw IllegalStateException("字幕文件没有可用的时间轴")
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(outputUri, "wt")
                        ?: throw IllegalStateException("无法写入所选文件")
                    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
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
                android.util.Log.w("PlaybackService", "Subtitle download failed", e)
                updateUiForSession(sessionKey) {
                    it.copy(subtitleTrackStatus = "字幕下载失败：" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * 导入已经翻译好的字幕：保留上传文件的时间轴，把译文作为配音文本，不再调用翻译引擎。
     * 支持 SRT/VTT/ASS；双语 cue 会优先取含中文的行作为译文。
     */
    fun importTranslatedSubtitles(uri: Uri) {
        val sessionKey = videoKey
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
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取字幕文件")
                }
                val parsed = SubtitleParsers.parseAuto(text).filter { it.startMs >= 0L && it.endMs > it.startMs }
                ensureCurrentSession(sessionKey)
                if (parsed.isEmpty()) throw IllegalStateException("字幕文件为空、时间轴无效或格式不支持")
                val chinesePattern = Regex("[\\u4e00-\\u9fff]")
                val translatedLines = applySavedLineOverrides(parsed.mapIndexed { index, line ->
                    val parts = line.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    val translation = parts.lastOrNull { chinesePattern.containsMatchIn(it) }
                        ?: parts.lastOrNull()
                        ?: line.text.trim()
                    val original = parts.filter { it != translation }.joinToString("\n").ifBlank { translation }
                    line.copy(
                        index = index,
                        text = original,
                        zh = SubtitleParsers.cleanTranslatedText(translation) ?: translation,
                    )
                })
                ensureCurrentSession(sessionKey)
                pm.originalVolume = if (ui.value.muteOriginal) 0f else 1f
                updateUiForSession(sessionKey) {
                    it.copy(
                        phase = Phase.SYNTHESIZING,
                        progress = "已载入 " + translatedLines.size + " 条已翻译字幕，正在准备中文配音…",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        preparedUntil = 0,
                        preparedCount = 0,
                        sourceLang = "zh",
                        error = null,
                        noModelHint = null,
                        subtitleTrackStatus = "已导入已翻译字幕；此流程会跳过自动翻译。",
                    )
                }
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey)
                val tts = makeTts()
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope)
                } catch (e: Exception) {
                    runCatching { tts.close() }
                    throw e
                }
                registerDubbingFailureHandler(d, tts, sessionKey)
                d.attach(tts, translatedLines)
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                dubbing = d
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === d) dubbing = null
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕导入任务")
                }
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForSession(sessionKey) {
                                it.copy(preparedUntil = prefix, preparedCount = readyCount)
                            }
                        }
                }
                lastLineIdx = Int.MIN_VALUE
                ensureCurrentSession(sessionKey)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(sessionKey)
                    pm.play()
                }
                updateUiForSession(sessionKey) {
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
                android.util.Log.e("PlaybackService", "Import translated subtitles failed", e)
                if (videoKey == sessionKey && ui.value.key == sessionKey) {
                    runCatching { pm.originalVolume = 1f }
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "已翻译字幕导入失败：" + (e.message ?: e.javaClass.simpleName),
                        muteOriginal = false,
                    )
                }
            }
        }
    }

    /** 导入外部字幕（SRT/VTT）：解析后重新走翻译+配音 */
    fun importSubtitles(uri: Uri) {
        val sessionKey = videoKey
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
        pm.originalVolume = if (ui.value.muteOriginal) 0f else 1f
        lastLineIdx = Int.MIN_VALUE

        prepJob = scope.launch {
            try {
                updateUiForSession(sessionKey) {
                    it.copy(
                        phase = Phase.PARSING,
                        progress = "正在读取字幕文件…",
                        error = null,
                        noModelHint = null,
                    )
                }
                // Subtitle files can be large. Keep file I/O and parsing off the main thread so
                // importing a long SRT/VTT/ASS file cannot freeze player controls.
                val parsedLines = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取字幕文件")
                    SubtitleParsers.parseAuto(text)
                        .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
                        .mapIndexed { index, line -> line.copy(index = index) }
                }
                ensureCurrentSession(sessionKey)
                if (parsedLines.isEmpty()) {
                    throw IllegalStateException("字幕文件为空或格式不支持")
                }

                updateUiForSession(sessionKey) {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "翻译导入的字幕（${parsedLines.size} 行）…",
                    )
                }
                val srcLang = detectLangFromText(parsedLines.take(12).joinToString(" ") { it.text })
                val activeTranslationId = getSharedPreferences("settings", MODE_PRIVATE)
                    .getLong("active_api_id", -1L)
                val importedCacheKey = importedTranslationCacheKey(sessionKey, activeTranslationId, parsedLines)
                ensureCurrentSession(sessionKey)
                // Preserve the imported source identity if this translation is later retried.
                updateUiForSession(sessionKey) { state ->
                    state.copy(
                        translationCacheKey = importedCacheKey,
                        lines = parsedLines,
                        totalLines = parsedLines.size,
                        sourceLang = srcLang,
                    )
                }
                // Use immutable copies so StateFlow/Compose sees translated subtitle text.
                val translatedLines = applySavedLineOverrides(
                    if (isChineseLanguage(srcLang)) {
                        parsedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                    } else {
                        translateLines(sessionKey, parsedLines, srcLang, importedCacheKey)
                    }
                )
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey)
                val tts = makeTts()
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope)
                } catch (e: Exception) {
                    runCatching { tts.close() }
                    throw e
                }
                registerDubbingFailureHandler(d, tts, sessionKey)
                d.attach(tts, translatedLines)
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                dubbing = d
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === d) dubbing = null
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧字幕导入任务")
                }
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForSession(sessionKey) {
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
                ensureCurrentSession(sessionKey)
                if (translatedLines.count { SubtitleParsers.cleanTranslatedText(it.zh) != null } >
                    translatedLines.size / 2
                ) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            importedCacheKey,
                            srcLang,
                            arr.toString(),
                            System.currentTimeMillis(),
                        )
                    )
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        phase = Phase.READY,
                        progress = "",
                        error = null,
                    )
                }
                ensureCurrentSession(sessionKey)
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Subtitle import failed", e)
                updateUiForSession(sessionKey) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "字幕导入失败: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** Imported subtitles get their own translation cache key so they cannot overwrite YouTube source captions. */
    private fun importedTranslationCacheKey(key: String, activeId: Long, lines: List<SubLine>): String =
        TranslationCacheIdentity.importedKey(key, activeId, lines)

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

    /** 从首行文本猜语言（仅含 CJK 判 zh，否则 en） */
    private fun detectLangFromText(t: String): String =
        io.translatetube.local.subs.SubtitleLanguageDetector.detect(t)

    /** Chinese subtitle tracks must not be translated into Chinese again. */
    private fun isChineseLanguage(tag: String): Boolean {
        val normalized = tag.trim().lowercase()
        return normalized.startsWith("zh") || normalized == "cmn" || normalized.startsWith("yue")
    }

    /** Reject corrupt translation caches containing progress/status text rather than translated rows. */
    private fun isUsableTranslationCache(linesJson: String): Boolean = try {
        val rows = org.json.JSONArray(linesJson)
        if (rows.length() == 0) {
            false
        } else {
            var validTranslations = 0
            var hasProgressPlaceholder = false
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val translation = row.optString("zh")
                if (SubtitleParsers.isPlaceholderTranslation(translation)) {
                    hasProgressPlaceholder = true
                }
                if (!SubtitleParsers.cleanTranslatedText(translation).isNullOrBlank()) {
                    validTranslations++
                }
            }
            // Partial caches are intentional checkpoints. Retry should only translate missing rows.
            !hasProgressPlaceholder && validTranslations > 0
        }
    } catch (e: Exception) {
        android.util.Log.w("PlaybackService", "Invalid subtitle translation cache JSON", e)
        false
    }

    private suspend fun runPipeline(
        key: String,
        videoId: String?,
        localUri: Uri?,
        resumeState: PlayerUiState? = null,
        sourceImportedLines: List<SubLine>? = null,
        remoteMediaUrl: String? = null,
        remoteMediaTitle: String = "",
        remoteMediaReferrer: String = "",
        remoteMediaCookie: String? = null,
    ) {
        // 记录仅供转写使用的临时网络音频，任何异常/取消路径都清理。
        var transientAudio: File? = null
        var transientPcmFile: AudioExtract.PcmFile? = null
        try {
            ensureCurrentSession(key)
            awaitRetiredDubbingEngines()
            ensureCurrentSession(key)
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
            if (resumeState != null) {
                // Video metadata and original subtitle rows already exist in memory; do not resolve
                // the YouTube page or fetch subtitles again for a translation-only retry.
                updateUiForSession(key) {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "继续翻译尚未完成的字幕…",
                        sourceLang = srcLang,
                        error = null,
                    )
                }
            } else if (videoId != null) {
                val m = NewPipeRepo.resolve(videoId)
                ensureCurrentSession(key)
                meta = meta.copy(title = m.title, channel = m.channel, durationSec = m.durationSec, thumbUrl = m.thumbUrl)
                updateUiForSession(key) { it.copy(title = m.title, channel = m.channel, thumbUrl = m.thumbUrl, durationMs = m.durationSec * 1000) }
            } else if (remoteMediaUrl != null && resumeState == null) {
                val remoteUri = Uri.parse(remoteMediaUrl)
                val title = remoteMediaTitle.takeIf { it.isNotBlank() }
                    ?: remoteUri.lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() }
                    ?: "网页视频"
                meta = meta.copy(title = title, localUri = remoteMediaUrl)
                updateUiForSession(key) { it.copy(title = title, durationMs = 0L) }
            } else if (resumeState == null) {
                localUri?.let { u ->
                    // 本地视频：取真实标题/时长
                    var title = u.lastPathSegment ?: "本地视频"
                    var dur = 0L
                    try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(this, u)
                        title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.takeIf { it.isNotBlank() } ?: title
                        dur = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?: "0").toLongOrNull() ?: 0L
                        mmr.release()
                    } catch (_: Exception) {}
                    meta = meta.copy(title = title, durationSec = dur / 1000)
                    updateUiForSession(key) { it.copy(title = title, durationMs = dur) }
                }
            }

            // 解析视频时先列出所有字幕轨。下载是可选操作，不影响自动翻译/配音流程。
            if (videoId != null && resumeState == null) {
                try {
                    updateUiForSession(key) { it.copy(progress = "正在查找可用字幕轨…") }
                    val tracks = NewPipeRepo.subtitleTracks(videoId)
                    ensureCurrentSession(key)
                    updateUiForSession(key) {
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
                    updateUiForSession(key) {
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
            val cacheKeyBase = remoteMediaUrl?.let(::remoteCacheIdentity) ?: key
            val translationCacheKey = resumeState?.translationCacheKey?.takeIf { it.isNotBlank() }
                ?: if (!sourceImportedLines.isNullOrEmpty()) {
                    importedTranslationCacheKey(cacheKeyBase, selectedTranslationId, sourceImportedLines)
                } else {
                    "$cacheKeyBase|zh|$selectedTranslationId"
                }
            val sourceSubtitleCacheKey = when {
                videoId != null -> "$key|source|youtube"
                remoteMediaUrl != null -> "$cacheKeyBase|source|remote-asr-v1"
                else -> "$key|source|local-asr-v1"
            }
            // 首次处理明确显示字幕读取阶段；恢复翻译时保留翻译阶段，不伪装成重新解析。
            if (resumeState == null) {
                updateUiForSession(key) { it.copy(phase = Phase.PARSING, progress = "正在检查本地字幕文件与缓存…") }
            } else {
                updateUiForSession(key) { it.copy(phase = Phase.TRANSLATING, progress = "继续翻译尚未完成的字幕…") }
            }
            // 先读本地缓存，再决定是否请求 YouTube 字幕，避免重试重复下载。
            val storedTranslationCache = if (resumeState == null) db.subtitleCacheDao().get(translationCacheKey) else null
            // Older builds may have persisted a progress placeholder (for example, "正在翻译")
            // as every row's Chinese text. Never trust such cache entries: retranslate from the
            // cached original subtitles instead of displaying the placeholder as completed output.
            var cached = storedTranslationCache?.takeIf { isUsableTranslationCache(it.linesJson) }
            if (storedTranslationCache != null && cached == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring invalid/stale translation cache for $key; will translate the source subtitles again"
                )
                db.subtitleCacheDao().delete(translationCacheKey)
            }
            suspend fun fetchAndCacheSourceLines(): Pair<List<SubLine>, String>? {
                val id = videoId ?: return null
                updateUiForSession(key) { it.copy(phase = Phase.PARSING, progress = "正在下载并读取字幕文件…") }
                val fetched = NewPipeRepo.subtitles(id) ?: return null
                ensureCurrentSession(key)
                val lang = fetched.second.takeIf { it.isNotBlank() } ?: "auto"
                val fetchedLines = fetched.first
                    .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
                    .mapIndexed { index, line -> line.copy(index = index) }
                if (fetchedLines.isEmpty()) return null

                val sourceArr = org.json.JSONArray()
                fetchedLines.forEach { line ->
                    sourceArr.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                    )
                }
                db.subtitleCacheDao().put(
                    io.translatetube.local.data.SubtitleCacheEntity(
                        sourceSubtitleCacheKey, lang, sourceArr.toString(), System.currentTimeMillis()
                    )
                )
                // A valid caption track supersedes any interrupted ASR transcript for this video.
                db.subtitleCacheDao().delete("$sourceSubtitleCacheKey|asr-progress-v1")
                return fetchedLines to lang
            }

            val sourceCached = if (
                resumeState == null && (videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()
            ) {
                db.subtitleCacheDao().get(sourceSubtitleCacheKey)
            } else null
            var restoredSourceLines = sourceCached?.let { source ->
                runCatching {
                    val sourceArr = org.json.JSONArray(source.linesJson)
                    val restored = (0 until sourceArr.length()).map { i ->
                        val o = sourceArr.getJSONObject(i)
                        SubLine(i, o.getLong("s"), o.getLong("e"), o.getString("t"))
                    }
                    restored.takeIf { rows ->
                        rows.isNotEmpty() && rows.all { row ->
                            row.startMs >= 0L && row.endMs > row.startMs && row.text.isNotBlank()
                        }
                    }
                }.getOrNull()
            }
            var restoredSourceLang: String? = restoredSourceLines?.let {
                sourceCached?.srcLang?.takeIf { lang -> lang.isNotBlank() }
                    ?: detectLangFromText(it.take(12).joinToString(" ") { cue -> cue.text })
            }
            if (sourceCached != null && restoredSourceLines != null) {
                // The completed source cache is authoritative if a crash happened between its
                // write and deletion of the partial-ASR checkpoint.
                db.subtitleCacheDao().delete("$sourceSubtitleCacheKey|asr-progress-v1")
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
                        ?: detectLangFromText(restoredSourceLines.take(12).joinToString(" ") { it.text })
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

            if (lines == null && cached == null && sourceImportedLines.isNullOrEmpty()) {
                if (!io.translatetube.local.data.ModelFiles.asrReady(this)) {
                    // 方案 B：转写模型未下载——优雅降级（原声播放 + 引导提示），不崩
                    updateUiForSession(key) { it.copy(noModelHint = "该视频无字幕。下载「转写模型」后即可生成中文字幕与配音（设置 → 语音模型）") }
                } else {
                // ---- 2b. 无字幕 → 本地转写 ----
                updateUiForSession(key) { it.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "无字幕，准备语音转写…") }
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
                updateUiForSession(key) { it.copy(phase = Phase.TRANSCRIBING, progress = "解码音频…") }
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
                            )
                        } else {
                            AudioExtract.decodeTo16kMonoFile(
                                this@PlaybackService, audioFile as Uri, null,
                                checkActive = { pcmOwnerJob?.ensureActive() },
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
                        val removed = !audioFile.exists() || audioFile.delete()
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
                ensureCurrentSession(key)
                updateUiForSession(key) { it.copy(progress = "语音分段…") }
                val segs = withContext(Dispatchers.IO) {
                    vadSegmentsFromFile(
                        this@PlaybackService,
                        pcmFile,
                        checkActive = { pcmOwnerJob?.ensureActive() },
                    )
                }
                ensureCurrentSession(key)
                if (segs.isEmpty()) throw IllegalStateException("未检测到语音内容")
                // PCM stays on disk; VAD timestamps and the current segment are loaded as needed.
                val partialAsrCacheKey = "${sourceSubtitleCacheKey}|asr-progress-v1"
                val asrSignature = AsrTranscriptCheckpointPolicy.signature(pcmFile, segs)
                var restoredProcessedSegments = 0
                var restoredAsrRows = emptyList<Triple<Long, Long, String>>()
                var restoredAsrLanguage = ""
                val storedPartialAsr = if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()) {
                    db.subtitleCacheDao().get(partialAsrCacheKey)
                } else null
                if (storedPartialAsr != null) {
                    val restored = runCatching {
                        val checkpoint = org.json.JSONObject(storedPartialAsr.linesJson)
                        if (checkpoint.optInt("version", -1) != 1) {
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
                        if (savedRows.length() > processed) {
                            throw IllegalStateException("ASR 检查点字幕数量超出已完成分段")
                        }
                        (0 until savedRows.length()).map { index ->
                            val row = savedRows.optJSONObject(index)
                                ?: throw IllegalStateException("ASR 检查点第 ${index + 1} 行格式无效")
                            val rowStart = row.optLong("s", Long.MIN_VALUE)
                            val rowEnd = row.optLong("e", Long.MIN_VALUE)
                            val rowText = row.optString("t").trim()
                            val belongsToProcessedSegment = segs.take(processed).any { segment ->
                                segment.first == rowStart && segment.second == rowEnd
                            }
                            if (rowStart < 0L || rowEnd <= rowStart || rowText.isBlank() ||
                                !belongsToProcessedSegment
                            ) {
                                throw IllegalStateException("ASR 检查点包含无效或未完成的字幕行")
                            }
                            Triple(rowStart, rowEnd, rowText)
                        }.let { rows ->
                            PartialAsrTranscript(processed, rows, storedPartialAsr.srcLang)
                        }
                    }.getOrNull()
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

                val (asrLines, detectedAsrLang) = whisperMutex.withLock {
                    ensureCurrentSession(key)
                    withContext(Dispatchers.IO) {
                        val engine = whisper ?: WhisperEngine(this@PlaybackService).also { whisper = it }
                        if (restoredProcessedSegments < segs.size) {
                            // A shared engine may remember the previous video's detected language.
                            // Reset it for this transcript, then use the saved checkpoint language as fallback.
                            engine.clearDetectedLanguage()
                        }
                        val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                        val rows = restoredAsrRows.toMutableList()
                        var processed = restoredProcessedSegments
                        while (processed < segs.size) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val chunkStart = processed
                            val chunkEnd = minOf(chunkStart + 3, segs.size)
                            val chunkRows = engine.transcribeBySegments(
                                pcmFile,
                                segs.subList(chunkStart, chunkEnd),
                                checkActive = { ownerJob?.ensureActive() },
                            ) { done, _ ->
                                updateUiForSession(key) {
                                    it.copy(progress = "转写中 ${chunkStart + done}/${segs.size} 段")
                                }
                            }
                            rows.addAll(chunkRows)
                            processed = chunkEnd

                            if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty()) {
                                ensureCurrentSession(key)
                                val rowsJson = org.json.JSONArray()
                                rows.forEach { (startMs, endMs, text) ->
                                    rowsJson.put(
                                        org.json.JSONObject()
                                            .put("s", startMs)
                                            .put("e", endMs)
                                            .put("t", text)
                                    )
                                }
                                val language = engine.lastLang.takeIf { it.isNotBlank() }
                                    ?: restoredAsrLanguage
                                val checkpointJson = org.json.JSONObject()
                                    .put("version", 1)
                                    .put("signature", asrSignature)
                                    .put("totalSegments", segs.size)
                                    .put("processedSegments", processed)
                                    .put("rows", rowsJson)
                                db.subtitleCacheDao().put(
                                    io.translatetube.local.data.SubtitleCacheEntity(
                                        partialAsrCacheKey,
                                        language,
                                        checkpointJson.toString(),
                                        System.currentTimeMillis(),
                                    )
                                )
                            }
                        }
                        val language = if (restoredProcessedSegments >= segs.size) {
                            restoredAsrLanguage.takeIf { it.isNotBlank() }
                                ?: detectLangFromText(rows.take(12).joinToString(" ") { it.third })
                        } else {
                            engine.lastLang.takeIf { it.isNotBlank() }
                                ?: restoredAsrLanguage.takeIf { it.isNotBlank() }
                                ?: "en"
                        }
                        if (shouldReleaseWhisperForMemoryPressure() && whisper === engine) {
                            // The recognizer is native-memory heavy. Release only under reported
                            // memory pressure; healthy phones can keep it warm for another ASR job.
                            whisper = null
                            runCatching { engine.close() }
                                .onFailure { android.util.Log.w("PlaybackService", "Whisper release under memory pressure failed", it) }
                            android.util.Log.i("PlaybackService", "Released Whisper recognizer after ASR because of memory pressure")
                        }
                        rows to language
                    }
                }
                ensureCurrentSession(key)
                transientPcmFile?.file?.delete()
                transientPcmFile = null
                // Only disposable local temporary files may be removed before the transcript checkpoint.
                // YouTube audio remains cached until its ASR subtitles have been saved successfully.
                if (audioFile is File && videoId == null && remoteMediaUrl == null) {
                    audioFile.delete()
                    if (transientAudio == audioFile) transientAudio = null
                }
                val transcribedLines = asrLines
                    .mapNotNull { (startMs, endMs, text) ->
                        val normalized = text.trim()
                        if (normalized.isBlank() || endMs <= startMs) null
                        else Triple(startMs, endMs, normalized)
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
                srcLang = detectedAsrLang.takeIf { it.isNotBlank() } ?: "en"

                // Keep the completed ASR transcript as an independent checkpoint. The bounded
                // source-audio cache is retained for retries and later speaker diarization, while
                // this subtitle cache lets translation retry without running ASR again.
                ensureCurrentSession(key)
                if ((videoId != null || localUri != null || remoteMediaUrl != null) && sourceImportedLines.isNullOrEmpty() && transcribedLines.isNotEmpty()) {
                    val sourceArr = org.json.JSONArray()
                    for (line in transcribedLines) {
                        sourceArr.put(
                            org.json.JSONObject()
                                .put("s", line.startMs)
                                .put("e", line.endMs)
                                .put("t", line.text)
                        )
                    }
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            sourceSubtitleCacheKey,
                            srcLang,
                            sourceArr.toString(),
                            System.currentTimeMillis(),
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
                srcLang = detectLangFromText(sourceImportedLines!!.take(12).joinToString(" ") { it.text })
            }

            if (cached != null) {
                // 缓存命中：直接还原双语行
                val arr = org.json.JSONArray(cached.linesJson)
                val restored = mutableListOf<SubLine>()
                for (i in 0 until arr.length()) {
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
                lines = restored
                srcLang = cached.srcLang
                if (!isChineseLanguage(srcLang) && restored.any { line ->
                    line.text.isNotBlank() && SubtitleParsers.cleanTranslatedText(line.zh).isNullOrBlank()
                }) {
                    updateUiForSession(key) {
                        it.copy(
                            phase = Phase.TRANSLATING,
                            sourceLang = srcLang,
                            progress = "继续翻译缓存中尚未完成的字幕…",
                        )
                    }
                    lines = translateLines(key, restored, srcLang, translationCacheKey)
                }
            } else if (lines != null) {
                // ---- 3. 翻译 ----
                var translatedLines = lines!!
                if (translatedLines.isNotEmpty() && !isChineseLanguage(srcLang)) {
                    updateUiForSession(key) { it.copy(phase = Phase.TRANSLATING, progress = "字幕已读取，准备翻译（${translatedLines.size} 行）…") }
                    translatedLines = translateLines(key, translatedLines, srcLang, translationCacheKey)
                } else if (translatedLines.isNotEmpty()) {
                    // Already-Chinese source subtitles are the Chinese display track; publish immutable copies.
                    translatedLines = translatedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                }
                lines = translatedLines
                // 仅在译文填充率 >50% 时写缓存（避免失败结果被永久缓存）
                ensureCurrentSession(key)
                if (translatedLines.count { !it.zh.isNullOrEmpty() } > translatedLines.size / 2) {
                    val arr = org.json.JSONArray()
                    for (l in translatedLines) arr.put(
                        org.json.JSONObject().put("s", l.startMs).put("e", l.endMs).put("t", l.text).put("zh", l.zh ?: "")
                    )
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(translationCacheKey, srcLang, arr.toString(), System.currentTimeMillis())
                    )
                }
            }

            ensureCurrentSession(key)
            val finalLines = applySavedLineOverrides(lines ?: emptyList(), key)
            updateUiForSession(key) {
                it.copy(
                    lines = finalLines, totalLines = finalLines.size, sourceLang = srcLang,
                    progress = "", phase = Phase.SYNTHESIZING,
                )
            }

            // ---- 4. 流装配 ----
            if (videoId != null) {
                val streamInfo = NewPipeRepo.streams(videoId)
                ensureCurrentSession(key)
                val choices = buildYouTubeStreamChoices(streamInfo)
                val candidates = choices.sources
                val audioUrl = choices.audioUrl
                val defaultQuality = choices.defaultQuality
                qualitySources = candidates
                qualityAudioUrl = audioUrl
                updateUiForSession(key) {
                    it.copy(
                        qualityOptions = candidates.map { q -> q.label },
                        selectedQuality = defaultQuality.label,
                    )
                }
                ensureCurrentSession(key)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key)
                    pm.setYouTubeQuality(
                        videoUrl = defaultQuality.url,
                        audioUrl = audioUrl,
                        muxed = defaultQuality.muxed,
                        title = meta.title,
                        artist = meta.channel,
                        artworkUrl = meta.thumbUrl,
                    )
                }
            } else {
                ensureCurrentSession(key)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key)
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
                )
                }
            }

            // ---- 5. 配音引擎：必须严格使用设置里明确选择的引擎 ----
            if (finalLines.isNotEmpty()) {
                val selectedTts = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("tts_engine", "kokoro") ?: "kokoro"
                val selectedTtsName = when (selectedTts) {
                    "system" -> "系统 TTS"
                    "kokoro" -> "Kokoro"
                    else -> "未知配音引擎"
                }
                updateUiForSession(key) { it.copy(ttsEngineName = selectedTtsName) }

                val selectedEngineReady = when (selectedTts) {
                    "system" -> true
                    "kokoro" -> kokoroRuntimeReady()
                    else -> false
                }
                if (selectedEngineReady) {
                    try {
                        // Pass the saved selection explicitly so a settings change during parsing
                        // cannot cause the readiness check and actual engine to disagree.
                        val tts: TtsEngine = makeTts(selectedTts)
                        try {
                            ensureCurrentSession(key)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            runCatching { tts.close() }
                            throw e
                        }
                        val d = try {
                            DubbingEngine(this, key, scope)
                        } catch (e: Exception) {
                            runCatching { tts.close() }
                            throw e
                        }
                        registerDubbingFailureHandler(d, tts, key)
                        updateUiForSession(key) {
                            it.copy(
                                ttsEngineName = tts.name,
                                dubbingStatus = "${tts.name} 已初始化，正在准备首句配音",
                                noModelHint = null,
                            )
                        }
                        d.attach(tts, finalLines)
                        try {
                            ensureCurrentSession(key)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            retireDubbing(d)
                            throw e
                        }
                        dubbing = d
                        if (videoKey != key || ui.value.key != key) {
                            if (dubbing === d) dubbing = null
                            retireDubbing(d)
                            throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频配音引擎")
                        }
                        dubCollectJob = scope.launch {
                            kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                                .collect { (prefix, readyCount) ->
                                    updateUiForSession(key) {
                                        it.copy(preparedUntil = prefix, preparedCount = readyCount)
                                    }
                                }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("PlaybackService", "Unable to initialize selected TTS engine $selectedTts", e)
                        val reason = e.message ?: e.javaClass.simpleName
                        updateUiForSession(key) {
                            it.copy(
                                ttsEngineName = selectedTtsName,
                                dubbingStatus = "${selectedTtsName} 初始化失败",
                                muteOriginal = false,
                                noModelHint = if (selectedTts == "system")
                                    "已选择系统 TTS，但系统配音引擎初始化失败：$reason。不会自动切换到 Kokoro。请检查手机的文字转语音服务和中文语音数据。"
                                else
                                    "已选择 Kokoro 模型，但初始化失败：$reason。不会自动切换到系统 TTS。请检查配音模型文件。",
                                error = null
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (videoKey == key && ui.value.key == key) pm.originalVolume = 1f
                        }
                    }
                } else {
                    val message = when (selectedTts) {
                        "kokoro" -> "已选择 Kokoro 配音模型，但模型尚未就绪。应用不会自动切换到系统 TTS；下载完整的配音模型后再试。"
                        else -> "设置中保存的配音引擎「$selectedTts」无效。请重新选择 Kokoro 或系统 TTS。应用不会擅自切换引擎。"
                    }
                    updateUiForSession(key) {
                        it.copy(
                            ttsEngineName = if (selectedTts == "kokoro") "Kokoro（未就绪）" else selectedTtsName,
                            dubbingStatus = "${selectedTtsName} 尚未就绪",
                            muteOriginal = false,
                            noModelHint = message,
                        )
                    }
                    withContext(Dispatchers.Main) {
                            if (videoKey == key && ui.value.key == key) pm.originalVolume = 1f
                        }
                }
            }
            // TTS 在后台按字幕顺序预合成。预热最前面的可朗读行后再启动视频，
            // 避免原声已被静音、系统 TTS 却还没合成出第一句时出现长时间无声。
            val startupDubbing = dubbing
            if (startupDubbing != null) {
                // Wait for actual speech cues, not the contiguous prepared-row counter. Empty or
                // failed rows before the first spoken cue must not block a ready first sentence.
                val startupSpeechIndices = finalLines.indices
                    .filter { !finalLines[it].zh.isNullOrBlank() }
                    .take(2)
                if (startupSpeechIndices.isNotEmpty()) {
                    startupDubbing.prioritizeAround(startupSpeechIndices.first())
                    val deadlineNs = System.nanoTime() + 12_000_000_000L
                    fun cueFinished(index: Int): Boolean =
                        startupDubbing.isPrepared(index) || startupDubbing.isSilent(index)
                    while (
                        startupSpeechIndices.any { !cueFinished(it) } &&
                        System.nanoTime() < deadlineNs
                    ) {
                        kotlinx.coroutines.delay(100)
                    }
                    val firstSpeech = startupSpeechIndices.first()
                    if (!cueFinished(firstSpeech)) {
                        android.util.Log.w("PlaybackService", "TTS startup buffer timed out before first spoken line")
                        updateUiForSession(key) { it.copy(progress = "首句配音仍在准备，已先开始视频播放") }
                    } else if (startupSpeechIndices.drop(1).any { !cueFinished(it) }) {
                        updateUiForSession(key) { it.copy(progress = "首句配音已就绪，其余配音继续准备…") }
                    }
                }
            }
            ensureCurrentSession(key)
            saveHistory(meta)
            ensureCurrentSession(key)
            updateUiForSession(key) { it.copy(phase = Phase.READY, engineName = it.engineName.ifEmpty { "本地引擎" }) }
            // 续播：上次观看超过 10 秒且距结尾超 30 秒则跳到上次位置
            val resumeSec = try {
                db.videoDao().byKey(key)?.watchedSec ?: 0L
            } catch (_: Exception) { 0L }
            val totalMs = meta.durationSec * 1000
            if (resumeSec > 10 && (totalMs <= 0 || resumeSec * 1000 < totalMs - 30_000)) {
                ensureCurrentSession(key)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key)
                    pm.seekTo(resumeSec * 1000)
                    pm.play()
                }
            } else {
                ensureCurrentSession(key)
                withContext(Dispatchers.Main) {
                    ensureCurrentSession(key)
                    pm.play()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Video pipeline failed for key=$key", e)
            if (e is TranslationFailureException) {
                if (ui.value.key == key && videoKey == key) runCatching { pm.originalVolume = 1f }
                updateUiForSession(key) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "API 翻译失败",
                        muteOriginal = false,
                        noModelHint = null,
                    )
                }
            } else {
            // A TTS constructor can fail before the subtitle list is published to UI.
            // Restore original volume immediately so default mute never silences the whole video.
            if (ui.value.key == key && videoKey == key && ui.value.dubbingOn) {
                runCatching { pm.originalVolume = 1f }
                updateUiForSession(key) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "视频解析或配音初始化失败",
                        muteOriginal = false,
                        noModelHint = "配音初始化失败，已恢复视频原声。系统 TTS 请检查手机文字转语音服务/中文语音数据；Kokoro 请检查模型文件是否完整。"
                    )
                }
            } else {
                updateUiForSession(key) { it.copy(phase = Phase.ERROR, progress = "", error = e.message ?: "视频解析失败") }
            }
            }
        } catch (e: Throwable) {
            // A non-fatal Error (for example, an extractor/library linkage error) must be shown
            // as a playback failure rather than escaping a Dispatchers.Default coroutine.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            android.util.Log.e("PlaybackService", "Fatal-looking non-VM error in video pipeline for key=$key", e)
            updateUiForSession(key) {
                it.copy(
                    phase = Phase.ERROR,
                    progress = "",
                    error = "视频解析/播放器异常（${e.javaClass.simpleName}）：${e.message ?: "请查看崩溃日志"}"
                )
            }
        } finally {
            transientPcmFile?.file?.delete()
            transientAudio?.delete()
        }
    }

    /** 按活跃引擎翻译，失败自动降级：API→本地→原文 */
    /** 按小批次翻译；每批完成就写入真实进度，避免整批请求期间进度停滞。 */
    private suspend fun translateLines(
        key: String,
        lines: List<SubLine>,
        srcLang: String,
        cacheKeyOverride: String? = null,
    ): List<SubLine> {
        // Keep previously published subtitle rows immutable so Compose observes every translated batch.
        var updatedLines: List<SubLine> = lines.toList()
        val db = AppDatabase.get(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val activeId = prefs.getLong("active_api_id", -1L)
        val profiles = db.apiProfileDao().enabled()
        val tgt = "zh"
        val plain = lines.map { it.text }
        var engine: TranslationEngine? = null
        var engineName = ""

        // Keep old preference values valid while treating local and online translation as one Google engine.
        if (activeId == -1L || activeId == -2L) {
            engine = GoogleHybridEngine(this)
            engineName = engine.displayName
        } else if (activeId == EngineFactory.ID_FREE_GEMINI || activeId == EngineFactory.ID_FREE_DEEPSEEK) {
            engine = EngineFactory.getBuiltinEngine(activeId, this)
            engineName = if (activeId == EngineFactory.ID_FREE_GEMINI) "Google Gemini (免费)" else "DeepSeek (免费)"
        }
        val active = profiles.firstOrNull { it.id == activeId }
        if (engine == null && active != null) {
            try {
                engine = EngineFactory.fromProfile(active)
                engineName = active.name
            } catch (e: Exception) { /* 坏配置 → 落到本地 */ }
        }
        if (engine == null) {
            engine = MlKitEngine()
            engineName = "本地引擎（离线）"
        }
        val pendingIndices = lines.indices.filter { index ->
            lines[index].text.isNotBlank() && SubtitleParsers.cleanTranslatedText(lines[index].zh).isNullOrBlank()
        }
        var completed = lines.indices.count { index ->
            lines[index].text.isBlank() || !SubtitleParsers.cleanTranslatedText(lines[index].zh).isNullOrBlank()
        }
        updateUiForSession(key) {
            it.copy(
                engineName = engineName,
                sourceLang = srcLang,
                lines = updatedLines,
                totalLines = updatedLines.size,
                progress = "翻译中 ${completed}/${updatedLines.size} 行",
            )
        }

        val src = if (srcLang == "auto") "" else srcLang
        val batchSize = 10
        val translationCacheKey = cacheKeyOverride ?: "${key}|zh|${activeId}"
        for (batchIndices in pendingIndices.chunked(batchSize)) {
            // A model may return fewer rows than requested, or put UI/status text in a translation
            // slot. Retry only missing rows once; never let a successful pipeline retain blanks.
            var unresolvedIndices = batchIndices
            val resolvedBatch = linkedMapOf<Int, String>()
            var attempt = 0
            while (unresolvedIndices.isNotEmpty() && attempt < 2) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val batch = unresolvedIndices.map { plain[it] }
                val batchTranslations = try {
                    engine!!.translate(batch, src, tgt)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                    } else {
                        "翻译失败：" + detail + "。请检查网络，或在 Google 翻译中下载对应离线语言包。"
                    }
                    throw TranslationFailureException(message, e)
                }

                val parsedBatch = io.translatetube.local.translate.TranslationBatchResult.from(
                    unresolvedIndices,
                    batchTranslations,
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

            if (unresolvedIndices.isNotEmpty()) {
                throw TranslationFailureException(
                    "翻译结果不完整：重试后仍有 ${unresolvedIndices.size} 行未得到有效译文。已停止处理，避免在播放页把缺失内容显示为“正在翻译”。请重试或更换翻译引擎。",
                    IllegalStateException("Missing subtitle translation rows: ${unresolvedIndices.joinToString()}"),
                )
            }

            updatedLines = updatedLines.mapIndexed { index, line ->
                resolvedBatch[index]?.let { translation -> line.copy(zh = translation) } ?: line
            }
            completed += batchIndices.size
            updateUiForSession(key) {
                it.copy(
                    lines = updatedLines,
                    totalLines = updatedLines.size,
                    sourceLang = srcLang,
                    progress = "翻译中 ${completed.coerceAtMost(updatedLines.size)}/${updatedLines.size} 行",
                )
            }
            // Persist a checkpoint only after this batch is complete and every row has a valid translation.
            val cacheRows = org.json.JSONArray()
            for (line in updatedLines) cacheRows.put(
                org.json.JSONObject()
                    .put("s", line.startMs)
                    .put("e", line.endMs)
                    .put("t", line.text)
                    .put("zh", line.zh ?: "")
                    .put("sp", line.speakerId)
            )
            db.subtitleCacheDao().put(
                io.translatetube.local.data.SubtitleCacheEntity(
                    translationCacheKey, srcLang, cacheRows.toString(), System.currentTimeMillis()
                )
            )
        }
        return updatedLines
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
                        selectedQuality = fallback.label,
                    )
                }
                pm.setYouTubeQuality(
                    videoUrl = fallback.url,
                    audioUrl = choices.audioUrl,
                    muxed = fallback.muxed,
                    title = ui.value.title,
                    artist = ui.value.channel,
                    artworkUrl = ui.value.thumbUrl,
                )
                pm.seekTo(position)
                if (shouldResume) pm.play() else pm.pause()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "YouTube playback recovery failed", e)
            withContext(Dispatchers.Main) {
                if (ui.value.key == sessionKey && videoId == failedVideoId) {
                    pm.errorSink.value = null
                    runCatching { pm.originalVolume = 1f }
                    updateUiForSession(sessionKey) {
                        it.copy(
                            phase = Phase.ERROR,
                            progress = "",
                            error = "YouTube 播放失败，刷新视频流后仍无法播放：${e.message ?: originalError}",
                            muteOriginal = false,
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
                    state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "正在获取视频音频流…")
                } else state
            }
            val stream = NewPipeRepo.audioForAsr(videoId)
                ?: throw IllegalStateException("无法获取视频音频流")
            val destination = asrAudioCacheFile(videoId)
            suspend fun downloadStream(url: String): File = try {
                downloadAudio(url, destination, sessionKey)
            } catch (manifest: HlsManifestPayloadException) {
                HlsAudioDownloader.download(url, destination) { detail ->
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
                    val refreshed = NewPipeRepo.audioForAsr(videoId) ?: throw firstError
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

    /** Stable translation identity excludes rotating URL signatures and authentication tokens. */
    private fun remoteCacheIdentity(url: String): String =
        MediaUrlCacheIdentity.key(url)

    /**
     * Download a direct or finite HLS webpage media URL into a bounded cache for Whisper.
     * The referrer/cookie are obtained from the current WebView session and are never persisted.
     */
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
                    state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "正在缓存网页视频音轨…")
                } else state
            }
            val headers = buildMap {
                referrer.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { put("Referer", it) }
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            val destination = asrAudioCacheFile(identity)
            activeAsrAudioPaths.acquire(destination.absolutePath)
            var leaseTransferred = false
            try {
                val cachedAudio = if (HlsAudioDownloader.isManifestUrl(mediaUrl)) {
                    HlsAudioDownloader.download(mediaUrl, destination, headers) { detail ->
                        updateUiForSession(sessionKey) { state ->
                            if (state.key == sessionKey) {
                                state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = detail)
                            } else state
                        }
                    }
                } else {
                    try {
                        downloadAudio(mediaUrl, destination, sessionKey, headers)
                    } catch (manifest: HlsManifestPayloadException) {
                        // Some servers serve HLS playlists from opaque URLs without an .m3u8 suffix.
                        // Detect the response body, then re-open through the bounded HLS segment path.
                        HlsAudioDownloader.download(mediaUrl, destination, headers) { detail ->
                            updateUiForSession(sessionKey) { state ->
                                if (state.key == sessionKey) {
                                    state.copy(phase = Phase.DOWNLOADING_AUDIO, progress = detail)
                                } else state
                            }
                        }
                    }
                }
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
        // Do not remove a candidate another active reader still owns, even if this caller won't reuse it.
        if (!activeAsrAudioPaths.contains(file.absolutePath)) file.delete()
        File(file.parentFile, file.name + ".part").delete()
        return null
    }

    /** Keep retry audio bounded in size; never evict the file currently being reused/downloaded. */
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
        // A process killed during download may leave a large .part file. Remove abandoned partials
        // after six hours; active downloads update their modification time as bytes are written.
        allFiles.filter { file ->
            file.isFile && file.name.endsWith(".part") &&
                (now - file.lastModified() > 6L * 60L * 60L * 1_000L)
        }.forEach { it.delete() }
        val files = allFiles.filter { it.isFile && it.name.endsWith(".bin") }
        var totalBytes = 0L

        // Enforce TTL even when the folder is below its size budget; otherwise old videos
        // could leave stale cache files on disk indefinitely.
        files.forEach { file ->
            val isKept = keep != null && file.absolutePath == keep.absolutePath
            val isActive = activeAsrAudioPaths.contains(file.absolutePath)
            val age = now - file.lastModified()
            if (!isKept && !isActive &&
                (file.length() <= 0L || age < 0L || age > AsrAudioCachePolicy.TTL_MS)
            ) {
                file.delete()
            } else {
                // Active files still count toward the budget, but must not be deleted mid-decode.
                totalBytes += file.length()
            }
        }

        val budget = AsrAudioCachePolicy.CACHE_BUDGET_BYTES
        for (file in files.sortedBy { it.lastModified() }) {
            if (totalBytes <= budget) break
            if (keep != null && file.absolutePath == keep.absolutePath) continue
            if (activeAsrAudioPaths.contains(file.absolutePath)) continue
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private suspend fun downloadAudio(
        url: String,
        destination: File? = null,
        sessionKey: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): File {
        val target = destination ?: File(cacheDir, "asr_audio_${System.nanoTime()}.bin")
        target.parentFile?.mkdirs()
        val writeTarget = if (destination == null) target else File(target.parentFile, target.name + ".part")
        val maxBytes = AsrAudioCachePolicy.MAX_AUDIO_BYTES
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
                        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
                    ).use { resp ->
                        SafeMediaHttp.validateCompleteGetResponse(resp.code, "音频文件")
                        val body = resp.body ?: throw IllegalStateException("音频下载响应为空")
                        if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                            throw IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频；链接可能过期或需要登录。")
                        }
                        val total = body.contentLength()
                        if (total > maxBytes) {
                            throw IllegalStateException("音频文件超过 160 MB 限制，已停止下载以保护设备存储")
                        }
                        body.byteStream().use { ins ->
                            var got = 0L
                            val payloadPrefix = ByteArray(512)
                            var payloadPrefixSize = 0
                            writeTarget.outputStream().buffered().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val n = ins.read(buf)
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
                                    got += n
                                    if (got > maxBytes) {
                                        throw IllegalStateException("音频文件超过 160 MB 限制，已停止下载以保护设备存储")
                                    }
                                    out.write(buf, 0, n)
                                    if (total > 0) {
                                        val progress = "下载音频 %.0f%%".format(
                                            (got * 100f / total).coerceIn(0f, 100f)
                                        )
                                        updateUiForSession(sessionKey) { state ->
                                            if (state.key == sessionKey) state.copy(progress = progress) else state
                                        }
                                    }
                                }
                                out.flush()
                            }
                            if (!AsrAudioCachePolicy.isCompleteDownload(total, got)) {
                                throw IllegalStateException(
                                    "音频下载不完整：响应声明 ${total} 字节，实际收到 ${got} 字节"
                                )
                            }
                        }
                    }
                }

                if (!writeTarget.isFile || writeTarget.length() == 0L) {
                    throw IllegalStateException("音频下载结果为空")
                }
                if (destination == null) {
                    writeTarget.setLastModified(System.currentTimeMillis())
                    return writeTarget
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("下载完成但无法替换旧的音频缓存")
                }
                if (!writeTarget.renameTo(target)) {
                    throw IllegalStateException("下载完成但无法保存音频缓存")
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

    private fun makeTts(engineId: String? = null): TtsEngine {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val selected = engineId ?: (prefs.getString("tts_engine", "kokoro") ?: "kokoro")
        return when (selected) {
            "system" -> io.translatetube.local.tts.SystemTts(this)
            "kokoro" -> {
                if (!kokoroRuntimeReady()) {
                    throw IllegalStateException("已选择 Kokoro，但配音模型尚未就绪")
                }
                KokoroTts(this, speakerId = prefs.getInt("kokoro_speaker", 14))
            }
            else -> throw IllegalStateException("未知配音引擎「$selected」，请在设置中重新选择")
        }
    }

    /**
     * Centralize synthesis-failure reporting for normal playback and imported subtitles.
     * The explicitly selected TTS engine is retained; the source audio is restored on failure.
     */
    private fun registerDubbingFailureHandler(
        engine: DubbingEngine,
        tts: TtsEngine,
        sessionKey: String = ui.value.key,
    ) {
        engine.onFirstSynthesisReady = { index, sampleCount, sampleRate ->
            val durationMs = sampleCount.toLong() * 1_000L / sampleRate.coerceAtLeast(1)
            ui.update { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        dubbingStatus = "${tts.name} 已生成第 ${index + 1} 句 PCM（约 ${durationMs} ms）"
                    )
                } else state
            }
        }
        engine.onPlaybackStarted = {
            ui.update { state ->
                if (state.key == sessionKey) state.copy(dubbingStatus = "${tts.name} 正在播放中文配音")
                else state
            }
        }
        engine.onSynthesisError = { detail ->
            val message = "当前选择的「${tts.name}」配音失败，未自动切换到其他引擎。$detail"
            ui.update { state ->
                if (state.key == sessionKey) {
                    state.copy(
                        ttsEngineName = tts.name,
                        dubbingStatus = "${tts.name} 合成或播放失败，已恢复原声",
                        muteOriginal = false,
                        noModelHint = message,
                    )
                } else state
            }
            scope.launch(Dispatchers.Main) {
                if (::pm.isInitialized && ui.value.key == sessionKey) {
                    runCatching { pm.originalVolume = 1f }
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
    private fun tick(ms: Long) {
        val st = ui.value
        val d = dubbing

        // 配音必须严格使用 lineAt 的时间区间结果；字幕高亮可以回退到最近一行，
        // 但不能把“字幕间隙中的上一行”当成当前配音行，否则会压低间隙里的原声。
        val dubLine = if (d != null) d.lineAt(ms) else -1
        // AudioTrack startup is asynchronous; publish the newest media playhead even while PCM
        // is loading so the audio executor can skip elapsed speech instead of starting late.
        d?.updateTimelinePosition(dubLine, ms)
        var highlightLine = dubLine
        if (highlightLine < 0) {
            val ls = st.lines
            var lo = 0
            var hi = ls.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (ls[mid].startMs <= ms) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            // 仅用于字幕列表定位；如果上一行已经结束，不将它作为配音行。
            highlightLine = ans
        }
        if (highlightLine != lastLineIdx) {
            lastLineIdx = highlightLine
            ui.update { it.copy(currentLine = highlightLine) }
        }

        if (d != null && st.dubbingOn) {
            // Empty audio can mean translation is missing, synthesis failed, or an old
            // cache has an empty marker. In every case preserve the source audio for this cue,
            // including when the user has requested original-audio muting.
            if (dubLine >= 0 && d.isSilent(dubLine)) {
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = 1f
                if (st.muteOriginal || st.noModelHint == null) {
                    ui.update {
                        it.copy(
                            muteOriginal = false,
                            noModelHint = io.translatetube.local.tts.TtsFailureAdvice.missingAudioHint(st.ttsEngineName)
                        )
                    }
                }
            } else if (dubLine >= 0 && d.isPrepared(dubLine)) {
                if (!pm.player.isPlaying) {
                    // seekTo() emits an immediate tick: don't start a detached AudioTrack while paused.
                    if (d.currentLineIdx() >= 0) d.stop()
                } else if (d.currentLineIdx() != dubLine) {
                    d.playAt(
                        dubLine,
                        if (st.muteOriginal) 1f else 0.35f,
                        positionMs = ms,
                        playerSpeed = pm.playbackSpeed(),
                    )
                } else {
                    d.updatePlayerSpeed(pm.playbackSpeed())
                    d.updateVolume(if (st.muteOriginal) 1.0f else 0.35f)
                }
                // Synthesis readiness is not playback readiness: AudioTrack may still be
                // loading PCM or initializing on older devices. Keep source audio audible until
                // the dubbed track actually starts, then apply the user's mute/duck preference.
                val voiceStarted = d.hasStartedPlaybackFor(dubLine)
                pm.originalVolume = when {
                    !voiceStarted -> 1f
                    st.muteOriginal -> 0f
                    else -> 0.15f
                }
            } else if (dubLine >= 0) {
                // Prioritize the current cue after a seek or when synthesis is lagging.
                d.prioritizeAround(dubLine)
                // While TTS is preparing this line, preserve source audio; mute it only when dubbing is ready.
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = 1f
            } else {
                // 字幕间隙不播放配音；按“原声静音”开关决定是否保留原音。
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = if (st.muteOriginal) 0f else 1f
            }
        } else {
            // If dubbing is enabled but the TTS engine could not be created, restore source audio
            // rather than honoring the default mute and leaving the entire video silent.
            if (st.dubbingOn && st.muteOriginal && st.lines.isNotEmpty()) {
                pm.originalVolume = 1f
                if (st.noModelHint == null) {
                    ui.update {
                        it.copy(
                            muteOriginal = false,
                            noModelHint = "配音引擎未能启动，已恢复视频原声。系统 TTS 请检查手机的文字转语音服务与中文语音数据；Kokoro 请检查配音模型是否完整。"
                        )
                    }
                }
            } else {
                pm.originalVolume = if (st.muteOriginal) 0f else 1f
            }
        }

        // Keep the displayed duration synchronized with ExoPlayer in case the extractor metadata
        // omitted or misreported the duration. This also prevents a permanent 00:00:00 total time.
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
            .getString("tts_engine", "kokoro") ?: "kokoro"
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
        speakerJob = scope.launch {
            var pendingTts: TtsEngine? = null
            var oldEngineRetired = false
            try {
                ensureCurrentSession(sessionKey)
                updateUiForSession(sessionKey) {
                    it.copy(
                        error = null,
                        progress = if (selectedTts == "system") "检查手机可用的中文音色…" else "准备人物识别音频（为荣耀 10 限制为前 3 分钟）…",
                        phase = Phase.TRANSCRIBING,
                    )
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        progress = if (selectedTts == "system") "检查手机可用的中文音色…" else "初始化所选配音引擎…",
                    )
                }
                // Initialize the selected engine before the expensive diarization step and before
                // retiring the current dubbing engine. If TTS setup fails, playback remains intact.
                val preflightEngine = makeTts(selectedTts)
                pendingTts = preflightEngine
                if (preflightEngine is io.translatetube.local.tts.SystemTts &&
                    !preflightEngine.supportsMultipleChineseVoices
                ) {
                    updateUiForSession(sessionKey) {
                        it.copy(
                            phase = Phase.READY,
                            progress = "",
                            error = "手机系统 TTS 只提供一个可用中文音色，无法为不同人物分配不同音色；普通系统配音仍可使用。"
                        )
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
                        updateUiForSession(sessionKey) { state ->
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
                        updateUiForSession(sessionKey) { state ->
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
                    updateUiForSession(sessionKey) { state ->
                        if (state.key == sessionKey) state.copy(progress = message) else state
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                if (segments.isEmpty()) throw IllegalStateException("没有识别到可区分的说话人")
                val assignmentSnapshot = loadSpeakerAssignments(sessionKey)
                val speakerVoiceAssignments = assignmentSnapshot.speakerVoices.toMutableMap()
                val lineVoiceAssignments = assignmentSnapshot.lineVoices.toMutableMap()
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
                val updated = originalLines.map { line ->
                    val start = line.startMs / 1000f
                    val end = line.endMs / 1000f
                    val duration = (end - start).coerceAtLeast(0.01f)
                    val overlapBySpeaker = segments.asSequence()
                        .mapNotNull { seg ->
                            val overlap = (minOf(end, seg.endSec) - maxOf(start, seg.startSec)).coerceAtLeast(0f)
                            if (overlap > 0f) seg.speaker to overlap else null
                        }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, overlaps) -> overlaps.sum() }
                    val best = overlapBySpeaker.maxByOrNull { it.value }
                    val detectedSpeaker = if (
                        best != null && best.value >= minOf(0.25f, duration * 0.2f)
                    ) best.key else -1
                    val speaker = canonicalSpeakerIds[detectedSpeaker] ?: -1
                    // Assign each diarized person a stable voice for this video. Manual cue
                    // choices take precedence over the automatic speaker mapping.
                    val suggestedVoice = when {
                        speaker == 0 -> -1
                        speaker in 1..53 -> {
                            val candidate = speaker - 1
                            if (candidate >= preferredVoice) candidate + 1 else candidate
                        }
                        else -> -1
                    }
                    val keyForLine = SpeakerAssignmentCodec.lineKey(line.startMs, line.endMs)
                    val voice = assignmentSnapshot.lineOverrides[keyForLine] ?: if (speaker in 0..53) {
                        speakerVoiceAssignments.getOrPut(speaker) { suggestedVoice }
                    } else {
                        suggestedVoice
                    }
                    lineVoiceAssignments[keyForLine] = voice
                    line.copy(speakerId = voice)
                }
                saveSpeakerAssignments(
                    assignmentSnapshot.copy(
                        speakerVoices = speakerVoiceAssignments,
                        lineVoices = lineVoiceAssignments,
                    ),
                    sessionKey,
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val distinctVoices = updated.map { it.speakerId }.distinct().size
                updateUiForSession(sessionKey) { state ->
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
                val tts = pendingTts ?: makeTts(selectedTts)
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!ttsWasPreflighted) runCatching { tts.close() }
                    throw e
                }
                val d = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope)
                } catch (e: Exception) {
                    if (!ttsWasPreflighted) runCatching { tts.close() }
                    throw e
                }
                pendingTts = null
                registerDubbingFailureHandler(d, tts, sessionKey)
                d.attach(tts, updated)
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(d)
                    throw e
                }
                dubbing = d
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === d) dubbing = null
                    retireDubbing(d)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的人物配音引擎")
                }
                dubCollectJob?.cancel()
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(d.preparedUntil, d.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForSession(sessionKey) { state ->
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
                ensureCurrentSession(sessionKey)
                if (updated.count { !it.zh.isNullOrEmpty() } > updated.size / 2) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            translationCacheKey,
                            sessionSourceLang, arr.toString(), System.currentTimeMillis()
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
                updateUiForSession(sessionKey) { state ->
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
                updateUiForSession(sessionKey) { state ->
                    if (state.key == sessionKey && videoKey == sessionKey) {
                        state.copy(
                            phase = Phase.READY,
                            progress = "",
                            muteOriginal = if (oldEngineRetired) false else sessionState.muteOriginal,
                            error = "自动区分人物失败：${e.message}",
                        )
                    } else state
                }
                if (oldEngineRetired && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) { pm.originalVolume = 1f }
                }
            } finally {
                pendingTts?.close()
            }
        }
    }

    fun setLineSpeaker(index: Int, speakerId: Int) {
        val sessionState = ui.value
        val sessionKey = sessionState.key
        if (sessionKey.isBlank() || videoKey != sessionKey) return
        val oldLines = sessionState.lines
        if (index !in oldLines.indices) return
        val selectedVoice = speakerId.coerceIn(-1, 53)
        if (oldLines[index].speakerId == selectedVoice) return

        val selectedTts = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("tts_engine", "kokoro") ?: "kokoro"
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
        val assignmentSnapshot = loadSpeakerAssignments(sessionKey)
        val updated = oldLines.mapIndexed { i, line ->
            if (i == index) line.copy(speakerId = selectedVoice) else line
        }
        val updatedAssignments = assignmentSnapshot.copy(
            lineOverrides = assignmentSnapshot.lineOverrides +
                (SpeakerAssignmentCodec.lineKey(selectedLine.startMs, selectedLine.endMs) to selectedVoice),
        )

        speakerJob = scope.launch {
            var pendingTts: TtsEngine? = null
            var replacementDubbing: DubbingEngine? = null
            var previousDubbing: DubbingEngine? = null
            var oldEngineRetired = false
            var assignmentsSaved = false
            try {
                ensureCurrentSession(sessionKey)
                updateUiForSession(sessionKey) {
                    it.copy(error = null, progress = "检查可用音色…")
                }

                // Initialize the selected engine before touching the active voice. A phone that
                // offers only one Chinese system voice cannot perform manual multi-speaker changes;
                // in that case the existing dub keeps playing unchanged.
                pendingTts = makeTts(selectedTts)
                if (pendingTts is io.translatetube.local.tts.SystemTts &&
                    !pendingTts.supportsMultipleChineseVoices
                ) {
                    updateUiForSession(sessionKey) {
                        it.copy(
                            progress = "",
                            error = "手机系统 TTS 只提供一个可用中文音色，当前人物音色未更改；普通系统配音仍可使用。"
                        )
                    }
                    return@launch
                }
                ensureCurrentSession(sessionKey)

                previousDubbing = retireCurrentDubbing()
                oldEngineRetired = previousDubbing != null
                awaitRetiredDubbingEngines()
                ensureCurrentSession(sessionKey)

                saveSpeakerAssignments(updatedAssignments, sessionKey)
                assignmentsSaved = true
                updateUiForSession(sessionKey) {
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

                val tts = pendingTts ?: makeTts(selectedTts)
                val engine = try {
                    DubbingEngine(this@PlaybackService, sessionKey, scope)
                } catch (e: Exception) {
                    if (pendingTts == null) runCatching { tts.close() }
                    throw e
                }
                pendingTts = null
                replacementDubbing = engine
                registerDubbingFailureHandler(engine, tts, sessionKey)
                engine.attach(tts, updated)
                try {
                    ensureCurrentSession(sessionKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retireDubbing(engine)
                    replacementDubbing = null
                    throw e
                }
                dubbing = engine
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    if (dubbing === engine) dubbing = null
                    retireDubbing(engine)
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的人物配音引擎")
                }

                dubCollectJob?.cancel()
                dubCollectJob = scope.launch {
                    kotlinx.coroutines.flow.combine(engine.preparedUntil, engine.preparedCount) { prefix, readyCount -> prefix to readyCount }
                        .collect { (prefix, readyCount) ->
                            updateUiForSession(sessionKey) { state ->
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
                ensureCurrentSession(sessionKey)
                if (updated.isNotEmpty() &&
                    updated.count { !it.zh.isNullOrBlank() } > updated.size / 2
                ) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            translationCacheKey,
                            sourceLang,
                            cacheRows.toString(),
                            System.currentTimeMillis(),
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Restore the persisted assignment and translated captions if setup failed before
                // a replacement engine became usable. If the old native engine was already retired,
                // restore original audio rather than leaving the video silent.
                if (replacementDubbing != null && dubbing === replacementDubbing) {
                    dubbing = null
                }
                replacementDubbing?.let { retireDubbing(it) }
                awaitRetiredDubbingEngines()
                if (assignmentsSaved && videoKey == sessionKey && ui.value.key == sessionKey) {
                    runCatching { saveSpeakerAssignments(assignmentSnapshot, sessionKey) }
                }
                updateUiForSession(sessionKey) {
                    it.copy(
                        lines = oldLines,
                        totalLines = oldLines.size,
                        phase = Phase.ERROR,
                        progress = "",
                        // If preflight failed before retiring the old engine, preserve its audio mode.
                        muteOriginal = if (oldEngineRetired) false else sessionState.muteOriginal,
                        error = "切换人物音色失败：${e.message}",
                    )
                }
                if (oldEngineRetired && videoKey == sessionKey && ui.value.key == sessionKey) {
                    withContext(Dispatchers.Main) { pm.originalVolume = 1f }
                }
            } finally {
                pendingTts?.close()
            }
        }
    }

    private fun assignmentPrefsKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        return "video_" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun loadSpeakerAssignments(key: String = videoKey): SpeakerAssignmentSnapshot {
        if (key.isBlank()) return SpeakerAssignmentSnapshot()
        val raw = getSharedPreferences("speaker_assignments_v1", MODE_PRIVATE)
            .getString(assignmentPrefsKey(key), null)
        return SpeakerAssignmentCodec.decode(raw)
    }

    private fun saveSpeakerAssignments(
        snapshot: SpeakerAssignmentSnapshot,
        key: String = videoKey,
    ) {
        if (key.isBlank()) return
        getSharedPreferences("speaker_assignments_v1", MODE_PRIVATE)
            .edit()
            .putString(assignmentPrefsKey(key), SpeakerAssignmentCodec.encode(snapshot))
            .apply()
    }

    private fun applySavedLineOverrides(
        lines: List<SubLine>,
        key: String = videoKey,
    ): List<SubLine> =
        SpeakerAssignmentResolver.apply(lines, loadSpeakerAssignments(key))

    fun toggleDub() {
        val nowOn = !ui.value.dubbingOn
        if (!nowOn) {
            dubbing?.stop()
            // Turning dubbing off must leave a usable audio path. Otherwise a remembered
            // "mute original" setting can silence both the source video and the stopped dub.
            ui.update { it.copy(dubbingOn = false, muteOriginal = false) }
            pm.originalVolume = 1f
        } else {
            ui.update {
                it.copy(
                    dubbingOn = true,
                    dubbingStatus = "正在等待中文配音启动",
                )
            }
            lastLineIdx = Int.MIN_VALUE
        }
    }
    fun toggleMute() {
        val nowMuted = !ui.value.muteOriginal
        ui.update { it.copy(muteOriginal = nowMuted) }
        // Apply this immediately; tick() will refine it to the ducked mix volume when dubbing is active.
        pm.originalVolume = if (nowMuted) 0f else 1f
        if (ui.value.dubbingOn) {
            dubbing?.updateVolume(if (nowMuted) 1.0f else 0.35f)
        }
        lastLineIdx = Int.MIN_VALUE
    }
    fun toggleAudioOnly() {
        val nowOnly = !ui.value.audioOnly
        ui.update { it.copy(audioOnly = nowOnly) }
        // 关画面：禁视频轨（省电）；重开恢复
        pm.player.trackSelectionParameters = pm.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, nowOnly)
            .build()
    }
    fun seek(ms: Long) {
        // Invalidate old speech before seekTo emits its immediate timeline tick.
        dubbing?.stop()
        lastLineIdx = Int.MIN_VALUE
        pm.seekTo(ms)
    }
    fun playPause() { if (pm.isPlaying.value) pm.pause() else pm.play() }

    /** 记录观看进度（退出播放页时调用，历史列表续看用） */
    fun saveProgress() {
        val key = videoKey
        if (key.isEmpty()) return
        scope.launch {
            try {
                AppDatabase.get(this@PlaybackService).videoDao().setWatched(key, pm.position() / 1000)
            } catch (_: Exception) {}
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
        val isDubbingPreparing = dubbing?.isPreparing == true
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
