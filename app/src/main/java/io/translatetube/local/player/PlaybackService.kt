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
    private var dubbing: DubbingEngine? = null
    private val retiringDubbingEngines = java.util.concurrent.CopyOnWriteArrayList<DubbingEngine>()
    private var whisper: WhisperEngine? = null

    private fun retireCurrentDubbing() {
        val previous = dubbing ?: return
        dubbing = null
        previous.release()
        retiringDubbingEngines.add(previous)
    }

    private suspend fun awaitRetiredDubbingEngines() {
        while (true) {
            val previous = retiringDubbingEngines.firstOrNull() ?: return
            try {
                previous.releaseAndJoin()
            } finally {
                retiringDubbingEngines.remove(previous)
            }
        }
    }
    private var prepJob: Job? = null
    private var videoKey = ""
    private var videoId: String? = null
    private var localUri: Uri? = null
    @Volatile private var qualitySources: List<YouTubeQualitySource> = emptyList()
    @Volatile private var qualityAudioUrl: String = ""
    @Volatile private var qualityRecoveryAttempted = false
    private var qualityRecoveryJob: Job? = null
    private var qualityJob: Job? = null
    private var importedLines: List<SubLine>? = null
    private var lastLineIdx = Int.MIN_VALUE
    private var dubCollectJob: Job? = null
    private var speakerJob: Job? = null
    private val asrAudioDownloadLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

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
                    (state.lines.count { SubtitleParsers.cleanTranslatedText(it.zh) != null } * 100f / state.totalLines).toInt().coerceIn(0, 100)
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
        prepJob?.cancel()
        speakerJob?.cancel(); speakerJob = null
        dubCollectJob?.cancel(); dubCollectJob = null
        qualityRecoveryJob?.cancel(); qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        retireCurrentDubbing()
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
        )
        pm.pause()
        prepJob = scope.launch { runPipeline(key, videoId, localUri, resumeState) }
    }

    /** 切换 YouTube 清晰度：保留当前播放位置与播放/暂停状态。 */
    fun selectQuality(label: String) {
        val source = qualitySources.firstOrNull { it.label == label } ?: return
        if (!::pm.isInitialized || source.label == ui.value.selectedQuality) return
        qualityRecoveryJob?.cancel(); qualityRecoveryJob = null
        qualityRecoveryAttempted = false
        qualityJob?.cancel()
        qualityJob = scope.launch {
            val positionMs = withContext(Dispatchers.Main) { pm.position() }
            val wasPlaying = withContext(Dispatchers.Main) { pm.player.isPlaying }
            ui.update { it.copy(progress = "正在切换清晰度至 ${source.label}…", error = null) }
            try {
                withContext(Dispatchers.Main) {
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
                    ui.update { it.copy(selectedQuality = source.label, progress = "") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Unable to switch YouTube quality", e)
                ui.update {
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
        scope.launch {
            ui.update { it.copy(subtitleTrackStatus = "正在下载 " + track.languageTag + " 字幕…") }
            try {
                val content = NewPipeRepo.downloadSubtitleContent(track)
                val cueCount = SubtitleParsers.parseAuto(content).size
                if (cueCount == 0) throw IllegalStateException("字幕文件没有可用的时间轴")
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(outputUri, "wt")
                        ?: throw IllegalStateException("无法写入所选文件")
                    output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }
                ui.update {
                    it.copy(
                        subtitleTrackStatus = "已保存 " + track.languageTag + " 字幕（" + cueCount +
                            " 条）。翻译时请保留时间戳，完成后可上传并直接配音。",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Subtitle download failed", e)
                ui.update {
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
        prepJob?.cancel()
        dubCollectJob?.cancel()
        dubCollectJob = null
        val previousDubbing = dubbing
        previousDubbing?.release()
        dubbing = null
        lastLineIdx = Int.MIN_VALUE
        prepJob = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取字幕文件")
                }
                val parsed = SubtitleParsers.parseAuto(text).filter { it.startMs >= 0L && it.endMs > it.startMs }
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
                pm.originalVolume = if (ui.value.muteOriginal) 0f else 1f
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
                previousDubbing?.releaseAndJoin()
                awaitRetiredDubbingEngines()
                val tts = makeTts()
                val d = DubbingEngine(this@PlaybackService, videoKey, scope)
                registerDubbingFailureHandler(d, tts)
                d.attach(tts, translatedLines)
                dubbing = d
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { n -> ui.update { it.copy(preparedUntil = n) } }
                }
                lastLineIdx = Int.MIN_VALUE
                withContext(Dispatchers.Main) { pm.play() }
                ui.update {
                    it.copy(
                        phase = Phase.READY,
                        progress = "",
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        preparedUntil = d.preparedUntil.value,
                        sourceLang = "zh",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Import translated subtitles failed", e)
                runCatching { pm.originalVolume = 1f }
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

    /** 导入外部字幕（SRT/VTT）：解析后重新走翻译+配音 */
    fun importSubtitles(uri: Uri) {
        prepJob?.cancel()
        dubCollectJob?.cancel()
        dubCollectJob = null
        // Stop the previous voice immediately, but wait for its engine to close before replacement.
        val previousDubbing = dubbing
        previousDubbing?.release()
        dubbing = null
        pm.originalVolume = if (ui.value.muteOriginal) 0f else 1f
        lastLineIdx = Int.MIN_VALUE

        prepJob = scope.launch {
            try {
                ui.update {
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
                }
                if (parsedLines.isEmpty()) {
                    throw IllegalStateException("字幕文件为空或格式不支持")
                }

                ui.update {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "翻译导入的字幕（${parsedLines.size} 行）…",
                    )
                }
                val srcLang = detectLangFromText(parsedLines.take(12).joinToString(" ") { it.text })
                val activeTranslationId = getSharedPreferences("settings", MODE_PRIVATE)
                    .getLong("active_api_id", -1L)
                val importedCacheKey = importedTranslationCacheKey(videoKey, activeTranslationId, parsedLines)
                // Use immutable copies so StateFlow/Compose sees translated subtitle text.
                val translatedLines = applySavedLineOverrides(
                    if (isChineseLanguage(srcLang)) {
                        parsedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                    } else {
                        translateLines(videoKey, parsedLines, srcLang, importedCacheKey)
                    }
                )
                previousDubbing?.releaseAndJoin()
                val tts = makeTts()
                val d = DubbingEngine(this@PlaybackService, videoKey, scope)
                registerDubbingFailureHandler(d, tts)
                d.attach(tts, translatedLines)
                dubbing = d
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { n -> ui.update { it.copy(preparedUntil = n) } }
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
                ui.update {
                    it.copy(
                        lines = translatedLines,
                        totalLines = translatedLines.size,
                        phase = Phase.READY,
                        progress = "",
                        error = null,
                    )
                }
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Subtitle import failed", e)
                ui.update {
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
    private fun importedTranslationCacheKey(key: String, activeId: Long, lines: List<SubLine>): String {
        val canonical = buildString {
            lines.forEach { line ->
                append(line.startMs).append(':')
                    .append(line.endMs).append(':')
                    .append(line.text.length).append(':')
                    .append(line.text).append('\n')
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val signature = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$key|zh|$activeId|imported-$signature"
    }

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
    ) {
        // 记录仅供转写使用的临时网络音频，任何异常/取消路径都清理。
        var transientAudio: File? = null
        try {
            awaitRetiredDubbingEngines()
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
                ui.update {
                    it.copy(
                        phase = Phase.TRANSLATING,
                        progress = "继续翻译尚未完成的字幕…",
                        sourceLang = srcLang,
                        error = null,
                    )
                }
            } else if (videoId != null) {
                val m = NewPipeRepo.resolve(videoId)
                meta = meta.copy(title = m.title, channel = m.channel, durationSec = m.durationSec, thumbUrl = m.thumbUrl)
                ui.update { it.copy(title = m.title, channel = m.channel, thumbUrl = m.thumbUrl, durationMs = m.durationSec * 1000) }
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
                    ui.update { it.copy(title = title, durationMs = dur) }
                }
            }

            // 解析视频时先列出所有字幕轨。下载是可选操作，不影响自动翻译/配音流程。
            if (videoId != null && resumeState == null) {
                try {
                    ui.update { it.copy(progress = "正在查找可用字幕轨…") }
                    val tracks = NewPipeRepo.subtitleTracks(videoId)
                    ui.update {
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
                    ui.update {
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
            val translationCacheKey = if (!importedLines.isNullOrEmpty()) {
                importedTranslationCacheKey(key, selectedTranslationId, importedLines)
            } else {
                "$key|zh|$selectedTranslationId"
            }
            val sourceSubtitleCacheKey = "$key|source|youtube"
            // 首次处理明确显示字幕读取阶段；恢复翻译时保留翻译阶段，不伪装成重新解析。
            if (resumeState == null) {
                ui.update { it.copy(phase = Phase.PARSING, progress = "正在检查本地字幕文件与缓存…") }
            } else {
                ui.update { it.copy(phase = Phase.TRANSLATING, progress = "继续翻译尚未完成的字幕…") }
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
            }
            suspend fun fetchAndCacheSourceLines(): Pair<List<SubLine>, String>? {
                val id = videoId ?: return null
                ui.update { it.copy(phase = Phase.PARSING, progress = "正在下载并读取字幕文件…") }
                val fetched = NewPipeRepo.subtitles(id) ?: return null
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
                return fetchedLines to lang
            }

            val sourceCached = if (
                resumeState == null && videoId != null && importedLines.isNullOrEmpty()
            ) {
                db.subtitleCacheDao().get(sourceSubtitleCacheKey)
            } else null
            val restoredSourceLines = sourceCached?.let { source ->
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
            if (sourceCached != null && restoredSourceLines == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring malformed source subtitle cache for $videoId; fetching subtitles/ASR again"
                )
                db.subtitleCacheDao().delete(sourceSubtitleCacheKey)
                // A legacy video-level translation cache cannot be proven to belong to this source.
                if (importedLines.isNullOrEmpty()) cached = null
            } else if (
                cached != null &&
                importedLines.isNullOrEmpty() &&
                restoredSourceLines != null &&
                !translationCacheMatchesSource(cached.linesJson, restoredSourceLines)
            ) {
                android.util.Log.w(
                    "PlaybackService",
                    "Ignoring translation cache for $videoId because it belongs to different source subtitle cues"
                )
                cached = null
            }

            var lines: List<SubLine>? = resumeState?.lines?.takeIf { it.isNotEmpty() }
            if (resumeState == null && cached == null && importedLines.isNullOrEmpty() && videoId != null) {
                if (restoredSourceLines != null && sourceCached != null) {
                    lines = restoredSourceLines
                    srcLang = sourceCached.srcLang.takeIf { it.isNotBlank() }
                        ?: detectLangFromText(restoredSourceLines.take(12).joinToString(" ") { it.text })
                    android.util.Log.i(
                        "PlaybackService",
                        "Using cached source subtitles for $videoId (${restoredSourceLines.size} cues)"
                    )
                } else {
                    fetchAndCacheSourceLines()?.let { (fetchedLines, lang) ->
                        lines = fetchedLines
                        srcLang = lang
                    }
                }
            }

            if (lines == null && cached == null) {
                if (!io.translatetube.local.data.ModelFiles.asrReady(this)) {
                    // 方案 B：转写模型未下载——优雅降级（原声播放 + 引导提示），不崩
                    ui.update { it.copy(noModelHint = "该视频无字幕。下载「转写模型」后即可生成中文字幕与配音（设置 → 语音模型）") }
                } else {
                // ---- 2b. 无字幕 → 本地转写 ----
                ui.update { it.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "无字幕，准备语音转写…") }
                val audioFile = if (videoId != null) {
                    obtainAsrAudioFile(videoId, "复用已下载音频，避免重复下载…")
                } else {
                    localUri ?: throw IllegalStateException("无音频源")
                }
                ui.update { it.copy(phase = Phase.TRANSCRIBING, progress = "解码音频…") }
                val pcm = withContext(Dispatchers.IO) {
                    if (audioFile is File) AudioExtract.decodeTo16kMono(this@PlaybackService, null, audioFile)
                    else AudioExtract.decodeTo16kMono(this@PlaybackService, audioFile as Uri, null)
                }
                if (whisper == null) whisper = WhisperEngine(this)
                ui.update { it.copy(progress = "语音分段…") }
                val segs = withContext(Dispatchers.IO) { vadSegments(this@PlaybackService, pcm.samples) }
                if (segs.isEmpty()) throw IllegalStateException("未检测到语音内容")
                val asrLines = withContext(Dispatchers.IO) {
                    whisper!!.transcribeBySegments(pcm.samples, segs) { done, total ->
                        ui.update { it.copy(progress = "转写中 $done/$total 段") }
                    }
                }
                // Only disposable local temporary files may be removed before the transcript checkpoint.
                // YouTube audio remains cached until its ASR subtitles have been saved successfully.
                if (audioFile is File && videoId == null) {
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
                    throw IllegalStateException(
                        if (videoId != null) {
                            "语音识别没有生成有效字幕；已保留下载的音频，重试时会复用缓存，不会再次下载。"
                        } else {
                            "语音识别没有生成有效字幕，请检查本地视频是否包含可识别语音。"
                        }
                    )
                }
                lines = transcribedLines
                srcLang = whisper?.lastLang?.takeIf { it.isNotBlank() } ?: "en"

                // Keep the completed ASR transcript as an independent checkpoint. The bounded
                // source-audio cache is retained for retries and later speaker diarization, while
                // this subtitle cache lets translation retry without running ASR again.
                if (videoId != null && importedLines.isNullOrEmpty() && transcribedLines.isNotEmpty()) {
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
            if (importedLines != null && importedLines!!.isNotEmpty()) {
                lines = importedLines
                srcLang = detectLangFromText(importedLines!!.take(12).joinToString(" ") { it.text })
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
                    ui.update {
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
                    ui.update { it.copy(phase = Phase.TRANSLATING, progress = "字幕已读取，准备翻译（${translatedLines.size} 行）…") }
                    translatedLines = translateLines(key, translatedLines, srcLang, translationCacheKey)
                } else if (translatedLines.isNotEmpty()) {
                    // Already-Chinese source subtitles are the Chinese display track; publish immutable copies.
                    translatedLines = translatedLines.map { it.copy(zh = SubtitleParsers.cleanTranslatedText(it.text)) }
                }
                lines = translatedLines
                // 仅在译文填充率 >50% 时写缓存（避免失败结果被永久缓存）
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

            val finalLines = applySavedLineOverrides(lines ?: emptyList(), key)
            ui.update {
                it.copy(
                    lines = finalLines, totalLines = finalLines.size, sourceLang = srcLang,
                    progress = "", phase = Phase.SYNTHESIZING,
                )
            }

            // ---- 4. 流装配 ----
            if (videoId != null) {
                val choices = buildYouTubeStreamChoices(NewPipeRepo.streams(videoId))
                val candidates = choices.sources
                val audioUrl = choices.audioUrl
                val defaultQuality = choices.defaultQuality
                qualitySources = candidates
                qualityAudioUrl = audioUrl
                ui.update {
                    it.copy(
                        qualityOptions = candidates.map { q -> q.label },
                        selectedQuality = defaultQuality.label,
                    )
                }
                withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    pm.setUriSource(localUri!!, meta.title, "本地视频", null)
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
                ui.update { it.copy(ttsEngineName = selectedTtsName) }

                val selectedEngineReady = when (selectedTts) {
                    "system" -> true
                    "kokoro" -> io.translatetube.local.data.ModelFiles.ttsReady(this)
                    else -> false
                }
                if (selectedEngineReady) {
                    try {
                        // Pass the saved selection explicitly so a settings change during parsing
                        // cannot cause the readiness check and actual engine to disagree.
                        val tts: TtsEngine = makeTts(selectedTts)
                        val d = DubbingEngine(this, key, scope)
                        registerDubbingFailureHandler(d, tts, key)
                        ui.update {
                            it.copy(
                                ttsEngineName = tts.name,
                                dubbingStatus = "${tts.name} 已初始化，正在准备首句配音",
                                noModelHint = null,
                            )
                        }
                        d.attach(tts, finalLines)
                        dubbing = d
                        dubCollectJob = scope.launch {
                            d.preparedUntil.collect { n -> ui.update { it.copy(preparedUntil = n) } }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PlaybackService", "Unable to initialize selected TTS engine $selectedTts", e)
                        val reason = e.message ?: e.javaClass.simpleName
                        ui.update {
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
                        withContext(Dispatchers.Main) { pm.originalVolume = 1f }
                    }
                } else {
                    val message = when (selectedTts) {
                        "kokoro" -> "已选择 Kokoro 配音模型，但模型尚未就绪。应用不会自动切换到系统 TTS；下载完整的配音模型后再试。"
                        else -> "设置中保存的配音引擎「$selectedTts」无效。请重新选择 Kokoro 或系统 TTS。应用不会擅自切换引擎。"
                    }
                    ui.update {
                        it.copy(
                            ttsEngineName = if (selectedTts == "kokoro") "Kokoro（未就绪）" else selectedTtsName,
                            dubbingStatus = "${selectedTtsName} 尚未就绪",
                            muteOriginal = false,
                            noModelHint = message,
                        )
                    }
                    withContext(Dispatchers.Main) { pm.originalVolume = 1f }
                }
            }
            // TTS 在后台按字幕顺序预合成。预热最前面的可朗读行后再启动视频，
            // 避免原声已被静音、系统 TTS 却还没合成出第一句时出现长时间无声。
            val startupDubbing = dubbing
            if (startupDubbing != null) {
                val firstSpeech = finalLines.indexOfFirst { !it.zh.isNullOrBlank() }
                if (firstSpeech >= 0) {
                    val targetPrepared = minOf(finalLines.size, firstSpeech + 2)
                    val deadlineNs = System.nanoTime() + 12_000_000_000L
                    while (startupDubbing.preparedUntil.value < targetPrepared && System.nanoTime() < deadlineNs) {
                        kotlinx.coroutines.delay(100)
                    }
                    if (startupDubbing.preparedUntil.value <= firstSpeech) {
                        android.util.Log.w("PlaybackService", "TTS startup buffer timed out before first spoken line")
                        ui.update { it.copy(progress = "配音仍在准备，已先开始视频播放") }
                    }
                }
            }
            saveHistory(meta)
            ui.update { it.copy(phase = Phase.READY, engineName = it.engineName.ifEmpty { "本地引擎" }) }
            // 续播：上次观看超过 10 秒且距结尾超 30 秒则跳到上次位置
            val resumeSec = try {
                db.videoDao().byKey(key)?.watchedSec ?: 0L
            } catch (_: Exception) { 0L }
            val totalMs = meta.durationSec * 1000
            if (resumeSec > 10 && (totalMs <= 0 || resumeSec * 1000 < totalMs - 30_000)) {
                withContext(Dispatchers.Main) { pm.seekTo(resumeSec * 1000); pm.play() }
            } else {
                withContext(Dispatchers.Main) { pm.play() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Video pipeline failed for key=$key", e)
            if (e is TranslationFailureException) {
                runCatching { pm.originalVolume = 1f }
                ui.update {
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
            if (ui.value.dubbingOn) {
                runCatching { pm.originalVolume = 1f }
                ui.update {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = e.message ?: "视频解析或配音初始化失败",
                        muteOriginal = false,
                        noModelHint = "配音初始化失败，已恢复视频原声。系统 TTS 请检查手机文字转语音服务/中文语音数据；Kokoro 请检查模型文件是否完整。"
                    )
                }
            } else {
                ui.update { it.copy(phase = Phase.ERROR, progress = "", error = e.message ?: "视频解析失败") }
            }
            }
        } catch (e: Throwable) {
            // A non-fatal Error (for example, an extractor/library linkage error) must be shown
            // as a playback failure rather than escaping a Dispatchers.Default coroutine.
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            android.util.Log.e("PlaybackService", "Fatal-looking non-VM error in video pipeline for key=$key", e)
            ui.update {
                it.copy(
                    phase = Phase.ERROR,
                    progress = "",
                    error = "视频解析/播放器异常（${e.javaClass.simpleName}）：${e.message ?: "请查看崩溃日志"}"
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
        ui.update {
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
            ui.update {
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
            ui.update { state ->
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
                ui.update { state ->
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
                    ui.update {
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
    private suspend fun obtainAsrAudioFile(videoId: String, cacheHitProgress: String): File {
        val mutex = asrAudioDownloadLocks.computeIfAbsent(videoId) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            reusableAsrAudioFile(videoId)?.let { cached ->
                ui.update { it.copy(progress = cacheHitProgress) }
                return@withLock cached
            }
            val stream = NewPipeRepo.audioForAsr(videoId)
                ?: throw IllegalStateException("无法获取视频音频流")
            downloadAudio(stream.content, asrAudioCacheFile(videoId)).also {
                pruneAsrAudioCache(asrAudioCacheDirectory(), keep = it)
            }
        }
    }

    /** Reuse only a complete, reasonably recent file; a failed ASR attempt keeps it for retry. */
    private fun reusableAsrAudioFile(videoId: String): File? {
        val file = asrAudioCacheFile(videoId)
        val now = System.currentTimeMillis()
        if (AsrAudioCachePolicy.canReuse(file.isFile, file.length(), file.lastModified(), now)) {
            file.setLastModified(now)
            file.parentFile?.let { pruneAsrAudioCache(it, keep = file) }
            return file
        }
        file.delete()
        File(file.parentFile, file.name + ".part").delete()
        return null
    }

    /** Keep retry audio bounded in size; never evict the file currently being reused/downloaded. */
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
            val age = now - file.lastModified()
            if (!isKept && (file.length() <= 0L || age < 0L || age > AsrAudioCachePolicy.TTL_MS)) {
                file.delete()
            } else {
                totalBytes += file.length()
            }
        }

        val budget = AsrAudioCachePolicy.CACHE_BUDGET_BYTES
        for (file in files.sortedBy { it.lastModified() }) {
            if (totalBytes <= budget) break
            if (keep != null && file.absolutePath == keep.absolutePath) continue
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private suspend fun downloadAudio(url: String, destination: File? = null): File {
        val target = destination ?: File(cacheDir, "asr_audio_${System.nanoTime()}.bin")
        target.parentFile?.mkdirs()
        val writeTarget = if (destination == null) target else File(target.parentFile, target.name + ".part")
        if (destination != null) {
            writeTarget.delete()
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("无法替换旧的音频缓存文件")
            }
        }
        val maxBytes = AsrAudioCachePolicy.MAX_AUDIO_BYTES
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
                        writeTarget.outputStream().buffered().use { out ->
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
                                    ui.update { it.copy(progress = "下载音频 %.0f%%".format((got * 100f / total).coerceIn(0f, 100f))) }
                                }
                            }
                            out.flush()
                        }
                    }
                    if (!writeTarget.isFile || writeTarget.length() == 0L) {
                        throw IllegalStateException("音频下载结果为空")
                    }
                }
            }
            if (destination == null) return writeTarget
            if (!writeTarget.renameTo(target)) throw IllegalStateException("下载完成但无法保存音频缓存")
            target.setLastModified(System.currentTimeMillis())
            return target
        } catch (e: Exception) {
            writeTarget.delete()
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
                    ui.update {
                        it.copy(
                            muteOriginal = false,
                            noModelHint = "部分字幕未能合成配音，已自动恢复原声，避免出现无声片段。请检查系统文字转语音引擎/语音数据，或重新下载 Kokoro 配音模型。"
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
                pm.originalVolume = if (st.muteOriginal) 0f else 0.15f
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
        if (getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "kokoro") == "system") {
            // Do not initiate local-model work while the user has explicitly selected system TTS.
            ui.update { it.copy(error = "系统 TTS 使用手机当前音色，不支持逐人物切换音色；普通系统配音不受影响。需要多人物音色时，请手动切换到 Kokoro。") }
            return
        }
        val originalLines = ui.value.lines
        if (originalLines.isEmpty()) {
            ui.update { it.copy(error = "当前没有字幕，先生成或导入字幕再区分人物") }
            return
        }
        if (!io.translatetube.local.data.ModelFiles.ttsReady(this)) {
            ui.update { it.copy(error = "Kokoro 配音模型尚未就绪；已保留当前播放和字幕，请先在设置中准备模型后再区分人物。") }
            return
        }
        val sessionKey = videoKey
        val sessionVideoId = videoId
        val sessionLocalUri = localUri
        val preferredVoice = getSharedPreferences("settings", MODE_PRIVATE).getInt("kokoro_speaker", 14).coerceIn(0, 53)
        speakerJob = scope.launch {
            try {
                ui.update { it.copy(error = null, progress = "准备人物识别音频（为荣耀 10 限制为前 3 分钟）…", phase = Phase.TRANSCRIBING) }
                val pcm = withContext(Dispatchers.IO) {
                    val sourceFile: File?
                    val sourceUri: Uri?
                    if (sessionVideoId != null) {
                        val id = sessionVideoId
                        val audio = obtainAsrAudioFile(id, "复用已下载音频，正在分析人物声音…")
                        sourceFile = audio
                        sourceUri = null
                    } else {
                        sourceFile = null
                        sourceUri = sessionLocalUri ?: throw IllegalStateException("找不到当前视频文件")
                    }
                    AudioExtract.decodeTo16kMono(this@PlaybackService, sourceUri, sourceFile, maxDurationMs = 3 * 60 * 1000L)
                }
                val segments = SpeakerDiarizer.diarize(this@PlaybackService, pcm.samples) { message ->
                    ui.update { state ->
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
                    val speaker = if (best != null && best.value >= minOf(0.25f, duration * 0.2f)) best.key else -1
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
                ui.update { state ->
                    if (state.key == sessionKey) {
                        state.copy(
                            lines = updated,
                            progress = "已区分人物，使用 ${distinctVoices} 种音色",
                            phase = Phase.SYNTHESIZING,
                            totalLines = updated.size,
                            preparedUntil = 0,
                            currentLine = -1,
                        )
                    } else state
                }
                // This operation is explicitly Kokoro-only; a settings change mid-task must not
                // silently switch engines or leave a false-success state with no dubbing engine.
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val previousDubbing = dubbing
                dubbing = null
                previousDubbing?.releaseAndJoin()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (videoKey != sessionKey || ui.value.key != sessionKey) {
                    throw kotlinx.coroutines.CancellationException("视频已切换，取消旧的视频人物识别结果")
                }
                val tts = makeTts("kokoro")
                val d = DubbingEngine(this@PlaybackService, sessionKey, scope)
                registerDubbingFailureHandler(d, tts, sessionKey)
                d.attach(tts, updated)
                dubbing = d
                dubCollectJob?.cancel()
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { n ->
                        ui.update { state -> if (state.key == sessionKey) state.copy(preparedUntil = n) else state }
                    }
                }
                val arr = org.json.JSONArray()
                for (line in updated) arr.put(
                    org.json.JSONObject().put("s", line.startMs).put("e", line.endMs)
                        .put("t", line.text).put("zh", line.zh ?: "").put("sp", line.speakerId)
                )
                if (updated.count { !it.zh.isNullOrEmpty() } > updated.size / 2) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            "$sessionKey|zh|${prefs.getLong("active_api_id", -1L)}",
                            ui.value.sourceLang, arr.toString(), System.currentTimeMillis()
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
                ui.update { state ->
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
                ui.update { state ->
                    if (state.key == sessionKey) {
                        state.copy(phase = Phase.READY, progress = "", error = "自动区分人物失败：${e.message}")
                    } else state
                }
            }
        }
    }

    fun setLineSpeaker(index: Int, speakerId: Int) {
        if (getSharedPreferences("settings", MODE_PRIVATE).getString("tts_engine", "kokoro") == "system") {
            ui.update { it.copy(error = "多人物音色切换需要 Kokoro 音色模型，请切换配音引擎后重试") }
            return
        }
        val oldLines = ui.value.lines
        if (index !in oldLines.indices) return
        val selectedVoice = speakerId.coerceIn(-1, 53)
        if (oldLines[index].speakerId == selectedVoice) return
        speakerJob?.cancel()
        speakerJob = null
        val selectedLine = oldLines[index]
        val assignmentSnapshot = loadSpeakerAssignments(videoKey)
        saveSpeakerAssignments(
            assignmentSnapshot.copy(
                lineOverrides = assignmentSnapshot.lineOverrides +
                    (SpeakerAssignmentCodec.lineKey(selectedLine.startMs, selectedLine.endMs) to selectedVoice),
            ),
            videoKey,
        )
        val updated = oldLines.mapIndexed { i, line ->
            if (i == index) line.copy(speakerId = selectedVoice) else line
        }
        dubCollectJob?.cancel()
        val previousDubbing = dubbing
        previousDubbing?.release()
        dubbing = null
        ui.update {
            it.copy(lines = updated, preparedUntil = 0, totalLines = updated.size,
                currentLine = -1, phase = Phase.SYNTHESIZING, progress = "更新人物音色…")
        }
        speakerJob = scope.launch {
            try {
                previousDubbing?.releaseAndJoin()
                // Manual per-cue voice selection is Kokoro-only; keep the selected engine stable.
                val tts = makeTts("kokoro")
                val d = DubbingEngine(this@PlaybackService, videoKey, scope)
                registerDubbingFailureHandler(d, tts)
                d.attach(tts, updated)
                dubbing = d
                dubCollectJob = scope.launch {
                    d.preparedUntil.collect { n ->
                        ui.update { state -> state.copy(preparedUntil = n,
                            phase = if (n >= updated.size) Phase.READY else Phase.SYNTHESIZING,
                            progress = if (n >= updated.size) "" else "正在准备多人物配音…") }
                    }
                }
                val arr = org.json.JSONArray()
                for (line in updated) arr.put(
                    org.json.JSONObject().put("s", line.startMs).put("e", line.endMs)
                        .put("t", line.text).put("zh", line.zh ?: "").put("sp", line.speakerId)
                )
                if (updated.isNotEmpty() && updated.count { !it.zh.isNullOrEmpty() } > updated.size / 2) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val src = ui.value.sourceLang
                    AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity(
                            "$videoKey|zh|${prefs.getLong("active_api_id", -1L)}", src,
                            arr.toString(), System.currentTimeMillis()
                        )
                    )
                }
                lastLineIdx = Int.MIN_VALUE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.update { it.copy(phase = Phase.ERROR, error = "切换人物音色失败：${e.message}") }
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
