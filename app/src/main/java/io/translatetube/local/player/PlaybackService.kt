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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class Phase { IDLE, PARSING, DOWNLOADING_AUDIO, TRANSCRIBING, TRANSLATING, SYNTHESIZING, READY, ERROR }
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
    val totalLines: Int = 0,
    val dubbingOn: Boolean = true,
    val muteOriginal: Boolean = true,
    val audioOnly: Boolean = false,
    val error: String? = null,
    val sourceLang: String = "",
    val engineName: String = "",
    val ttsEngineName: String = "",
    val isPlaying: Boolean = false,
    val videoAspect: Float = 16f / 9f,   // 视频真实宽高比（竖屏视频自适应）
    val noModelHint: String? = null,     // 方案 B：模型未下载引导提示
    val speakerRebuildInProgress: Boolean = false,
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

private data class YouTubeQualityInventory(
    val candidates: List<YouTubeQualitySource>,
    val audioUrl: String,
)

/** Build only playable choices; adaptive video-only tracks require a separate audio URL. */
private fun buildYouTubeQualityInventory(
    info: org.schabi.newpipe.extractor.stream.StreamInfo,
): YouTubeQualityInventory {
    val audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream> = info.audioStreams
    val rawCandidates = (info.videoStreams + info.videoOnlyStreams)
        .filter { v -> v.height in 1..2160 && v.content.isNotBlank() }
        .distinctBy { v -> "${v.height}|${v.content}|${v.isVideoOnly}" }
        .groupBy { it.height }
        .mapNotNull { (height, streams) ->
            val chosen = streams.sortedWith(
                compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { v ->
                    val mp4 = v.format?.suffix?.equals("mp4", ignoreCase = true) == true
                    when {
                        !v.isVideoOnly && mp4 -> 0
                        !v.isVideoOnly -> 1
                        mp4 -> 2
                        else -> 3
                    }
                }.thenByDescending { v -> v.bitrate }
            ).firstOrNull()
            chosen?.let {
                YouTubeQualitySource(
                    label = "${height}p",
                    height = height,
                    url = it.content,
                    muxed = !it.isVideoOnly,
                )
            }
        }
        .sortedByDescending { it.height }

    val audioUrl = audioStreams
        .sortedByDescending { it.averageBitrate }
        .firstOrNull { it.content.isNotBlank() }?.content
        ?: audioStreams.firstOrNull { it.content.isNotBlank() }?.content
        ?: ""

    // Never advertise a video-only resolution when the extractor did not supply an audio track.
    val playableCandidates = if (audioUrl.isBlank()) rawCandidates.filter { it.muxed } else rawCandidates
    if (playableCandidates.isEmpty()) {
        throw IllegalStateException(
            if (rawCandidates.isEmpty()) {
                "YouTube 未提供可播放的视频流。请检查网络/VPN 后重试"
            } else {
                "YouTube 未返回独立音频流或带声音的视频流，请稍后重试"
            }
        )
    }

    android.util.Log.i(
        "PlaybackService",
        "YouTube stream inventory: muxed=${info.videoStreams.size}" +
            ", videoOnly=${info.videoOnlyStreams.size}" +
            ", audio=${audioStreams.size}" +
            ", playableQualities=${playableCandidates.map { it.label }}",
    )
    return YouTubeQualityInventory(playableCandidates, audioUrl)
}


