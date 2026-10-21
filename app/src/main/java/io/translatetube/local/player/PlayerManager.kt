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

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** ExoPlayer 封装：源装配 / 音量 / 倍速 / 时间轴轮询 */
class PlayerManager(private val ctx: Context) {

    /** 配音混流总线：DubbingEngine 按媒体时间挂 PCM，音频处理器逐窗相加。 */
    val dubbingBus = DubbingTrackBus()

    /** 混流处理器（与 bus 成对，注入 ExoPlayer 音频渲染管线）。 */
    private val dubbingProcessor = DubbingAudioProcessor(dubbingBus)

    /** 挂了配音混流处理器的渲染器工厂：配音与原声共用同一播放时钟。 */
    private val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink? = runCatching {
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(dubbingProcessor))
                .build()
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
                if (state == Player.STATE_ENDED) {
                    try {
                        onEnded?.invoke()
                    } catch (error: Exception) {
                        android.util.Log.w("PlayerManager", "Playback-end callback failed", error)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
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
        if (state.wasPlaying) player.play() else player.pause()
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

    fun play() {
        if (released) return
        try {
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
    }
}
