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
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(UA)
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, httpFactory)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 150_000, 1_500, 3_000)
                .build()
        )
        .build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    val errorSink = MutableStateFlow<String?>(null)

    var onTick: ((ms: Long) -> Unit)? = null
    var onSeek: ((ms: Long) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onVideoAspect: ((ratio: Float) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // Tick while paused too: keep subtitle highlighting and audio-track state aligned.
            onTick?.invoke(player.currentPosition)
            mainHandler.postDelayed(this, 100)
        }
    }

    init {
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded?.invoke()
                onTick?.invoke(player.currentPosition)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek inside the same subtitle cue must restart from the new in-cue offset.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    onSeek?.invoke(newPosition.positionMs)
                } else {
                    onTick?.invoke(newPosition.positionMs)
                }
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                onTick?.invoke(player.currentPosition)
            }
            override fun onPlayerError(error: PlaybackException) {
                errorSink.value = error.message ?: "播放错误"
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 竖屏/方形视频自适应宽高比，避免黑边或裁切
                val w = videoSize.width.toFloat()
                val h = videoSize.height.toFloat()
                if (w > 0 && h > 0) {
                    // pixelWidthHeightRatio 处理非方像素（少见但合法）
                    val ratio = (w * videoSize.pixelWidthHeightRatio / h).coerceIn(0.5f, 2.4f)
                    onVideoAspect?.invoke(ratio)
                }
            }
        })
        mainHandler.post(ticker)
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
        errorSink.value = null

        // 单文件流兼容性最好：只需一个 MediaSource，不需要 MergingMediaSource 对齐两条时间轴。
        if (!muxedUrl.isNullOrBlank()) {
            try {
                setUriSource(Uri.parse(muxedUrl), title, artist, artworkUrl)
                return
            } catch (e: Exception) {
                android.util.Log.w("PlayerManager", "Muxed YouTube stream setup failed; trying adaptive streams", e)
            }
        }

        val v = videoOnly.maxByOrNull { it.second }?.first
        if (!v.isNullOrBlank() && audioUrl.isNotBlank()) {
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

        if (muxedUrl.isNullOrBlank()) {
            errorSink.value = "YouTube 没有提供可播放的合并音视频流，且分离音视频流不可用"
        }
    }

    /** 为指定清晰度加载流；视频轨分离时与音频轨合并播放。 */
    /** 为指定清晰度装配流；返回 false 表示 MediaSource 初始化阶段失败。 */
    fun setYouTubeQuality(
        videoUrl: String,
        audioUrl: String,
        muxed: Boolean,
        title: String = "",
        artist: String = "",
        artworkUrl: String? = null,
    ): Boolean {
        errorSink.value = null
        if (videoUrl.isBlank()) {
            errorSink.value = "该清晰度没有可用的视频地址"
            return false
        }
        if (muxed) {
            return try {
                setUriSource(Uri.parse(videoUrl), title, artist, artworkUrl)
                true
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Muxed YouTube quality setup failed", e)
                errorSink.value = "无法初始化 ${title.ifEmpty { "YouTube" }} 视频流：${e.message ?: e.javaClass.simpleName}"
                false
            }
        }
        if (audioUrl.isBlank()) {
            errorSink.value = "该清晰度缺少独立音频流"
            return false
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
            true
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "YouTube quality setup failed", e)
            errorSink.value = "无法初始化 ${title.ifEmpty { "YouTube" }} 视频流：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** 本地文件 / 直链 */
    fun setUriSource(uri: Uri, title: String = "", artist: String = "", artworkUrl: String? = null) {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(buildMetadata(title, artist, artworkUrl))
                .build()
        )
        player.prepare()
    }

    fun play() { player.play() }
    fun pause() { player.pause() }
    fun seekTo(ms: Long) {
        player.seekTo(ms)
        onTick?.invoke(ms)
    }
    fun speed(s: Float) { player.playbackParameters = PlaybackParameters(s) }
    fun playbackSpeed(): Float = player.playbackParameters.speed
    var originalVolume: Float
        get() = player.volume
        set(v) { player.volume = v }
    fun duration(): Long = player.duration.coerceAtLeast(0)
    fun position(): Long = player.currentPosition.coerceAtLeast(0)

    fun release() {
        mainHandler.removeCallbacks(ticker)
        player.release()
    }
}