class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var pm: PlayerManager
    @Volatile private var playerReady = false
    private var session: MediaSession? = null
    @Volatile private var dubbing: DubbingEngine? = null
    private var whisper: WhisperEngine? = null
    private var prepJob: Job? = null
    @Volatile private var videoKey = ""
    private var videoId: String? = null
    private var localUri: Uri? = null
    @Volatile private var qualitySources: List<YouTubeQualitySource> = emptyList()
    @Volatile private var qualityAudioUrl: String = ""
    private var qualityJob: Job? = null
    private var importedLines: List<SubLine>? = null
    @Volatile private var lastLineIdx = Int.MIN_VALUE
    private var dubCollectJob: Job? = null
    private var speakerJob: Job? = null
    private var speakerRebuildJob: Job? = null
    private val speakerRebuildRevision = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionLock = Any()
    @Volatile private var playbackSessionRevision: Long = 0L

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

    private fun beginPlaybackSession(): Long = synchronized(sessionLock) {
        playbackSessionRevision += 1L
        playbackSessionRevision
    }

    private fun isCurrentPlaybackSession(key: String, revision: Long): Boolean = synchronized(sessionLock) {
        playbackSessionRevision == revision && videoKey == key && ui.value.key == key
    }

    private fun installDubbingForSession(key: String, revision: Long, engine: DubbingEngine): Boolean =
        synchronized(sessionLock) {
            if (playbackSessionRevision != revision || videoKey != key || ui.value.key != key) {
                false
            } else {
                dubbing = engine
                true
            }
        }

    /**
     * Share the same recovery policy across subtitle sources: preserve the selected engine,
     * restore source audio on failure, and ignore stale session/rebuild callbacks.
     */
    private fun registerDubbingFailureHandler(
        engine: DubbingEngine,
        tts: TtsEngine,
        key: String,
        sessionRevision: Long,
    ) {
        val expectedSpeakerRevision = speakerRebuildRevision.get()
        engine.onSynthesisError = { detail ->
            ui.update { state ->
                if (state.key == key &&
                    isCurrentPlaybackSession(key, sessionRevision) &&
                    speakerRebuildRevision.get() == expectedSpeakerRevision
                ) {
                    state.copy(
                        ttsEngineName = tts.name,
                        muteOriginal = false,
                        noModelHint = "当前选择的「${tts.name}」配音失败；应用没有自动切换到其他配音引擎，已恢复原声。$detail",
                    )
                } else state
            }
            scope.launch(Dispatchers.Main) {
                if (isCurrentPlaybackSession(key, sessionRevision) &&
                    speakerRebuildRevision.get() == expectedSpeakerRevision &&
                    ::pm.isInitialized
                ) {
                    runCatching { pm.originalVolume = 1f }
                }
            }
        }
    }

    /**
     * Create a per-video dubbing engine with explicit TTS ownership. If an import is cancelled
     * or installation fails, release whichever object currently owns the TTS.
     */
    private fun createAndInstallDubbingForSession(
        tts: TtsEngine,
        lines: List<SubLine>,
        key: String,
        sessionRevision: Long,
    ): DubbingEngine? {
        val engine = try {
            DubbingEngine(this, key, scope)
        } catch (e: Exception) {
            runCatching { tts.close() }
            throw e
        }
        var ttsAttached = false
        try {
            registerDubbingFailureHandler(engine, tts, key, sessionRevision)
            if (!isCurrentPlaybackSession(key, sessionRevision)) {
                runCatching { tts.close() }
                runCatching { engine.release() }
                return null
            }
            // attach() transfers TTS ownership to the DubbingEngine.
            ttsAttached = true
            engine.attach(tts, lines)
            if (!installDubbingForSession(key, sessionRevision, engine)) {
                runCatching { engine.release() }
                return null
            }
            return engine
        } catch (e: Exception) {
            if (dubbing === engine) dubbing = null
            runCatching { engine.release() }
            if (!ttsAttached) runCatching { tts.close() }
            throw e
        }
    }

    private fun updateUiForSession(
        key: String,
        revision: Long,
        transform: (PlayerUiState) -> PlayerUiState,
    ) {
        ui.update { state ->
            if (state.key == key && isCurrentPlaybackSession(key, revision)) transform(state) else state
        }
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
            pm.onSeek = { ms ->
                // A seek can remain inside the same subtitle cue; force a fresh slice at the new offset.
                dubbing?.stop()
                lastLineIdx = Int.MIN_VALUE
                tick(ms)
            }
            pm.onEnded = { ui.update { it.copy(isPlaying = false, currentLine = -1) } }
            pm.onVideoAspect = { ratio -> ui.update { it.copy(videoAspect = ratio) } }

            // Keep dubbed AudioTrack locked to ExoPlayer's playback state. This callback
            // runs with the player's state change, so voice stops immediately on pause and
            // resumes from the paused sample position instead of finishing the current line.
            pm.player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) dubbing?.resumePlayback() else dubbing?.pausePlayback()
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
                    // A StateFlow can briefly deliver a prior item's error while a new video is
                    // opening. Confirm it still belongs to ExoPlayer on its application looper.
                    withContext(Dispatchers.Main) {
                        if (!::pm.isInitialized) return@withContext
                        val playerError = pm.player.playerError
                        val actualMessage = playerError?.message ?: if (playerError != null) "播放错误" else null
                        if (playerError != null && actualMessage == err) {
                            val currentKey = videoKey
                            val currentRevision = playbackSessionRevision
                            ui.update { state ->
                                if (isCurrentPlaybackSession(currentKey, currentRevision)) {
                                    state.copy(error = err, phase = Phase.ERROR)
                                } else state
                            }
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
                if (state.totalLines > 0) "已翻译 ${state.lines.count { !it.zh.isNullOrBlank() }}/${state.totalLines} 行" else "正在逐句翻译"
            }
            Phase.SYNTHESIZING -> "正在生成配音" to if (state.totalLines > 0) {
                "已准备 ${state.preparedUntil.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音"
            } else state.progress.ifBlank { "正在准备语音" }
            Phase.READY -> if (state.isPlaying) "正在播放" to state.title.ifBlank { "视频配音已准备完成" } else "配音已准备完成" to state.title.ifBlank { "返回应用即可查看字幕与配音" }
            Phase.ERROR -> "处理遇到问题" to (state.error ?: "打开应用查看详情")
            else -> "大象翻译" to "等待开始"
        }

        // Only publish determinate progress when it comes from a measurable source:
        // downloaded bytes, completed ASR segments, translated lines, or prepared TTS lines.
        // Do not use Android's indeterminate notification animation as a fake progress display.
        val measuredPercent: Int? = when (state.phase) {
            Phase.DOWNLOADING_AUDIO -> percentFromProgress(state.progress)
            Phase.TRANSCRIBING -> ratioPercentFromProgress(state.progress)
                ?: percentFromProgress(state.progress)
            Phase.TRANSLATING -> ratioPercentFromProgress(state.progress)
                ?: if (state.totalLines > 0) {
                    (state.lines.count { !it.zh.isNullOrBlank() } * 100f / state.totalLines).toInt().coerceIn(0, 100)
                } else null
            Phase.SYNTHESIZING -> if (state.totalLines > 0) {
                (state.preparedUntil.coerceIn(0, state.totalLines) * 100f / state.totalLines).toInt().coerceIn(0, 100)
            } else ratioPercentFromProgress(state.progress)
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

    override fun onDestroy() {
        synchronized(sessionLock) { playbackSessionRevision += 1L }
        speakerRebuildRevision.incrementAndGet()
        speakerJob?.cancel()
        speakerRebuildJob?.cancel()
        runCatching { dubbing?.release() }
        runCatching { whisper?.close() }
        runCatching { session?.release() }
        if (::pm.isInitialized) runCatching { pm.release() }
        playerReady = false
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ============ 管线入口 ============
    fun open(
        key: String,
        videoId: String?,
        localUri: Uri?,
        importedLines: List<SubLine>? = null,
        resumeState: PlayerUiState? = null,
    ) {
        if (!playerReady || !::pm.isInitialized) {
            ui.update {
                it.copy(phase = Phase.ERROR, progress = "", error = it.error ?: "播放器服务未初始化成功，请返回主页后重试")
            }
            return
        }
        val sessionRevision = beginPlaybackSession()
        // Clear any error retained from the previous MediaItem before starting this session.
        if (::pm.isInitialized) pm.errorSink.value = null
        prepJob?.cancel()
        speakerJob?.cancel(); speakerJob = null
        speakerRebuildRevision.incrementAndGet()
        speakerRebuildJob?.cancel(); speakerRebuildJob = null
        dubCollectJob?.cancel(); dubCollectJob = null
        dubbing?.release(); dubbing = null
        this.videoKey = key; this.videoId = videoId; this.localUri = localUri
        this.importedLines = importedLines
        // Preserve parsed metadata/subtitles when resuming a translation failure.
        if (videoId != null && resumeState == null) NewPipeRepo.invalidate(videoId)
        qualityJob?.cancel()
        qualitySources = emptyList()
        qualityAudioUrl = ""
        lastLineIdx = Int.MIN_VALUE
        val configuredTts = getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "kokoro") ?: "kokoro"
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
            ttsEngineName = configuredTtsName,
            speakerRebuildInProgress = false,
        )
        pm.pause()
        prepJob = scope.launch { runPipeline(key, videoId, localUri, resumeState, sessionRevision) }
    }

    /** Wait until ExoPlayer confirms the selected stream is usable or reports a load error. */
    private suspend fun awaitPlayerReadyForSession(
        key: String,
        revision: Long,
        timeoutMs: Long = 20_000L,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val snapshot = withContext(Dispatchers.Main) {
                if (!isCurrentPlaybackSession(key, revision)) null
                else pm.player.playbackState to pm.player.playerError
            } ?: throw kotlinx.coroutines.CancellationException("播放会话已切换")
            val error = snapshot.second
            if (error != null) {
                throw IllegalStateException(error.message ?: "播放器加载该清晰度失败", error)
            }
            if (snapshot.first == androidx.media3.common.Player.STATE_READY) return
            kotlinx.coroutines.delay(100L)
        }
        throw IllegalStateException("播放器在 20 秒内未能开始读取该清晰度")
    }

    /** 切换清晰度时保留原播放位置；新流失败则尝试恢复之前正在播放的清晰度。 */
    fun selectQuality(label: String) {
        data class QualityRequest(
            val key: String,
            val revision: Long,
            val source: YouTubeQualitySource,
            val previousSource: YouTubeQualitySource?,
            val audioUrl: String,
            val title: String,
            val artist: String,
            val artworkUrl: String?,
        )
        // Keep the quality URL, audio URL and rollback choice from one session snapshot.
        val request = synchronized(sessionLock) {
            val key = videoKey
            val revision = playbackSessionRevision
            val source = qualitySources.firstOrNull { it.label == label }
            val previous = qualitySources.firstOrNull { it.label == ui.value.selectedQuality }
            if (key.isBlank() || ui.value.key != key || source == null ||
                source.label == ui.value.selectedQuality
            ) {
                null
            } else {
                QualityRequest(
                    key = key,
                    revision = revision,
                    source = source,
                    previousSource = previous,
                    audioUrl = qualityAudioUrl,
                    title = ui.value.title,
                    artist = ui.value.channel,
                    artworkUrl = ui.value.thumbUrl,
                )
            }
        } ?: return
        if (!::pm.isInitialized) return

        qualityJob?.cancel()
        qualityJob = scope.launch {
            var positionMs: Long? = null
            var wasPlaying: Boolean? = null
            try {
                if (!isCurrentPlaybackSession(request.key, request.revision)) return@launch
                positionMs = withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(request.key, request.revision)) pm.position() else null
                } ?: return@launch
                wasPlaying = withContext(Dispatchers.Main) {
                    // playWhenReady preserves the user's intent even if the current stream is buffering.
                    if (isCurrentPlaybackSession(request.key, request.revision)) pm.player.playWhenReady else null
                } ?: return@launch

                updateUiForSession(request.key, request.revision) {
                    it.copy(progress = "正在切换清晰度至 ${request.source.label}…", error = null)
                }
                val setupAccepted = withContext(Dispatchers.Main) {
                    if (!isCurrentPlaybackSession(request.key, request.revision)) false
                    else pm.setYouTubeQuality(
                        videoUrl = request.source.url,
                        audioUrl = request.audioUrl,
                        muxed = request.source.muxed,
                        title = request.title,
                        artist = request.artist,
                        artworkUrl = request.artworkUrl,
                    )
                }
                if (!isCurrentPlaybackSession(request.key, request.revision)) return@launch
                if (!setupAccepted) {
                    throw IllegalStateException(pm.errorSink.value ?: "所选清晰度无法初始化")
                }
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(request.key, request.revision)) {
                        pm.seekTo(positionMs!!)
                        if (wasPlaying == true) pm.play() else pm.pause()
                    }
                }
                awaitPlayerReadyForSession(request.key, request.revision)
                updateUiForSession(request.key, request.revision) {
                    it.copy(selectedQuality = request.source.label, progress = "", error = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentPlaybackSession(request.key, request.revision)) return@launch
                android.util.Log.e("PlaybackService", "Unable to switch YouTube quality", e)
                var recovered = false
                val previous = request.previousSource
                if (previous != null && positionMs != null && wasPlaying != null) {
                    try {
                        val rollbackAccepted = withContext(Dispatchers.Main) {
                            if (!isCurrentPlaybackSession(request.key, request.revision)) false
                            else pm.setYouTubeQuality(
                                videoUrl = previous.url,
                                audioUrl = request.audioUrl,
                                muxed = previous.muxed,
                                title = request.title,
                                artist = request.artist,
                                artworkUrl = request.artworkUrl,
                            )
                        }
                        if (rollbackAccepted && isCurrentPlaybackSession(request.key, request.revision)) {
                            withContext(Dispatchers.Main) {
                                if (isCurrentPlaybackSession(request.key, request.revision)) {
                                    pm.seekTo(positionMs!!)
                                    if (wasPlaying == true) pm.play() else pm.pause()
                                }
                            }
                            awaitPlayerReadyForSession(request.key, request.revision)
                            recovered = true
                        }
                    } catch (rollbackError: kotlinx.coroutines.CancellationException) {
                        throw rollbackError
                    } catch (rollbackError: Exception) {
                        android.util.Log.w("PlaybackService", "Unable to restore previous YouTube quality", rollbackError)
                    }
                }
                updateUiForSession(request.key, request.revision) {
                    it.copy(
                        phase = if (recovered) Phase.READY else Phase.ERROR,
                        progress = "",
                        selectedQuality = if (recovered) request.previousSource?.label ?: it.selectedQuality else it.selectedQuality,
                        error = if (recovered) {
                            "切换至 ${request.source.label} 失败，已恢复 ${request.previousSource?.label ?: "原清晰度"}。原因：${e.message ?: e.javaClass.simpleName}"
                        } else {
                            "切换清晰度失败：${e.message ?: e.javaClass.simpleName}"
                        },
                    )
                }
            }
        }
    }

    /** 将选中的 YouTube 字幕轨保存到用户指定位置；下载失败只更新提示，不打断播放任务。 */
    fun downloadSubtitle(track: SubtitleTrackOption, outputUri: Uri) {
        val expectedVideoKey = videoKey
        val expectedSessionRevision = playbackSessionRevision
        if (expectedVideoKey.isEmpty()) return
        fun publishSubtitleStatus(message: String) {
            ui.update { state ->
                if (isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision)) {
                    state.copy(subtitleTrackStatus = message)
                } else state
            }
        }
        scope.launch {
            publishSubtitleStatus("正在下载 " + track.languageTag + " 字幕…")
            try {
                val content = NewPipeRepo.downloadSubtitleContent(track)
                val cueCount = SubtitleParsers.parseAuto(content).size
                if (cueCount == 0) throw IllegalStateException("字幕文件没有可用的时间轴")
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(outputUri, "wt")
                        ?: throw IllegalStateException("无法写入所选文件")
                    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }
                publishSubtitleStatus(
                    "已保存 " + track.languageTag + " 字幕（" + cueCount +
                        " 条）。翻译时请保留时间戳，完成后可上传并直接配音。"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Subtitle download failed", e)
                publishSubtitleStatus("字幕下载失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * 导入已经翻译好的字幕：保留上传文件的时间轴，把译文作为配音文本，不再调用翻译引擎。
     * 支持 SRT/VTT/ASS；双语 cue 会优先取含中文的行作为译文。
     */
    fun importTranslatedSubtitles(uri: Uri) {
        val sessionRevision = beginPlaybackSession()
        val expectedVideoKey = videoKey
        val requestedMuteOriginal = ui.value.muteOriginal
        prepJob?.cancel()
        speakerJob?.cancel(); speakerJob = null
        speakerRebuildRevision.incrementAndGet()
        speakerRebuildJob?.cancel(); speakerRebuildJob = null
        if (::pm.isInitialized) pm.originalVolume = 1f
        ui.update {
            it.copy(
                phase = Phase.SYNTHESIZING,
                progress = "正在读取已翻译字幕…",
                lines = emptyList(),
                currentLine = -1,
                preparedUntil = 0,
                totalLines = 0,
                muteOriginal = false,
                speakerRebuildInProgress = false,
                error = null,
                noModelHint = null,
            )
        }
        dubCollectJob?.cancel()
        dubCollectJob = null
        dubbing?.release()
        dubbing = null
        lastLineIdx = Int.MIN_VALUE
        prepJob = scope.launch {
            if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取字幕文件")
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                val parsed = SubtitleParsers.parseAuto(text).filter { it.startMs >= 0L && it.endMs > it.startMs }
                if (parsed.isEmpty()) throw IllegalStateException("字幕文件为空、时间轴无效或格式不支持")
                val chinesePattern = Regex("[\\u4e00-\\u9fff]")
                val parsedTranslatedLines = parsed.mapIndexed { index, line ->
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
                }
                val translatedLines = applySavedSpeakerAssignments(expectedVideoKey, parsedTranslatedLines)
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                java.io.File(cacheDir, "dub/" + expectedVideoKey).deleteRecursively()
                ui.update {
                    it.copy(
                        phase = Phase.SYNTHESIZING,
                        progress = "已载入 " + translatedLines.size + " 条已翻译字幕，正在准备中文配音…",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        preparedUntil = 0,
                        sourceLang = "zh",
                        error = null,
                        noModelHint = null,
                        subtitleTrackStatus = "已导入已翻译字幕；此流程会跳过自动翻译。",
                    )
                }
                val tts = makeTts()
                val d = createAndInstallDubbingForSession(
                    tts = tts,
                    lines = translatedLines,
                    key = expectedVideoKey,
                    sessionRevision = sessionRevision,
                ) ?: return@launch
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { n ->
                        ui.update { state ->
                            if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision) &&
                                state.key == expectedVideoKey && dubbing === d
                            ) state.copy(preparedUntil = n) else state
                        }
                    }
                }
                lastLineIdx = Int.MIN_VALUE
                val firstSpeech = translatedLines.indexOfFirst { !it.zh.isNullOrBlank() }
                if (firstSpeech >= 0) {
                    val targetPrepared = minOf(translatedLines.size, firstSpeech + 2)
                    val deadlineNs = System.nanoTime() + 12_000_000_000L
                    while (d.preparedUntil.value < targetPrepared && System.nanoTime() < deadlineNs) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                        kotlinx.coroutines.delay(100)
                    }
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) pm.play()
                }
                ui.update { state ->
                    if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision) &&
                        state.key == expectedVideoKey && dubbing === d
                    ) state.copy(
                        phase = Phase.READY,
                        progress = "",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        preparedUntil = d.preparedUntil.value,
                        sourceLang = "zh",
                        muteOriginal = if (state.noModelHint == null) requestedMuteOriginal else false,
                    ) else state
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                android.util.Log.e("PlaybackService", "Import translated subtitles failed", e)
                withContext(Dispatchers.Main) { if (::pm.isInitialized) runCatching { pm.originalVolume = 1f } }
                ui.update {
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

    /** 导入外部字幕（SRT/VTT）：异步读取文件后翻译，避免大字幕文件阻塞播放页。 */
    fun importSubtitles(uri: Uri) {
        val sessionRevision = beginPlaybackSession()
        val expectedVideoKey = videoKey
        val requestedMuteOriginal = ui.value.muteOriginal
        prepJob?.cancel()
        speakerJob?.cancel(); speakerJob = null
        speakerRebuildRevision.incrementAndGet()
        speakerRebuildJob?.cancel(); speakerRebuildJob = null
        dubCollectJob?.cancel(); dubCollectJob = null
        dubbing?.release()
        dubbing = null
        lastLineIdx = Int.MIN_VALUE
        if (::pm.isInitialized) pm.originalVolume = 1f
        ui.update {
            it.copy(
                phase = Phase.TRANSLATING,
                progress = "正在读取导入字幕…",
                lines = emptyList(),
                currentLine = -1,
                preparedUntil = 0,
                totalLines = 0,
                muteOriginal = false,
                speakerRebuildInProgress = false,
                error = null,
                noModelHint = null,
            )
        }

        prepJob = scope.launch {
            if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取字幕文件")
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                val parsedLines = SubtitleParsers.parseAuto(text)
                if (parsedLines.isEmpty()) throw IllegalStateException("字幕文件为空或格式不支持")

                // Remove old cue audio before a different timeline is attached to the same video.
                java.io.File(cacheDir, "dub/$expectedVideoKey").deleteRecursively()
                ui.update { state ->
                    if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) {
                        state.copy(progress = "翻译导入的字幕（${parsedLines.size} 行）…")
                    } else state
                }
                val srcLang = detectLangFromText(parsedLines.firstOrNull()?.text ?: "")
                val rawTranslatedLines = if (isChineseLanguage(srcLang)) {
                    parsedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                } else {
                    translateLines(expectedVideoKey, parsedLines, srcLang, sessionRevision)
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                val translatedLines = applySavedSpeakerAssignments(expectedVideoKey, rawTranslatedLines)
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch

                val tts = makeTts()
                val d = createAndInstallDubbingForSession(
                    tts = tts,
                    lines = translatedLines,
                    key = expectedVideoKey,
                    sessionRevision = sessionRevision,
                ) ?: return@launch
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { count ->
                        ui.update { state ->
                            if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision) &&
                                state.key == expectedVideoKey && dubbing === d
                            ) state.copy(preparedUntil = count) else state
                        }
                    }
                }

                val arr = org.json.JSONArray()
                for (line in translatedLines) {
                    arr.put(
                        org.json.JSONObject()
                            .put("s", line.startMs)
                            .put("e", line.endMs)
                            .put("t", line.text)
                            .put("zh", line.zh ?: "")
                            .put("sp", line.speakerId)
                    )
                }
                if (translatedLines.count { !it.zh.isNullOrEmpty() } > translatedLines.size / 2) {
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            "$expectedVideoKey|zh|${getSharedPreferences("settings", MODE_PRIVATE).getLong("active_api_id", -1L)}",
                            srcLang,
                            arr.toString(),
                            System.currentTimeMillis(),
                        )
                    )
                }

                ui.update { state ->
                    if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision) && dubbing === d) {
                        state.copy(
                            lines = translatedLines,
                            totalLines = translatedLines.size,
                            preparedUntil = d.preparedUntil.value,
                            phase = Phase.SYNTHESIZING,
                            progress = "字幕已就绪，正在准备配音…",
                            sourceLang = srcLang,
                            error = null,
                        )
                    } else state
                }
                val firstSpeech = translatedLines.indexOfFirst { !it.zh.isNullOrBlank() }
                if (firstSpeech >= 0) {
                    val targetPrepared = minOf(translatedLines.size, firstSpeech + 2)
                    val deadlineNs = System.nanoTime() + 12_000_000_000L
                    while (d.preparedUntil.value < targetPrepared && System.nanoTime() < deadlineNs) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                        kotlinx.coroutines.delay(100)
                    }
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                ui.update { state ->
                    if (isCurrentPlaybackSession(expectedVideoKey, sessionRevision) && dubbing === d) {
                        state.copy(
                            phase = Phase.READY,
                            progress = "",
                            muteOriginal = if (state.noModelHint == null) requestedMuteOriginal else false,
                        )
                    } else state
                }
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentPlaybackSession(expectedVideoKey, sessionRevision)) return@launch
                android.util.Log.e("PlaybackService", "Subtitle import failed", e)
                withContext(Dispatchers.Main) { if (::pm.isInitialized) pm.originalVolume = 1f }
                ui.update {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "字幕导入失败：${e.message ?: e.javaClass.simpleName}",
                        muteOriginal = false,
                    )
                }
            }
        }
    }
    /** 从首行文本猜语言（仅含 CJK 判 zh，否则 en） */
    private fun detectLangFromText(t: String): String =
        if (Regex("[\\u4e00-\\u9fff]").containsMatchIn(t)) "zh" else "en"

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
        sessionRevision: Long,
    ) {
        // 记录仅供转写使用的临时网络音频，任何异常/取消路径都清理。
        if (!isCurrentPlaybackSession(key, sessionRevision)) return
        var transientAudio: File? = null
        try {
            var meta = VideoEntity(
                key = key, videoId = videoId, localUri = localUri?.toString(),
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
                updateUiForSession(key, sessionRevision) {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "继续翻译尚未完成的字幕…",
                        sourceLang = srcLang,
                        error = null,
                    )
                }
            } else if (videoId != null) {
                val m = NewPipeRepo.resolve(videoId)
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                meta = meta.copy(title = m.title, channel = m.channel, durationSec = m.durationSec, thumbUrl = m.thumbUrl)
                updateUiForSession(key, sessionRevision) { it.copy(title = m.title, channel = m.channel, thumbUrl = m.thumbUrl, durationMs = m.durationSec * 1000) }
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
                    updateUiForSession(key, sessionRevision) { it.copy(title = title, durationMs = dur) }
                }
            }

            // 解析视频时先列出所有字幕轨。下载是可选操作，不影响自动翻译/配音流程。
            if (videoId != null && resumeState == null) {
                try {
                    updateUiForSession(key, sessionRevision) { it.copy(progress = "正在查找可用字幕轨…") }
                    val tracks = NewPipeRepo.subtitleTracks(videoId)
                    if (!isCurrentPlaybackSession(key, sessionRevision)) return
                    updateUiForSession(key, sessionRevision) {
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
                    if (!isCurrentPlaybackSession(key, sessionRevision)) return
                    android.util.Log.w("PlaybackService", "Could not list subtitle tracks", e)
                    updateUiForSession(key, sessionRevision) {
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
            val translationCacheKey = "$key|zh|$selectedTranslationId"
            val sourceSubtitleCacheKey = "$key|source|youtube"
            // 首次处理明确显示字幕读取阶段；恢复翻译时保留翻译阶段，不伪装成重新解析。
            if (resumeState == null) {
                updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.PARSING, progress = "正在检查本地字幕文件与缓存…") }
            } else {
                updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.TRANSLATING, progress = "继续翻译尚未完成的字幕…") }
            }
            // 先读本地缓存，再决定是否请求 YouTube 字幕，避免重试重复下载。
            val storedTranslationCache = if (resumeState == null) db.subtitleCacheDao().get(translationCacheKey) else null
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            // Older builds may have persisted a progress placeholder (for example, "正在翻译")
            // as every row's Chinese text. Never trust such cache entries: retranslate from the
            // cached original subtitles instead of displaying the placeholder as completed output.
            val cached = storedTranslationCache?.takeIf { isUsableTranslationCache(it.linesJson) }
            if (storedTranslationCache != null && cached == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring invalid/stale translation cache for $key; will translate the source subtitles again"
                )
            }
            val sourceCached = if (resumeState == null && cached == null && videoId != null && importedLines.isNullOrEmpty()) {
                db.subtitleCacheDao().get(sourceSubtitleCacheKey)
            } else null
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            var lines: List<SubLine>? = resumeState?.lines?.takeIf { it.isNotEmpty() }
            if (resumeState == null && cached == null && importedLines.isNullOrEmpty() && videoId != null) {
                if (sourceCached != null) {
                    val sourceArr = org.json.JSONArray(sourceCached.linesJson)
                    lines = (0 until sourceArr.length()).map { i ->
                        val o = sourceArr.getJSONObject(i)
                        SubLine(i, o.getLong("s"), o.getLong("e"), o.getString("t"))
                    }
                    srcLang = sourceCached.srcLang
                    android.util.Log.i("PlaybackService", "Using cached source subtitles for $videoId (${lines!!.size} cues)")
                } else {
                    updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.PARSING, progress = "正在下载并读取字幕文件…") }
                    val fetchedSubtitles = NewPipeRepo.subtitles(videoId)
                    if (!isCurrentPlaybackSession(key, sessionRevision)) return
                    fetchedSubtitles?.let { (fetchedLines, lang) ->
                        lines = fetchedLines
                        srcLang = lang
                        // 缓存原始字幕独立于翻译服务，API 切换或翻译失败后也不必重新下载。
                        val sourceArr = org.json.JSONArray()
                        for (line in fetchedLines) {
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
                    }
                }
            }

            if (lines == null && cached == null) {
                if (!io.translatetube.local.data.ModelFiles.asrReady(this)) {
                    // 方案 B：转写模型未下载——优雅降级（原声播放 + 引导提示），不崩
                    updateUiForSession(key, sessionRevision) { it.copy(noModelHint = "该视频无字幕。下载「转写模型」后即可生成中文字幕与配音（设置 → 语音模型）") }
                } else {
                // ---- 2b. 无字幕 → 本地转写 ----
                updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "无字幕，准备语音转写…") }
                val audioFile = if (videoId != null) {
                    val audioStream = NewPipeRepo.audioForAsr(videoId)
                    if (!isCurrentPlaybackSession(key, sessionRevision)) return
                    downloadAudio(
                        audioStream?.content ?: throw IllegalStateException("无法获取音频流"),
                        key,
                        sessionRevision,
                    ).also { transientAudio = it }
                } else {
                    localUri ?: throw IllegalStateException("无音频源")
                }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.TRANSCRIBING, progress = "解码音频…") }
                val pcm = withContext(Dispatchers.IO) {
                    if (audioFile is File) AudioExtract.decodeTo16kMono(this@PlaybackService, null, audioFile)
                    else AudioExtract.decodeTo16kMono(this@PlaybackService, audioFile as Uri, null)
                }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                if (whisper == null) whisper = WhisperEngine(this)
                updateUiForSession(key, sessionRevision) { it.copy(progress = "语音分段…") }
                val segs = withContext(Dispatchers.IO) { vadSegments(this@PlaybackService, pcm.samples) }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                if (segs.isEmpty()) throw IllegalStateException("未检测到语音内容")
                val asrLines = withContext(Dispatchers.IO) {
                    whisper!!.transcribeBySegments(pcm.samples, segs) { done, total ->
                        updateUiForSession(key, sessionRevision) { it.copy(progress = "转写中 $done/$total 段") }
                    }
                }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                // 转写完成：释放临时音频文件
                if (audioFile is File) {
                    audioFile.delete()
                    if (transientAudio == audioFile) transientAudio = null
                }
                lines = asrLines.mapIndexed { i, (a, b, text) -> SubLine(i, a, b, text) }
                srcLang = whisper?.lastLang?.takeIf { it.isNotBlank() } ?: "en"
                }
            }

            // 用户导入的字幕优先（SRT/VTT 导入入口）
            if (importedLines != null && importedLines!!.isNotEmpty()) {
                lines = importedLines
                srcLang = detectLangFromText(importedLines!!.first().text)
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
                    updateUiForSession(key, sessionRevision) {
                        it.copy(
                            phase = Phase.TRANSLATING,
                            sourceLang = srcLang,
                            progress = "继续翻译缓存中尚未完成的字幕…",
                        )
                    }
                    lines = translateLines(key, restored, srcLang, sessionRevision)
                }
            } else if (lines != null) {
                // ---- 3. 翻译 ----
                var translatedLines = lines!!
                if (translatedLines.isNotEmpty() && !isChineseLanguage(srcLang)) {
                    updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.TRANSLATING, progress = "字幕已读取，准备翻译（${translatedLines.size} 行）…") }
                    translatedLines = translateLines(key, translatedLines, srcLang, sessionRevision)
                } else if (translatedLines.isNotEmpty()) {
                    // Already-Chinese source subtitles are the Chinese display track; publish immutable copies.
                    translatedLines = translatedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                }
                lines = translatedLines
                // 仅在译文填充率 >50% 时写缓存（避免失败结果被永久缓存）
                if (translatedLines.count { !it.zh.isNullOrEmpty() } > translatedLines.size / 2) {
                    val arr = org.json.JSONArray()
                    for (l in translatedLines) arr.put(
                        org.json.JSONObject().put("s", l.startMs).put("e", l.endMs).put("t", l.text).put("zh", l.zh ?: "").put("sp", l.speakerId)
                    )
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(translationCacheKey, srcLang, arr.toString(), System.currentTimeMillis())
                    )
                }
            }

            var finalLines = lines ?: emptyList()
            finalLines = applySavedSpeakerAssignments(key, finalLines)
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            updateUiForSession(key, sessionRevision) { state ->
                if (isCurrentPlaybackSession(key, sessionRevision)) {
                    state.copy(
                        lines = finalLines,
                        totalLines = finalLines.size,
                        sourceLang = srcLang,
                        progress = "",
                        phase = Phase.SYNTHESIZING,
                        // Without any usable subtitle cues there is no dubbing track to replace
                        // the source audio, so start in normal-audio mode rather than silent mode.
                        muteOriginal = if (finalLines.isEmpty()) false else state.muteOriginal,
                    )
                } else state
            }

            // ---- 4. 流装配 ----
            if (videoId != null) {
                val info = NewPipeRepo.streams(videoId)
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                val inventory = buildYouTubeQualityInventory(info)
                val candidates = inventory.candidates
                val audioUrl = inventory.audioUrl
                android.util.Log.i(
                    "PlaybackService",
                    "YouTube audio tracks=" + info.audioStreams.size +
                        ", usingMuxedFallback=" + (audioUrl.isBlank() && candidates.any { it.muxed })
                )
                android.util.Log.i("PlaybackService", "YouTube selectable qualities=" + candidates.map { it.label })
                val qualityInstalled = synchronized(sessionLock) {
                    if (playbackSessionRevision == sessionRevision && videoKey == key && ui.value.key == key) {
                        qualitySources = candidates
                        qualityAudioUrl = audioUrl
                        true
                    } else false
                }
                if (!qualityInstalled) return
                // Prefer <=720p initially; user can choose 1080p or higher from the menu.
                val defaultQuality = candidates.firstOrNull { it.height == 360 } ?: candidates.minByOrNull { kotlin.math.abs(it.height - 360) } ?: candidates.first()
                updateUiForSession(key, sessionRevision) {
                    it.copy(
                        qualityOptions = candidates.map { q -> q.label },
                        selectedQuality = defaultQuality.label,
                    )
                }
                val initialSourceAccepted = withContext(Dispatchers.Main) {
                    if (!isCurrentPlaybackSession(key, sessionRevision)) false
                    else pm.setYouTubeQuality(
                        videoUrl = defaultQuality.url,
                        audioUrl = audioUrl,
                        muxed = defaultQuality.muxed,
                        title = meta.title,
                        artist = meta.channel,
                        artworkUrl = meta.thumbUrl,
                    )
                }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
                if (!initialSourceAccepted) {
                    throw IllegalStateException(pm.errorSink.value ?: "YouTube 默认清晰度无法初始化")
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) pm.setUriSource(localUri!!, meta.title, "本地视频", null)
                }
                if (!isCurrentPlaybackSession(key, sessionRevision)) return
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
                updateUiForSession(key, sessionRevision) { it.copy(ttsEngineName = selectedTtsName) }

                val selectedEngineReady = when (selectedTts) {
                    "system" -> true
                    "kokoro" -> io.translatetube.local.data.ModelFiles.ttsReady(this)
                    else -> false
                }
                var ttsCandidate: TtsEngine? = null
                var dubbingCandidate: DubbingEngine? = null
                var candidateOwnsTts = false
                if (selectedEngineReady) {
                    try {
                        // Pass the saved selection explicitly so a settings change during parsing
                        // cannot cause the readiness check and actual engine to disagree.
                        val tts: TtsEngine = makeTts(selectedTts)
                        ttsCandidate = tts
                        val d = DubbingEngine(this, key, scope)
                        dubbingCandidate = d
                        registerDubbingFailureHandler(d, tts, key, sessionRevision)
                        if (!isCurrentPlaybackSession(key, sessionRevision)) {
                            runCatching { tts.close() }
                            ttsCandidate = null
                            runCatching { d.release() }
                            dubbingCandidate = null
                            return
                        }
                        updateUiForSession(key, sessionRevision) {
                            it.copy(ttsEngineName = tts.name, noModelHint = null)
                        }
                        // attach() assigns the TTS engine to DubbingEngine before starting work,
                        // so from this point release() owns closing the TTS instance.
                        candidateOwnsTts = true
                        d.attach(tts, finalLines)
                        if (!installDubbingForSession(key, sessionRevision, d)) {
                            runCatching { d.release() }
                            ttsCandidate = null
                            dubbingCandidate = null
                            candidateOwnsTts = false
                            return
                        }
                        dubCollectJob = scope.launch {
                            d.preparedUntil.collect { n ->
                                updateUiForSession(key, sessionRevision) { state ->
                                    if (state.key == key && isCurrentPlaybackSession(key, sessionRevision) && dubbing === d) {
                                        state.copy(preparedUntil = n)
                                    } else state
                                }
                            }
                        }
                        // The service now owns the installed engine; local cleanup must not release it.
                        ttsCandidate = null
                        dubbingCandidate = null
                        candidateOwnsTts = false
                    } catch (e: Exception) {
                        val failedCandidate = dubbingCandidate
                        if (failedCandidate != null) {
                            if (dubbing === failedCandidate) dubbing = null
                            runCatching { failedCandidate.release() }
                            if (!candidateOwnsTts) runCatching { ttsCandidate?.close() }
                        } else {
                            runCatching { ttsCandidate?.close() }
                        }
                        ttsCandidate = null
                        dubbingCandidate = null
                        candidateOwnsTts = false
                        if (!isCurrentPlaybackSession(key, sessionRevision)) return
                        android.util.Log.e("PlaybackService", "Unable to initialize selected TTS engine $selectedTts", e)
                        val reason = e.message ?: e.javaClass.simpleName
                        updateUiForSession(key, sessionRevision) {
                            it.copy(
                                ttsEngineName = selectedTtsName,
                                muteOriginal = false,
                                noModelHint = if (selectedTts == "system")
                                    "已选择系统 TTS，但系统配音引擎初始化失败：$reason。不会自动切换到 Kokoro。请检查手机的文字转语音服务和中文语音数据。"
                                else
                                    "已选择 Kokoro 模型，但初始化失败：$reason。不会自动切换到系统 TTS。请检查配音模型文件。",
                                error = null
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (isCurrentPlaybackSession(key, sessionRevision)) pm.originalVolume = 1f
                        }
                    }
                } else {
                    if (!isCurrentPlaybackSession(key, sessionRevision)) return
                    val message = when (selectedTts) {
                        "kokoro" -> "已选择 Kokoro 配音模型，但模型尚未就绪。应用不会自动切换到系统 TTS；下载完整的配音模型后再试。"
                        else -> "设置中保存的配音引擎「$selectedTts」无效。请重新选择 Kokoro 或系统 TTS。应用不会擅自切换引擎。"
                    }
                    updateUiForSession(key, sessionRevision) {
                        it.copy(ttsEngineName = if (selectedTts == "kokoro") "Kokoro（未就绪）" else selectedTtsName,
                            muteOriginal = false, noModelHint = message)
                    }
                    withContext(Dispatchers.Main) {
                        if (isCurrentPlaybackSession(key, sessionRevision)) pm.originalVolume = 1f
                    }
                }
            }
            // TTS 在后台按字幕顺序预合成。预热最前面的可朗读行后再启动视频，
            // 避免原声已被静音、系统 TTS 却还没合成出第一句时出现长时间无声。
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            val startupDubbing = dubbing
            if (startupDubbing != null) {
                val firstSpeech = finalLines.indexOfFirst { !it.zh.isNullOrBlank() }
                if (firstSpeech >= 0) {
                    val targetPrepared = minOf(finalLines.size, firstSpeech + 2)
                    val deadlineNs = System.nanoTime() + 12_000_000_000L
                    while (startupDubbing.preparedUntil.value < targetPrepared && System.nanoTime() < deadlineNs) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!isCurrentPlaybackSession(key, sessionRevision)) return
                        kotlinx.coroutines.delay(100)
                    }
                    if (startupDubbing.preparedUntil.value <= firstSpeech) {
                        android.util.Log.w("PlaybackService", "TTS startup buffer timed out before first spoken line")
                        updateUiForSession(key, sessionRevision) { it.copy(progress = "配音仍在准备，已先开始视频播放") }
                    }
                }
            }
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            saveHistory(meta)
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            updateUiForSession(key, sessionRevision) { state ->
                if (state.key == key && isCurrentPlaybackSession(key, sessionRevision)) {
                    state.copy(phase = Phase.READY, engineName = state.engineName.ifEmpty { "本地引擎" })
                } else state
            }
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            // 续播：上次观看超过 10 秒且距结尾超 30 秒则跳到上次位置
            val resumeSec = try {
                db.videoDao().byKey(key)?.watchedSec ?: 0L
            } catch (_: Exception) { 0L }
            val totalMs = meta.durationSec * 1000
            if (resumeSec > 10 && (totalMs <= 0 || resumeSec * 1000 < totalMs - 30_000)) {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) {
                        pm.seekTo(resumeSec * 1000)
                        pm.play()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) pm.play()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            android.util.Log.e("PlaybackService", "Video pipeline failed for key=$key", e)
            if (e is TranslationFailureException) {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) runCatching { pm.originalVolume = 1f }
                }
                updateUiForSession(key, sessionRevision) {
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
            val currentSessionDubbingOn = ui.value.let { state ->
                state.key == key && isCurrentPlaybackSession(key, sessionRevision) && state.dubbingOn
            }
            if (currentSessionDubbingOn) {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) runCatching { pm.originalVolume = 1f }
                }
                updateUiForSession(key, sessionRevision) {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "视频解析或配音初始化失败",
                        muteOriginal = false,
                        noModelHint = "配音初始化失败，已恢复视频原声。系统 TTS 请检查手机文字转语音服务/中文语音数据；Kokoro 请检查模型文件是否完整。"
                    )
                }
            } else {
                updateUiForSession(key, sessionRevision) { it.copy(phase = Phase.ERROR, progress = "", error = e.message ?: "视频解析失败") }
            }
            }
        } catch (e: Throwable) {
            // A non-fatal Error (for example, an extractor/library linkage error) must be shown
            // as a playback failure rather than escaping a Dispatchers.Default coroutine.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            if (!isCurrentPlaybackSession(key, sessionRevision)) return
            android.util.Log.e("PlaybackService", "Fatal-looking non-VM error in video pipeline for key=$key", e)
            val currentSessionDubbingOn = ui.value.let { state ->
                state.key == key && isCurrentPlaybackSession(key, sessionRevision) && state.dubbingOn
            }
            if (currentSessionDubbingOn) {
                withContext(Dispatchers.Main) {
                    if (isCurrentPlaybackSession(key, sessionRevision)) runCatching { pm.originalVolume = 1f }
                }
            }
            updateUiForSession(key, sessionRevision) {
                it.copy(
                    phase = Phase.ERROR,
                    progress = "",
                    error = "视频解析/播放器异常（${e.javaClass.simpleName}）：${e.message ?: "请查看崩溃日志"}",
                    muteOriginal = if (it.dubbingOn) false else it.muteOriginal,
                    noModelHint = if (it.dubbingOn) "处理过程中出现异常，已优先恢复视频原声；请根据上方错误检查当前视频或配音引擎。" else it.noModelHint,
                )
            }
        } finally {
            transientAudio?.delete()
        }
    }

    /** 按活跃引擎翻译，失败自动降级：API→本地→原文 */
    /** 按小批次翻译；每批完成就写入真实进度，避免整批请求期间进度停滞。 */
    private suspend fun translateLines(
        key: String,
        lines: List<SubLine>,
        srcLang: String,
        sessionRevision: Long,
    ): List<SubLine> {
        fun requireCurrentSession() {
            if (!isCurrentPlaybackSession(key, sessionRevision)) {
                throw kotlinx.coroutines.CancellationException("Playback session changed during translation")
            }
        }
        requireCurrentSession()
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
        requireCurrentSession()
        val pendingIndices = lines.indices.filter { index ->
            lines[index].text.isNotBlank() && SubtitleParsers.cleanTranslatedText(lines[index].zh).isNullOrBlank()
        }
        var completed = lines.indices.count { index ->
            lines[index].text.isBlank() || !SubtitleParsers.cleanTranslatedText(lines[index].zh).isNullOrBlank()
        }
        updateUiForSession(key, sessionRevision) {
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
        val translationCacheKey = "${key}|zh|${activeId}"
        for (batchIndices in pendingIndices.chunked(batchSize)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requireCurrentSession()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val batch = batchIndices.map { plain[it] }
            var translated: List<String>? = null
            try {
                translated = engine!!.translate(batch, src, tgt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("PlaybackService", "Translation batch failed for rows ${batchIndices.firstOrNull()}..${batchIndices.lastOrNull()}", e)
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

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requireCurrentSession()
            val batchTranslations = translated
            if (batchTranslations != null) {
                val cleanedBatch = batchTranslations.map { SubtitleParsers.cleanTranslatedText(it) }
                // A provider returning the UI status text instead of an actual translation must
                // not produce a READY player with every subtitle saying "正在翻译".
                if (batchTranslations.isNotEmpty() && cleanedBatch.none { !it.isNullOrBlank() }) {
                    throw TranslationFailureException(
                        "翻译模型响应中没有可识别的逐行译文。请在 API 配置中点击“测试连接”，确认当前模型支持文本生成；也可以尝试重新拉取模型或更换模型。",
                        IllegalStateException("translation response contained no usable subtitle text"),
                    )
                }
                updatedLines = updatedLines.mapIndexed { index, line ->
                    val batchPosition = batchIndices.indexOf(index)
                    if (batchPosition >= 0) line.copy(zh = cleanedBatch.getOrNull(batchPosition)) else line
                }
            }
            completed = updatedLines.indices.count { index ->
                updatedLines[index].text.isBlank() ||
                    !SubtitleParsers.cleanTranslatedText(updatedLines[index].zh).isNullOrBlank()
            }
            updateUiForSession(key, sessionRevision) {
                it.copy(
                    lines = updatedLines,
                    totalLines = updatedLines.size,
                    sourceLang = srcLang,
                    progress = "翻译中 ${completed.coerceAtMost(updatedLines.size)}/${updatedLines.size} 行",
                )
            }
            // Persist a checkpoint after each batch; retry keeps successful translations and fills only gaps.
            requireCurrentSession()
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

    private suspend fun downloadAudio(
        url: String,
        sessionKey: String,
        sessionRevision: Long,
    ): File {
        val f = File(cacheDir, "asr_audio_${System.nanoTime()}.bin")
        val maxBytes = 512L * 1024 * 1024
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0")
            .build()
        try {
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("音频下载失败 ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("音频下载响应为空")
                    val total = body.contentLength()
                    if (total > maxBytes) throw IllegalStateException("音频文件超过 512 MB 限制，已停止下载以保护设备存储")
                    body.byteStream().use { ins ->
                        f.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val n = ins.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > maxBytes) throw IllegalStateException("音频文件超过 512 MB 限制，已停止下载以保护设备存储")
                                out.write(buf, 0, n)
                                if (total > 0) {
                                    updateUiForSession(sessionKey, sessionRevision) {
                                        it.copy(progress = "下载音频 %.0f%%".format((got * 100f / total).coerceIn(0f, 100f)))
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                    if (!f.isFile || f.length() == 0L) throw IllegalStateException("音频下载结果为空")
                }
            }
            return f
        } catch (e: Exception) {
            f.delete()
            throw e
        }
    }

    private fun makeTts(engineId: String? = null): TtsEngine {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val selected = engineId ?: (prefs.getString("tts_engine", "kokoro") ?: "kokoro")
        return when (selected) {
            "system" -> io.translatetube.local.tts.SystemTts(this)
            "kokoro" -> {
                if (!io.translatetube.local.data.ModelFiles.ttsReady(this)) {
                    throw IllegalStateException("已选择 Kokoro，但配音模型尚未就绪")
                }
                KokoroTts(this, speakerId = prefs.getInt("kokoro_speaker", 14))
            }
            else -> throw IllegalStateException("未知配音引擎「$selected」，请在设置中重新选择")
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
            val activeLine = st.lines.getOrNull(dubLine)
            // Empty audio can also mean translation is missing, engine initialization failed,
            // or an earlier run cached an empty marker. Never keep the whole video silent.
            if (dubLine >= 0 && activeLine?.zh?.isNotBlank() == true && d.isSilent(dubLine)) {
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = 1f
                if (st.muteOriginal || st.noModelHint == null) {
                    ui.update { current ->
                        val configured = getSharedPreferences("settings", MODE_PRIVATE)
                            .getString("tts_engine", "kokoro")
                        val selectedName = current.ttsEngineName
                            .takeIf { it.isNotBlank() }
                            ?: st.ttsEngineName.takeIf { it.isNotBlank() }
                            ?: TtsEngineMessages.displayName(configured)
                        current.copy(
                            muteOriginal = false,
                            // Keep a more specific engine-construction/synthesis error if a callback
                            // published it after this tick captured its older PlayerUiState.
                            noModelHint = current.noModelHint
                                ?: TtsEngineMessages.partialFailure(selectedName),
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
                }
                pm.originalVolume = if (st.muteOriginal) 0f else 0.15f
            } else if (dubLine >= 0) {
                // While TTS is preparing this line, preserve source audio; mute it only when dubbing is ready.
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = 1f
            } else {
                // 字幕间隙不播放配音；按“原声静音”开关决定是否保留原音。
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = if (st.muteOriginal) 0f else 1f
            }
        } else {
            // During first preparation or a speaker rebuild, source audio is the intentional fallback.
            // Do not misreport this short-lived null engine as a TTS initialization failure.
            if (st.dubbingOn && st.lines.isNotEmpty() &&
                (st.phase == Phase.SYNTHESIZING || st.speakerRebuildInProgress)
            ) {
                pm.originalVolume = 1f
            } else if (st.dubbingOn && st.muteOriginal && st.lines.isNotEmpty()) {
                pm.originalVolume = 1f
                if (st.noModelHint == null) {
                    ui.update { current ->
                        val configured = getSharedPreferences("settings", MODE_PRIVATE)
                            .getString("tts_engine", "kokoro")
                        val selectedName = current.ttsEngineName
                            .takeIf { it.isNotBlank() }
                            ?: st.ttsEngineName.takeIf { it.isNotBlank() }
                            ?: TtsEngineMessages.displayName(configured)
                        current.copy(
                            muteOriginal = false,
                            // Do not overwrite an error already supplied by the selected engine.
                            noModelHint = current.noModelHint
                                ?: TtsEngineMessages.engineUnavailable(selectedName),
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
        if (speakerJob?.isActive == true || speakerRebuildJob?.isActive == true) return
        val configuredEngine = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("tts_engine", "kokoro") ?: "kokoro"
        if (configuredEngine != "kokoro") {
            ui.update { it.copy(error = "自动人物识别和逐句切换音色目前需要 Kokoro 多音色引擎；请先在设置中选择 Kokoro。") }
            return
        }
        if (!io.translatetube.local.data.ModelFiles.ttsReady(this)) {
            ui.update {
                it.copy(
                    error = null,
                    noModelHint = "自动人物识别需要 Kokoro 配音模型。请先到「设置 → 模型管理」下载并安装配音模型，再重新识别人物。",
                )
            }
            return
        }
        val originalLines = ui.value.lines
        val expectedVideoKey = videoKey
        val expectedSessionRevision = playbackSessionRevision
        val expectedVideoId = videoId
        val expectedLocalUri = localUri
        val recognitionRevision = speakerRebuildRevision.get()
        val preferredVoice = getSharedPreferences("settings", MODE_PRIVATE).getInt("kokoro_speaker", 14).coerceIn(0, 53)
        if (originalLines.isEmpty()) {
            ui.update { it.copy(error = "当前没有字幕，先生成或导入字幕再区分人物") }
            return
        }
        speakerJob = scope.launch {
            var tempAudio: File? = null
            try {
                ui.update { state ->
                    if (state.key == expectedVideoKey &&
                        isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) &&
                        expectedVideoKey == videoKey &&
                        recognitionRevision == speakerRebuildRevision.get()
                    ) {
                        state.copy(
                            error = null,
                            progress = "准备人物识别音频（为荣耀 10 限制为前 3 分钟）…",
                            phase = Phase.TRANSCRIBING,
                        )
                    } else state
                }
                val pcm = withContext(Dispatchers.IO) {
                    val sourceFile: File?
                    val sourceUri: Uri?
                    if (expectedVideoId != null) {
                        val stream = NewPipeRepo.audioForAsr(expectedVideoId)
                            ?: throw IllegalStateException("无法获取当前视频的音频流")
                        if (!isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision)) {
                            throw kotlinx.coroutines.CancellationException("Playback session changed during speaker audio download")
                        }
                        tempAudio = downloadAudio(stream.content, expectedVideoKey, expectedSessionRevision)
                        sourceFile = tempAudio
                        sourceUri = null
                    } else {
                        sourceFile = null
                        sourceUri = expectedLocalUri ?: throw IllegalStateException("找不到当前视频文件")
                    }
                    AudioExtract.decodeTo16kMono(this@PlaybackService, sourceUri, sourceFile, maxDurationMs = 3 * 60 * 1000L)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (expectedVideoKey != videoKey || !isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) ||
                    recognitionRevision != speakerRebuildRevision.get()) return@launch
                val segments = SpeakerDiarizer.diarize(this@PlaybackService, pcm.samples) { message ->
                    if (expectedVideoKey == videoKey && isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) &&
                        recognitionRevision == speakerRebuildRevision.get()) {
                        ui.update { state ->
                            if (state.key == expectedVideoKey) state.copy(progress = message) else state
                        }
                    }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (expectedVideoKey != videoKey || !isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) ||
                    recognitionRevision != speakerRebuildRevision.get()) return@launch
                if (segments.isEmpty()) throw IllegalStateException("没有识别到可区分的说话人")
                val mappingSegments = segments.map {
                    SpeakerVoiceMapping.Segment(
                    startSec = it.startSec,
                    endSec = it.endSec,
                    speaker = it.speaker,
                    // Some bundled sherpa builds omit confidence; a negative sentinel means
                    // "not provided", so preserve the segment rather than rejecting all speakers.
                    confidence = it.confidence.takeIf { value -> value.isFinite() && value in 0f..1f } ?: 1f,
                )
                }
                val updated = originalLines.map { line ->
                    line.copy(
                        speakerId = SpeakerVoiceMapping.voiceForCue(
                            startMs = line.startMs,
                            endMs = line.endMs,
                            segments = mappingSegments,
                            preferredDefaultVoice = preferredVoice,
                        )
                    )
                }
                val recognizedCount = segments.map { it.speaker }.distinct().size
                val distinctVoices = updated.map { it.speakerId }.distinct().size
                android.util.Log.i(
                    "PlaybackService",
                    "Speaker diarization mapped $recognizedCount diarized speakers to $distinctVoices Kokoro voices for $videoKey",
                )
                lastLineIdx = Int.MIN_VALUE
                if (expectedVideoKey != videoKey || !isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) ||
                    recognitionRevision != speakerRebuildRevision.get()) return@launch
                rebuildDubbingForSpeakerLines(
                    updated = updated,
                    expectedVideoKey = expectedVideoKey,
                    expectedPlaybackSessionRevision = expectedSessionRevision,
                    progressLabel = "人物识别完成，正在准备配音",
                    completedMessage = "人物区分完成：已识别 $recognizedCount 位说话人，使用 $distinctVoices 种音色",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (expectedVideoKey == videoKey && isCurrentPlaybackSession(expectedVideoKey, expectedSessionRevision) &&
                        recognitionRevision == speakerRebuildRevision.get()) {
                    ui.update { state ->
                        if (state.key == expectedVideoKey) state.copy(
                            phase = Phase.READY,
                            progress = "",
                            speakerRebuildInProgress = false,
                            error = "自动区分人物失败：" + (e.message ?: e.javaClass.simpleName),
                        ) else state
                    }
                }
            } finally {
                tempAudio?.delete()
            }
        }
    }

    fun setLineSpeaker(index: Int, speakerId: Int) {
        val selectedEngine = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("tts_engine", "kokoro") ?: "kokoro"
        if (selectedEngine != "kokoro") {
            ui.update { it.copy(error = "逐句切换人物音色需要 Kokoro 多音色引擎；系统 TTS 使用手机当前选定的语音。") }
            return
        }
        if (!io.translatetube.local.data.ModelFiles.ttsReady(this)) {
            ui.update {
                it.copy(
                    error = null,
                    noModelHint = "Kokoro 配音模型尚未就绪，暂时不能切换人物音色。请先在「设置 → 模型管理」完成下载。",
                )
            }
            return
        }
        if (!SpeakerVoiceMapping.isValidVoiceSelection(speakerId)) {
            ui.update { it.copy(error = "无效的配音音色编号，请重新选择。") }
            return
        }
        val expectedVideoKey = videoKey
        val expectedPlaybackSessionRevision = playbackSessionRevision
        if (!isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision)) return
        val oldLines = ui.value.lines
        if (index !in oldLines.indices) return
        if (oldLines[index].speakerId == speakerId) return
        if (speakerJob?.isActive == true) {
            ui.update { it.copy(error = "人物识别尚未完成，请完成后再切换音色。") }
            return
        }
        val updated = oldLines.mapIndexed { i, line ->
            if (i == index) line.copy(speakerId = speakerId) else line
        }
        rebuildDubbingForSpeakerLines(
            updated = updated,
            expectedVideoKey = expectedVideoKey,
            expectedPlaybackSessionRevision = expectedPlaybackSessionRevision,
            progressLabel = "正在更新人物音色",
            completedMessage = "人物音色已更新",
        )
    }

    /**
     * Rebuild selected-engine PCM without leaving the playback screen. During this short window
     * the source track remains audible, and a revision token prevents an older rebuild from
     * installing itself after a newer user selection or a different video has opened.
     */
    private fun rebuildDubbingForSpeakerLines(
        updated: List<SubLine>,
        expectedVideoKey: String,
        expectedPlaybackSessionRevision: Long,
        progressLabel: String,
        completedMessage: String,
    ): Job? {
        data class RebuildSetup(
            val revision: Int,
            val previousJob: Job?,
            val previousCollector: Job?,
            val previousDubbing: DubbingEngine?,
        )

        // Reserve the rebuild only while this exact playback session is still current. Taking
        // ownership of the previous engine under the session lock prevents a same-URL reopen from
        // accidentally having its new engine released by this older rebuild.
        val setup = synchronized(sessionLock) {
            if (playbackSessionRevision != expectedPlaybackSessionRevision ||
                videoKey != expectedVideoKey || ui.value.key != expectedVideoKey
            ) {
                null
            } else {
                val previousJob = speakerRebuildJob
                val previousCollector = dubCollectJob
                val previousDubbing = dubbing
                val revision = speakerRebuildRevision.incrementAndGet()
                speakerRebuildJob = null
                dubCollectJob = null
                dubbing = null
                RebuildSetup(revision, previousJob, previousCollector, previousDubbing)
            }
        } ?: return null

        val revision = setup.revision
        setup.previousJob?.cancel()
        setup.previousCollector?.cancel()
        runCatching { setup.previousDubbing?.release() }

        scope.launch(Dispatchers.Main) {
            if (isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                speakerRebuildRevision.get() == revision && ::pm.isInitialized
            ) {
                runCatching { pm.originalVolume = 1f }
            }
        }
        updateUiForSession(expectedVideoKey, expectedPlaybackSessionRevision) { state ->
            if (speakerRebuildRevision.get() == revision) {
                state.copy(
                    lines = updated,
                    totalLines = updated.size,
                    preparedUntil = 0,
                    currentLine = -1,
                    phase = Phase.READY,
                    progress = "$progressLabel 0/${updated.size} 行",
                    speakerRebuildInProgress = true,
                    noModelHint = null,
                    error = null,
                )
            } else state
        }
        if (isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
            speakerRebuildRevision.get() == revision
        ) {
            lastLineIdx = Int.MIN_VALUE
        }
        scope.launch(Dispatchers.Main) {
            if (::pm.isInitialized &&
                isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                speakerRebuildRevision.get() == revision
            ) {
                tick(pm.position())
            }
        }

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var candidate: DubbingEngine? = null
            try {
                if (!isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) ||
                    speakerRebuildRevision.get() != revision
                ) return@launch
                if (!io.translatetube.local.data.ModelFiles.ttsReady(this@PlaybackService)) {
                    throw IllegalStateException("Kokoro 配音模型尚未就绪")
                }
                try {
                    saveSpeakerAssignments(
                        lines = updated,
                        expectedVideoKey = expectedVideoKey,
                        expectedPlaybackSessionRevision = expectedPlaybackSessionRevision,
                        expectedSpeakerRevision = revision,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "Unable to persist speaker assignment", e)
                }
                if (!isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) ||
                    speakerRebuildRevision.get() != revision
                ) return@launch

                val created = DubbingEngine(this@PlaybackService, expectedVideoKey, scope)
                candidate = created
                val tts = makeTts("kokoro")
                created.onSynthesisError = { detail ->
                    ui.update { state ->
                        if (state.key == expectedVideoKey &&
                            isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                            speakerRebuildRevision.get() == revision
                        ) {
                            state.copy(
                                ttsEngineName = tts.name,
                                muteOriginal = false,
                                noModelHint = state.noModelHint
                                    ?: "当前选择的「${tts.name}」配音失败；应用未切换到其他引擎。$detail",
                            )
                        } else state
                    }
                    scope.launch(Dispatchers.Main) {
                        if (isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                            speakerRebuildRevision.get() == revision && ::pm.isInitialized
                        ) {
                            runCatching { pm.originalVolume = 1f }
                        }
                    }
                }
                created.attach(tts, updated)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                val installed = synchronized(sessionLock) {
                    if (playbackSessionRevision == expectedPlaybackSessionRevision &&
                        videoKey == expectedVideoKey && ui.value.key == expectedVideoKey &&
                        speakerRebuildRevision.get() == revision
                    ) {
                        dubbing = created
                        true
                    } else false
                }
                if (!installed) {
                    runCatching { created.release() }
                    candidate = null
                    return@launch
                }
                candidate = null

                val collector = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    created.preparedUntil.collect { count ->
                        ui.update { state ->
                            if (state.key != expectedVideoKey ||
                                !isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) ||
                                speakerRebuildRevision.get() != revision || dubbing !== created
                            ) {
                                state
                            } else {
                                val allPrepared = count >= updated.size
                                state.copy(
                                    preparedUntil = count,
                                    phase = Phase.READY,
                                    speakerRebuildInProgress = !allPrepared,
                                    progress = if (allPrepared) completedMessage
                                        else "$progressLabel $count/${updated.size} 行",
                                )
                            }
                        }
                    }
                }
                val keepCollector = synchronized(sessionLock) {
                    if (playbackSessionRevision == expectedPlaybackSessionRevision &&
                        videoKey == expectedVideoKey && ui.value.key == expectedVideoKey &&
                        speakerRebuildRevision.get() == revision && dubbing === created
                    ) {
                        dubCollectJob = collector
                        true
                    } else false
                }
                if (keepCollector) collector.start() else collector.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Speaker voice rebuild failed", e)
                if (isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                    speakerRebuildRevision.get() == revision
                ) {
                    withContext(Dispatchers.Main) {
                        if (isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) &&
                            speakerRebuildRevision.get() == revision && ::pm.isInitialized
                        ) {
                            runCatching { pm.originalVolume = 1f }
                        }
                    }
                    updateUiForSession(expectedVideoKey, expectedPlaybackSessionRevision) { state ->
                        if (speakerRebuildRevision.get() == revision) {
                            state.copy(
                                phase = Phase.READY,
                                progress = "",
                                speakerRebuildInProgress = false,
                                muteOriginal = false,
                                noModelHint = TtsEngineMessages.engineUnavailable("Kokoro"),
                                error = "重新生成配音失败：${e.message ?: e.javaClass.simpleName}",
                            )
                        } else state
                    }
                }
            } finally {
                candidate?.release()
            }
        }

        val registered = synchronized(sessionLock) {
            if (playbackSessionRevision == expectedPlaybackSessionRevision &&
                videoKey == expectedVideoKey && ui.value.key == expectedVideoKey &&
                speakerRebuildRevision.get() == revision
            ) {
                speakerRebuildJob = job
                true
            } else false
        }
        if (!registered) {
            job.cancel()
            return null
        }
        job.start()
        return job
    }

    private fun speakerAssignmentCacheKey(expectedVideoKey: String): String =
        expectedVideoKey + "|speaker-map-v1"

    private fun speakerCueIdentity(startMs: Long, endMs: Long, text: String): String =
        startMs.toString() + "|" + endMs + "|" + text.trim().replace(Regex("\\s+"), " ")

    /** Restore voice choices independently of the translation API/cache entry. */
    private suspend fun applySavedSpeakerAssignments(
        expectedVideoKey: String,
        lines: List<SubLine>,
    ): List<SubLine> {
        if (lines.isEmpty()) return lines
        return try {
            val cached = AppDatabase.get(this).subtitleCacheDao()
                .get(speakerAssignmentCacheKey(expectedVideoKey)) ?: return lines
            val rows = org.json.JSONArray(cached.linesJson)
            val assignments = HashMap<String, Int>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val startMs = row.optLong("s", Long.MIN_VALUE)
                val endMs = row.optLong("e", Long.MIN_VALUE)
                val original = row.optString("t", "")
                val voice = row.optInt("sp", -1)
                if (startMs >= 0L && endMs > startMs && SpeakerVoiceMapping.isValidVoiceSelection(voice)) {
                    assignments[speakerCueIdentity(startMs, endMs, original)] = voice
                }
            }
            if (assignments.isEmpty()) lines else lines.map { line ->
                assignments[speakerCueIdentity(line.startMs, line.endMs, line.text)]
                    ?.let { voice -> line.copy(speakerId = voice) }
                    ?: line
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Unable to restore per-video speaker assignments", e)
            lines
        }
    }

    private suspend fun saveSpeakerAssignments(
        lines: List<SubLine>,
        expectedVideoKey: String,
        expectedPlaybackSessionRevision: Long,
        expectedSpeakerRevision: Int,
    ) {
        if (expectedVideoKey != videoKey || lines.isEmpty() ||
            !isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) ||
            speakerRebuildRevision.get() != expectedSpeakerRevision
        ) return
        val sourceLang = ui.value.sourceLang
        val array = org.json.JSONArray()
        lines.forEach { line ->
            array.put(
                org.json.JSONObject()
                    .put("s", line.startMs)
                    .put("e", line.endMs)
                    .put("t", line.text)
                    .put("sp", if (SpeakerVoiceMapping.isValidVoiceSelection(line.speakerId)) line.speakerId else -1)
            )
        }
        if (!isCurrentPlaybackSession(expectedVideoKey, expectedPlaybackSessionRevision) ||
            speakerRebuildRevision.get() != expectedSpeakerRevision
        ) return
        AppDatabase.get(this).subtitleCacheDao().put(
            io.translatetube.local.data.SubtitleCacheEntity(
                speakerAssignmentCacheKey(expectedVideoKey),
                sourceLang,
                array.toString(),
                System.currentTimeMillis(),
            )
        )
    }

    fun toggleDub() {
        val current = ui.value
        val next = DubbingPlaybackPolicy.toggle(current.dubbingOn)
        ui.update {
            it.copy(
                dubbingOn = next.dubbingOn,
                // “关闭配音”意味着回到正常原声播放，不能继续沿用“配音时静音原声”。
                muteOriginal = next.muteOriginal,
            )
        }
        // Invalidate the previous voice slice, then recalculate using the current video timestamp.
        dubbing?.stop()
        lastLineIdx = Int.MIN_VALUE
        if (!next.dubbingOn) pm.originalVolume = 1f
        if (::pm.isInitialized) tick(pm.position())
    }
    fun toggleMute() {
        val nowMuted = !ui.value.muteOriginal
        ui.update { it.copy(muteOriginal = nowMuted) }
        pm.originalVolume = if (nowMuted) 0f else 1f
        lastLineIdx = Int.MIN_VALUE
        if (::pm.isInitialized) tick(pm.position())
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
        // 先停止旧时间点的配音，再 seek；seekTo 的即时 tick 可以直接启动新时间点的正确字幕行。
        dubbing?.stop()
        lastLineIdx = Int.MIN_VALUE
        pm.seekTo(ms)
    }
    fun playPause() { if (pm.isPlaying.value) pm.pause() else pm.play() }

    /** 记录观看进度（退出播放页时调用，历史列表续看用） */
    fun saveProgress() {
        val key = videoKey
        if (key.isEmpty() || !::pm.isInitialized) return
        scope.launch {
            try {
                // ExoPlayer belongs to the main application looper; capture its position there,
                // then persist history without touching the player from this background coroutine.
                val watchedSec = withContext(Dispatchers.Main) {
                    // Avoid saving the new video's position under the previous video's history key.
                    if (::pm.isInitialized && videoKey == key) pm.position() / 1000L else null
                } ?: return@launch
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                AppDatabase.get(this@PlaybackService).videoDao().setWatched(key, watchedSec)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Unable to persist watch progress for $key", e)
            }
        }
    }
    fun setSpeed(s: Float) { pm.speed(s) }
    fun position(): Long = pm.position()
    fun duration(): Long = pm.duration()

    override fun onTaskRemoved(rootIntent: Intent?) {
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
