package io.translatetube.local.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** ExoPlayer 封装：源装配 / 音量 / 倍速 / 时间轴轮询 */
class PlayerManager(private val ctx: Context) {

    /** 配音混流总线：DubbingEngine 按媒体时间挂 PCM，音频处理器逐窗相加。 */
    val dubbingBus = DubbingTrackBus()

    /** Mixer diagnostics are written off the audio thread: AppLogStore does synchronous file IO. */
    private val mixerLogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dubbing-mixer-log").apply { isDaemon = true }
    }

    /** 混流处理器（与 bus 成对，注入 ExoPlayer 音频渲染管线）。 */
    private val dubbingProcessor = DubbingAudioProcessor(dubbingBus) { isWarning, message, details ->
        runCatching {
            mixerLogExecutor.execute {
                runCatching {
                    if (isWarning) {
                        io.translatetube.local.util.AppLogStore.warning(
                            ctx, "DubbingMixer", message, diagnosticContext = details,
                        )
                    } else {
                        io.translatetube.local.util.AppLogStore.info(
                            ctx, "DubbingMixer", message, diagnosticContext = details,
                        )
                    }
                }
            }
        }
    }

    /** Attach the real decoded-buffer PTS to the processor before Media3 handles each buffer. */
    private val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink? = runCatching {
            val delegate = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(dubbingProcessor))
                .build()
            object : androidx.media3.exoplayer.audio.ForwardingAudioSink(delegate) {
                private var currentInputBuffer: ByteBuffer? = null

                /**
                 * ExoPlayer adds a per-stream offset (10^12 us for the first period) to every
                 * buffer timestamp before calling handleBuffer. Without removing it, the mix
                 * window never lines up with subtitle time and the dubbing stays silent.
                 */
                @Volatile private var rendererStreamOffsetUs = 0L

                override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                    rendererStreamOffsetUs = outputStreamOffsetUs
                    dubbingProcessor.sinkStreamOffsetUs = outputStreamOffsetUs
                    super.setOutputStreamOffsetUs(outputStreamOffsetUs)
                }

                override fun handleBuffer(
                    buffer: ByteBuffer,
                    presentationTimeUs: Long,
                    encodedAccessUnitCount: Int,
                ): Boolean {
                    if (currentInputBuffer !== buffer) {
                        dubbingProcessor.setInputBufferPresentationTimeUs(
                            DubbingSinkTimestamp.toMediaTimeUs(
                                presentationTimeUs,
                                rendererStreamOffsetUs,
                            ),
                        )
                        currentInputBuffer = buffer
                    }
                    val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    if (handled) currentInputBuffer = null
                    return handled
                }

                override fun handleDiscontinuity() {
                    currentInputBuffer = null
                    dubbingProcessor.clearInputBufferTimestamp()
                    super.handleDiscontinuity()
                }

                override fun flush() {
                    currentInputBuffer = null
                    dubbingProcessor.clearInputBufferTimestamp()
                    super.flush()
                }

                override fun reset() {
                    currentInputBuffer = null
                    dubbingProcessor.clearInputBufferTimestamp()
                    super.reset()
                }
            }
        }.onFailure { error ->
            android.util.Log.e("PlayerManager", "无法安装配音混流处理器，回退系统音频 sink", error)
        }.getOrNull() ?: super.buildAudioSink(
            context, enableFloatOutput, enableAudioTrackPlaybackParams,
        )
    }

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(UA)
        .setAllowCrossProtocolRedirects(true)

    private val bufferConfig: PlayerBufferConfig = run {
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memoryInfo) }
        PlayerBufferPolicy.select(
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: 256,
            availableMemoryBytes = memoryInfo.availMem,
        )
    }

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, httpFactory)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    bufferConfig.minBufferMs,
                    bufferConfig.maxBufferMs,
                    bufferConfig.bufferForPlaybackMs,
                    bufferConfig.bufferForPlaybackAfterRebufferMs,
                )
                .build()
        )
        .build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    /** 播放器正在加载/缓冲（含首次加载、拖动进度后的重新加载）：界面据此显示转圈，而不是黑屏。 */
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    /**
     * 本次加载中，网络请求失败后播放器自动重试的次数（来自 ExoPlayer 的真实加载错误回调）。
     * 缓冲结束（可播放）或停止后清零。界面据此如实提示"连接不稳定，正在重试"。
     */
    private val _loadRetryCount = MutableStateFlow(0)
    val loadRetryCount: StateFlow<Int> = _loadRetryCount

    /** 播放头之前方已经缓冲到的时长（毫秒），必须在主线程读取。 */
    fun bufferedAheadMs(): Long = runCatching { player.totalBufferedDuration }.getOrDefault(0L).coerceAtLeast(0L)
    val errorSink = MutableStateFlow<String?>(null)

    var onTick: ((ms: Long) -> Unit)? = null
        set(value) {
            field = value
            syncTicker()
        }
    var onEnded: (() -> Unit)? = null
    var onVideoAspect: ((ratio: Float) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // A 50 ms cadence lowers subtitle-cue start jitter without polling aggressively on older phones.
    private val timelineTickIntervalMs = 50L
    @Volatile private var released = false
    private var tickFailureLogged = false

    /** A faulty consumer must not permanently kill the playback timeline ticker. */
    private fun notifyTick(positionMs: Long? = null) {
        if (released) return
        val callback = onTick ?: return
        val safePosition = try {
            positionMs ?: player.currentPosition
        } catch (error: Exception) {
            if (!released) {
                android.util.Log.w("PlayerManager", "Could not read the playhead for timeline update", error)
            }
            return
        }
        try {
            callback(safePosition)
            tickFailureLogged = false
        } catch (error: Exception) {
            if (!tickFailureLogged) {
                android.util.Log.w("PlayerManager", "Timeline callback failed; polling will continue", error)
                tickFailureLogged = true
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released || onTick == null) return
            val playing = try {
                player.isPlaying
            } catch (error: Exception) {
                if (!released) android.util.Log.w("PlayerManager", "Ticker could not read player state", error)
                false
            }
            if (!playing) return
            notifyTick()
            if (!released && onTick != null && runCatching { player.isPlaying }.getOrDefault(false)) {
                mainHandler.postDelayed(this, timelineTickIntervalMs)
            }
        }
    }

    /** Poll only while playback is active and a consumer needs timeline updates. */
    private fun syncTicker() {
        mainHandler.removeCallbacks(ticker)
        if (released || onTick == null) return
        if (runCatching { player.isPlaying }.getOrDefault(false)) {
            mainHandler.postDelayed(ticker, timelineTickIntervalMs)
        }
    }

    /** 播放器诊断日志写磁盘，放到后台线程，避免卡住主线程/渲染。不记录带签名的完整地址，只记录域名。 */
    private fun logPlayerDiagnostic(
        isWarning: Boolean,
        message: String,
        details: Map<String, String>,
        failure: Throwable? = null,
        issueKey: String? = null,
    ) {
        runCatching {
            mixerLogExecutor.execute {
                runCatching {
                    // 沿用 PlayerManager 标签：日志页会归到"视频播放"分类，标题由 AppLogTitleResolver 生成。
                    if (isWarning) {
                        io.translatetube.local.util.AppLogStore.warning(
                            ctx, "PlayerManager", message, failure,
                            diagnosticContext = details, issueKey = issueKey,
                        )
                    } else {
                        io.translatetube.local.util.AppLogStore.info(
                            ctx, "PlayerManager", message, diagnosticContext = details,
                        )
                    }
                }
            }
        }
    }

    private fun playerStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE（空闲/未准备）"
        Player.STATE_BUFFERING -> "BUFFERING（缓冲中）"
        Player.STATE_READY -> "READY（可播放）"
        Player.STATE_ENDED -> "ENDED（播放结束）"
        else -> state.toString()
    }

    /** 当前视频流的域名（YouTube 为 googlevideo.com 一类），用于判断是不是某个域名访问不通。 */
    private fun currentMediaHost(): String = runCatching {
        player.currentMediaItem?.localConfiguration?.uri?.host
    }.getOrNull().orEmpty().ifBlank { "未知（合并音视频流）" }

    private fun logPlayerError(error: PlaybackException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val httpStatus = chain.firstNotNullOfOrNull {
            (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        }
        val host = currentMediaHost()
        logPlayerDiagnostic(
            isWarning = true,
            message = "播放器报告错误",
            failure = error,
            // 不同错误码/域名分别归类，避免所有播放错误被合并成同一条问题。
            issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                "PlayerManager", "播放器报告错误|${error.errorCodeName}|$host",
            ),
            details = mapOf(
                "错误码" to error.errorCodeName,
                "HTTP 状态码" to (httpStatus?.toString() ?: "无"),
                "视频流域名" to host,
                "播放位置 ms" to runCatching { player.currentPosition.toString() }.getOrDefault("未知"),
                "播放器状态" to runCatching { playerStateName(player.playbackState) }.getOrDefault("未知"),
                "异常链" to chain.joinToString(" <- ") {
                    it.javaClass.simpleName + (it.message?.take(120)?.let { m -> ": $m" } ?: "")
                },
            ),
        )
    }

    private var bufferingStartedAtMs = 0L

    init {
        player.volume = 1f
        // 音频焦点：耳机拔出/来电/其他应用播放时自动暂停或压低
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        // 后台播放不休眠（WiFi/CPU）
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                // Emit an immediate update on pause/resume to invalidate detached AudioTrack work.
                notifyTick()
                syncTicker()
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // Push seek/speed synchronization now rather than waiting for the 100 ms ticker.
                // Read the playhead inside notifyTick's guarded path.
                notifyTick()
            }
            override fun onPlaybackStateChanged(state: Int) {
                // 记录缓冲耗时：黑屏时能看出是一直在缓冲（网络慢/域名不通）还是根本没开始加载。
                _isBuffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    _loadRetryCount.value = 0
                }
                val nowMs = android.os.SystemClock.elapsedRealtime()
                when (state) {
                    Player.STATE_BUFFERING -> bufferingStartedAtMs = nowMs
                    Player.STATE_READY -> if (bufferingStartedAtMs > 0L) {
                        val waitedMs = nowMs - bufferingStartedAtMs
                        bufferingStartedAtMs = 0L
                        if (waitedMs >= 3_000L) {
                            logPlayerDiagnostic(
                                isWarning = false,
                                message = "视频缓冲较慢",
                                details = mapOf(
                                    "缓冲耗时 ms" to waitedMs.toString(),
                                    "视频流域名" to currentMediaHost(),
                                ),
                            )
                        }
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> bufferingStartedAtMs = 0L
                }
                if (state == Player.STATE_ENDED) {
                    try {
                        onEnded?.invoke()
                    } catch (error: Exception) {
                        android.util.Log.w("PlayerManager", "Playback-end callback failed", error)
                    }
                }
            }
            override fun onRenderedFirstFrame() {
                val size = player.videoSize
                logPlayerDiagnostic(
                    isWarning = false,
                    message = "视频首帧已渲染",
                    details = mapOf(
                        "视频流域名" to currentMediaHost(),
                        "视频宽度" to size.width.toString(),
                        "视频高度" to size.height.toString(),
                        "播放器状态" to playerStateName(player.playbackState),
                        "是否正在播放" to player.isPlaying.toString(),
                        "当前进度 ms" to player.currentPosition.coerceAtLeast(0L).toString(),
                    ),
                )
            }
            override fun onPlayerError(error: PlaybackException) {
                logPlayerError(error)
                var cause: Throwable? = error
                var detail: String? = null
                while (cause != null) {
                    val message = cause.message?.trim().orEmpty()
                    if (message.isNotEmpty() && !message.equals("Source error", ignoreCase = true)) {
                        detail = message
                        break
                    }
                    cause = cause.cause
                }
                val code = error.errorCodeName
                val friendly = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        "视频地址返回的内容无法播放（常见原因：站点防护拦截了 App 的访问，或视频地址已过期）。请回到「网页视频」页重新检测，或稍后在网络环境变化后重试。"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "网络连接失败或超时，请检查网络后重试。"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "视频地址被站点拒绝（HTTP 状态异常），站点防护可能已更新，请重新从网页检测。"
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                        "没有权限访问该视频地址。"
                    else -> null
                }
                errorSink.value = friendly
                    ?: "播放失败（$code）：${detail ?: error.message ?: "播放器无法读取该视频流"}"
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 竖屏/方形视频自适应宽高比，避免黑边或裁切
                val w = videoSize.width.toFloat()
                val h = videoSize.height.toFloat()
                if (w > 0 && h > 0) {
                    // pixelWidthHeightRatio 处理非方像素（少见但合法）
                    val ratio = (w * videoSize.pixelWidthHeightRatio / h).coerceIn(0.5f, 2.4f)
                    try {
                        onVideoAspect?.invoke(ratio)
                    } catch (error: Exception) {
                        android.util.Log.w("PlayerManager", "Video-aspect callback failed", error)
                    }
                }
            }
        })
        // 真实网络信号：每次视频数据请求失败（超时/断连/HTTP 错误）ExoPlayer 都会回调一次，随后自动重试。
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                if (wasCanceled) return
                _loadRetryCount.value = _loadRetryCount.value + 1
                logPlayerDiagnostic(
                    isWarning = true,
                    message = "视频数据加载失败，播放器将自动重试",
                    details = mapOf(
                        "累计失败次数" to _loadRetryCount.value.toString(),
                        "视频流域名" to (loadEventInfo.uri.host ?: "未知"),
                        "已缓冲 ms" to bufferedAheadMs().toString(),
                    ),
                    failure = error,
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "PlayerManager", "视频数据加载失败|${loadEventInfo.uri.host ?: "未知"}",
                    ),
                )
            }
        })
        // No idle ticker: onIsPlayingChanged starts it when an observed player begins playback.
    }

    /** 通知栏/锁屏显示的媒体元数据（标题/作者/封面） */
    var mediaTitle: String = ""
    var mediaArtist: String = ""
    var mediaArtwork: String? = null

    /** 通知栏/锁屏显示的媒体元数据（标题/作者/封面），随 MediaItem 挂载 */
    private fun buildMetadata(title: String, artist: String, artworkUrl: String?): androidx.media3.common.MediaMetadata =
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title.ifEmpty { "大象翻译" })
            .setArtist(artist.ifEmpty { "中文配音" })
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()

    /**
     * YouTube：优先使用单文件 muxed 流，避免旧设备在自适应音视频轨合并时崩溃/黑屏。
     * 只有没有 muxed 流时才退回分离音视频轨。
     */
    fun setYouTubeSource(
        videoOnly: List<Pair<String, Int>>,
        audioUrl: String,
        muxedUrl: String?,
        title: String = "",
        artist: String = "",
        artworkUrl: String? = null,
    ) {
        // Validate candidates before stopping the current source. Invalid extractor output must
        // not turn a recoverable parse failure into an abrupt stop of a working video.
        val muxed = muxedUrl?.takeIf(::isValidHttpMediaUrl)
        val v = PlayerQualityPolicy.chooseDefault(videoOnly, ::isValidHttpMediaUrl)?.first
        if (!PlayerQualityPolicy.hasUsableSourceCandidate(
                muxed,
                v,
                audioUrl,
                ::isValidHttpMediaUrl,
            )
        ) {
            errorSink.value = "YouTube 没有可用的合并音视频流或有效的分离音视频流地址"
            return
        }

        clearPreviousYouTubeSource()

        // Single-file streams are the most compatible path on older Android devices.
        if (muxed != null) {
            try {
                setUriSource(Uri.parse(muxed), title, artist, artworkUrl)
                return
            } catch (e: Exception) {
                android.util.Log.w("PlayerManager", "Muxed YouTube stream setup failed; trying adaptive streams", e)
            }
        }

        if (v != null && isValidHttpMediaUrl(v) && isValidHttpMediaUrl(audioUrl)) {
            try {
                val meta = buildMetadata(title, artist, artworkUrl)
                val vs = ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.Builder().setUri(v).setMediaMetadata(meta).build())
                val asrc = ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.Builder().setUri(audioUrl).setMediaMetadata(meta).build())
                player.setMediaSource(MergingMediaSource(vs, asrc))
                player.prepare()
                return
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Adaptive YouTube stream setup failed", e)
                errorSink.value = "无法初始化 YouTube 音视频流：${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (errorSink.value == null) {
            errorSink.value = "YouTube 没有可用的合并音视频流或有效的分离音视频流地址"
        }
    }

    /** Clear stale playback before switching; a failed new source must not leave the old video running. */
    private fun clearPreviousYouTubeSource() {
        clearVideoQualityOverrides()
        errorSink.value = null
        player.pause()
        player.stop()
        player.clearMediaItems()
    }

    /** 为指定清晰度加载流；视频轨分离时与音频轨合并播放。 */
    fun setYouTubeQuality(
        videoUrl: String,
        audioUrl: String,
        muxed: Boolean,
        title: String = "",
        artist: String = "",
        artworkUrl: String? = null,
    ): Boolean {
        // Reject bad candidates before stopping the currently working source.
        if (!isValidHttpMediaUrl(videoUrl)) {
            errorSink.value = "YouTube 视频流地址无效（不是有效的 HTTP/HTTPS 地址）"
            return false
        }
        if (!muxed && !isValidHttpMediaUrl(audioUrl)) {
            errorSink.value = "该清晰度缺少有效的视频或音频流地址"
            return false
        }

        // Clearing Media3 state resets playWhenReady and the timeline. Capture both first so a
        // quality switch does not jump back to 00:00 or unexpectedly pause an active session.
        val resumeState = PlayerQualitySwitchPolicy.capture(
            player.currentPosition,
            player.playWhenReady,
        )
        clearPreviousYouTubeSource()
        if (muxed) {
            return try {
                setUriSource(Uri.parse(videoUrl), title, artist, artworkUrl)
                restoreQualitySwitchState(resumeState)
                true
            } catch (e: Exception) {
                errorSink.value = "无法初始化 YouTube 合并视频流：${e.message ?: e.javaClass.simpleName}"
                false
            }
        }
        return try {
            val meta = buildMetadata(title, artist, artworkUrl)
            val videoSource = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(videoUrl).setMediaMetadata(meta).build()
                )
            val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(audioUrl).setMediaMetadata(meta).build()
                )
            player.setMediaSource(MergingMediaSource(videoSource, audioSource))
            player.prepare()
            restoreQualitySwitchState(resumeState)
            true
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "YouTube quality setup failed", e)
            errorSink.value = "无法初始化 ${title.ifEmpty { "YouTube" }} 视频流：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Restore the position and intent after a successful quality-source replacement. */
    private fun restoreQualitySwitchState(state: PlayerQualityResumeState) {
        player.seekTo(state.positionMs)
        if (state.wasPlaying && isPlaybackAllowed()) player.play() else player.pause()
    }

    private fun isValidHttpMediaUrl(value: String): Boolean =
        PlaybackHttpUrlPolicy.isValidMediaUrl(value)

    /** 本地文件 / 直链；网页源可附带当前 WebView 会话的 Referer/Cookie。 */
    fun setUriSource(
        uri: Uri,
        title: String = "",
        artist: String = "",
        artworkUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        userAgent: String? = null,
    ) {
        clearVideoQualityOverrides()
        errorSink.value = null
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(buildMetadata(title, artist, artworkUrl))
            .build()
        val safeRequestHeaders = PlaybackHttpUrlPolicy.sanitizeRequestHeaders(requestHeaders)
        if (safeRequestHeaders.isEmpty()) {
            player.setMediaItem(item)
        } else {
            // 站点防护（如 Cloudflare）的通行凭证与请求 UA 绑定：网页视频源必须沿用
            // WebView 的 UA，否则 Cookie 会被判无效，返回挑战页 HTML 导致解析失败。
            val remoteHttpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: UA)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(safeRequestHeaders)
            val remoteMediaSourceFactory = DefaultMediaSourceFactory(
                DefaultDataSource.Factory(ctx, remoteHttpFactory)
            )
            player.setMediaSource(remoteMediaSourceFactory.createMediaSource(item))
        }
        player.prepare()
    }

    /** 当前媒体流实际暴露且设备可解码的视频清晰度；静态单画质视频不会产生可切换菜单。 */
    fun availableVideoQualityLabels(): List<String> {
        if (released) return emptyList()
        return runCatching {
            player.currentTracks.groups.asSequence()
                .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    (0 until group.length).asSequence()
                        .filter { group.isTrackSupported(it) }
                        .map { group.getTrackFormat(it).height }
                }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
                .map { "${it}p" }
                .toList()
        }.onFailure {
            android.util.Log.w("PlayerManager", "读取网页视频可用画质失败", it)
        }.getOrDefault(emptyList())
    }

    /** 返回当前已选视频轨的分辨率标签。 */
    fun currentVideoQualityLabel(): String {
        if (released) return ""
        return runCatching {
            player.currentTracks.groups.asSequence()
                .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    (0 until group.length).asSequence()
                        .filter { group.isTrackSelected(it) }
                        .map { group.getTrackFormat(it).height }
                }
                .firstOrNull { it > 0 }
                ?.let { "${it}p" }
                .orEmpty()
        }.onFailure {
            android.util.Log.w("PlayerManager", "读取当前视频画质失败", it)
        }.getOrDefault("")
    }

    /**
     * 对当前自适应媒体清单锁定一个视频轨。
     * 这里只更新 Media3 的视频轨选择参数，不重载媒体源、不 seek，音频轨与配音时间轴保持连续。
     */
    fun selectVideoQuality(label: String): Boolean {
        if (released) return false
        val targetHeight = label.removeSuffix("p").toIntOrNull()?.takeIf { it > 0 } ?: return false
        return runCatching {
            val matchingTrack = player.currentTracks.groups.asSequence()
                .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    (0 until group.length).asSequence().map { trackIndex -> group to trackIndex }
                }
                .firstOrNull { (group, trackIndex) ->
                    group.isTrackSupported(trackIndex) &&
                        group.getTrackFormat(trackIndex).height == targetHeight
                } ?: return false

            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        matchingTrack.first.mediaTrackGroup,
                        listOf(matchingTrack.second),
                    ),
                )
                .build()
            true
        }.onFailure {
            android.util.Log.w("PlayerManager", "应用网页视频画质选择失败：$label", it)
        }.getOrDefault(false)
    }

    /** 更换媒体源时清除旧视频轨覆盖，避免同结构的新流继承上个视频的锁定画质。 */
    private fun clearVideoQualityOverrides() {
        if (released) return
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                .build()
        }.onFailure {
            android.util.Log.w("PlayerManager", "清除旧视频画质选择失败", it)
        }
    }

    /**
     * PlaybackService supplies the live readiness/error gate. If dubbing is waiting for a model,
     * or the selected TTS has failed, automatic playback must remain paused instead of falling
     * back to source audio in the background.
     */
    @Volatile var playbackAllowed: (() -> Boolean)? = null

    /** 点播放被上面的门拦住时回调（主线程），由 PlaybackService 在页面上显示具体原因。 */
    @Volatile var onPlayBlocked: (() -> Unit)? = null

    /**
     * 播放器处于空闲（IDLE，例如后台期间被停止或出错后）但还挂着视频时，play() 本身不会重新加载，
     * 表现就是"点了没反应、一直黑屏"。点播放前先重新 prepare()，让它真正开始加载。
     */
    private fun ensurePreparedForPlay() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            errorSink.value = null
            player.prepare()
        }
    }

    private fun isPlaybackAllowed(): Boolean = try {
        playbackAllowed?.invoke() ?: true
    } catch (error: Exception) {
        android.util.Log.e("PlayerManager", "Playback readiness check failed; keeping playback paused", error)
        false
    }

    fun play() {
        if (released) return
        try {
            if (!isPlaybackAllowed()) {
                player.pause()
                runCatching { onPlayBlocked?.invoke() }
                return
            }
            ensurePreparedForPlay()
            player.play()
        } catch (error: Exception) {
            if (!released) android.util.Log.e("PlayerManager", "Unable to start playback", error)
        }
    }

    fun pause() {
        if (released) return
        try {
            player.pause()
        } catch (error: Exception) {
            if (!released) android.util.Log.w("PlayerManager", "Unable to pause playback", error)
        }
    }

    fun seekTo(ms: Long) {
        if (released) return
        val safePosition = ms.coerceAtLeast(0L)
        try {
            player.seekTo(safePosition)
            notifyTick(safePosition)
        } catch (error: Exception) {
            if (!released) android.util.Log.e("PlayerManager", "Player rejected seek to $safePosition ms", error)
        }
    }
    fun speed(s: Float) {
        player.playbackParameters = PlaybackParameters(DubbingTiming.normalizePlayerSpeed(s))
    }
    fun playbackSpeed(): Float = player.playbackParameters.speed
    /** 原声增益：不能用 player.volume（会连混进同一路输出的配音一起静音），改为控制混流处理器。 */
    var originalVolume: Float
        get() = dubbingProcessor.originalGain
        set(v) { dubbingProcessor.originalGain = v.coerceIn(0f, 1f) }
    /** 中文配音增益：与原声独立，不能使用 player.volume，因为两路会在同一 sink 混合。 */
    var dubbingVolume: Float
        get() = dubbingProcessor.dubbingGain
        set(v) { dubbingProcessor.dubbingGain = v.coerceIn(0f, 1f) }
    fun duration(): Long = if (released) 0L else
        runCatching { player.duration.coerceAtLeast(0) }
            .onFailure { if (!released) android.util.Log.w("PlayerManager", "Unable to read duration", it) }
            .getOrDefault(0L)

    fun position(): Long = if (released) 0L else
        runCatching { player.currentPosition.coerceAtLeast(0) }
            .onFailure { if (!released) android.util.Log.w("PlayerManager", "Unable to read playhead", it) }
            .getOrDefault(0L)

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(ticker)
        runCatching { player.release() }
            .onFailure { android.util.Log.w("PlayerManager", "Player release failed", it) }
        runCatching { mixerLogExecutor.shutdown() }
    }
}
