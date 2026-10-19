package io.translatetube.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.translatetube.local.ui.screens.*
import io.translatetube.local.ui.theme.BgDark
import io.translatetube.local.ui.theme.TranslateTubeTheme
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.Phase
import io.translatetube.local.yt.NewPipeRepo

enum class Screen { HOME, PLAYER, WEB, SETTINGS, API, LOGS, STORAGE }

/** Survives process death so an interrupted parse can be restarted from its original entry URL. */
private data class PendingResumeTask(
    val sourceKey: String,
    val activeKey: String,
    val generation: Long,
)

/** 一次播放请求：YouTube 或 本地视频 */
data class PlayRequest(
    val videoId: String? = null,
    val localUri: String? = null,
    val mediaUrl: String? = null,
    val mediaTitle: String? = null,
    val mediaReferrer: String? = null,
    val mediaCookie: String? = null,
    val mediaUserAgent: String? = null,
    val webPageUrl: String? = null,
    val ts: Long = System.currentTimeMillis(), // 保证同视频重复点击也能触发
) {
    val key: String get() = when {
        videoId != null -> "youtube:$videoId"
        mediaUrl != null -> "remote:$mediaUrl"
        webPageUrl != null -> "webpage:$webPageUrl"
        else -> "local:${localUri ?: ""}"
    }
}

class MainActivity : ComponentActivity() {
    /** 从分享/链接/历史/本地选择进入的播放请求 */
    private val playReq = mutableStateOf<PlayRequest?>(null)
    private val pendingResumeTaskState = mutableStateOf<PendingResumeTask?>(null)
    private val resumePrefs by lazy { getSharedPreferences(RESUME_PREFS, MODE_PRIVATE) }

    private fun loadPendingResumeTask(): PendingResumeTask? {
        val sourceKey = resumePrefs.getString(KEY_SOURCE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val activeKey = resumePrefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() } ?: sourceKey
        val generation = resumePrefs.getLong(KEY_GENERATION, 1L).coerceAtLeast(1L)
        return PendingResumeTask(sourceKey, activeKey, generation)
    }

    private fun persistPendingResumeTask(task: PendingResumeTask) {
        resumePrefs.edit()
            .putString(KEY_SOURCE, task.sourceKey)
            .putString(KEY_ACTIVE, task.activeKey)
            .putLong(KEY_GENERATION, task.generation)
            .commit()
    }

    /** Give even rapid repeat taps on the same row a distinct playback request token. */
    private fun withFreshRequestToken(request: PlayRequest): PlayRequest {
        val previousToken = playReq.value?.ts ?: 0L
        return request.copy(ts = maxOf(request.ts, previousToken + 1L))
    }

    /** Save each new request before the navigation/parse pipeline starts. */
    private fun rememberPlayRequest(request: PlayRequest) {
        val tokenizedRequest = withFreshRequestToken(request)
        playReq.value = tokenizedRequest
        val generation = (resumePrefs.getLong(KEY_GENERATION, 0L) + 1L).coerceAtLeast(1L)
        val task = PendingResumeTask(tokenizedRequest.key, tokenizedRequest.key, generation)
        persistPendingResumeTask(task)
        pendingResumeTaskState.value = task
        val source = when {
            request.videoId != null -> "YouTube 视频"
            request.webPageUrl != null -> "网页视频"
            request.mediaUrl != null -> "网页媒体"
            request.localUri != null -> "本地视频"
            else -> "未知来源"
        }
        io.translatetube.local.util.AppLogStore.info(
            applicationContext,
            "PlaybackFlow",
            "开始播放请求：$source",
        )
    }

    /** Keep the original web page as the recovery source if parsing resolves to a temporary media URL. */
    private fun advancePlayRequest(request: PlayRequest) {
        val current = pendingResumeTaskState.value ?: loadPendingResumeTask()
        val tokenizedRequest = withFreshRequestToken(request)
        playReq.value = tokenizedRequest
        if (current == null) {
            val generation = (resumePrefs.getLong(KEY_GENERATION, 0L) + 1L).coerceAtLeast(1L)
            val task = PendingResumeTask(tokenizedRequest.key, tokenizedRequest.key, generation)
            persistPendingResumeTask(task)
            pendingResumeTaskState.value = task
            return
        }
        val updated = current.copy(activeKey = tokenizedRequest.key)
        persistPendingResumeTask(updated)
        pendingResumeTaskState.value = updated
        io.translatetube.local.util.AppLogStore.info(
            applicationContext,
            "PlaybackFlow",
            "网页视频已解析出媒体地址，准备进入播放器",
        )
    }

    private fun clearPendingResumeTask(expected: PendingResumeTask) {
        val current = loadPendingResumeTask() ?: return
        if (current.sourceKey != expected.sourceKey ||
            current.activeKey != expected.activeKey ||
            current.generation != expected.generation
        ) return
        resumePrefs.edit().remove(KEY_SOURCE).remove(KEY_ACTIVE).commit()
        pendingResumeTaskState.value = null
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (pendingResumeTaskState.value == null) {
            pendingResumeTaskState.value = loadPendingResumeTask()
        }
        // 先显示首页首帧，再请求 Android 13+ 通知权限；避免权限弹窗抢在 setContent 前启动，
        // 让系统启动页一直停留，看起来像是应用启动很慢。
        setContent {
            TranslateTubeTheme {
                LaunchedEffect(Unit) {
                    // 等待两帧，确保主页至少完成一次绘制后再弹权限申请。
                    withFrameNanos { }
                    withFrameNanos { }
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                // Resolve deep links before the first draw so opening a shared video never flashes Home.
                val initialScreen = when {
                    playReq.value?.webPageUrl != null -> Screen.WEB
                    playReq.value != null -> Screen.PLAYER
                    else -> Screen.HOME
                }
                var screen by remember { mutableStateOf(initialScreen) }
                // Render only one screen at a time. A staged fade avoids keeping two PlayerScreens
                // alive together during AnimatedContent, which could duplicate playback side effects.
                var displayedScreen by remember { mutableStateOf(initialScreen) }
                val pageMotion = remember { Animatable(1f) }
                var hasInitializedScreenMotion by remember { mutableStateOf(false) }
                LaunchedEffect(screen) {
                    if (!hasInitializedScreenMotion) {
                        hasInitializedScreenMotion = true
                        displayedScreen = screen
                        pageMotion.snapTo(1f)
                        return@LaunchedEffect
                    }
                    // Short exit, then a slightly longer entrance with a subtle vertical drift.
                    // The old route is disposed before the next one enters (important for PlayerScreen).
                    pageMotion.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 105,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                    displayedScreen = screen
                    pageMotion.snapTo(0.01f)
                    pageMotion.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 225,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                var previousScreen by remember { mutableStateOf(Screen.HOME) }
                var logsReturnScreen by remember { mutableStateOf(Screen.SETTINGS) }
                val req by playReq
                val pendingTask by pendingResumeTaskState
                val playback by PlaybackService.ui.collectAsState()
                var startedPendingGeneration by remember { mutableStateOf<Long?>(null) }

                // Clear a persisted recovery entry only after the matching run reaches READY.
                LaunchedEffect(pendingTask?.generation, playback.key, playback.phase) {
                    val task = pendingTask
                    if (task == null) {
                        startedPendingGeneration = null
                        return@LaunchedEffect
                    }
                    if (startedPendingGeneration != task.generation) startedPendingGeneration = null
                    if (playback.key != task.activeKey) return@LaunchedEffect
                    when (playback.phase) {
                        Phase.PARSING,
                        Phase.DOWNLOADING_AUDIO,
                        Phase.TRANSCRIBING,
                        Phase.TRANSLATING,
                        Phase.SYNTHESIZING -> startedPendingGeneration = task.generation
                        Phase.READY -> if (startedPendingGeneration == task.generation) {
                            clearPendingResumeTask(task)
                            startedPendingGeneration = null
                        }
                        else -> Unit
                    }
                }

                // 立即展示主界面；启动品牌画面由 Android windowBackground / 系统启动页负责.
                // 不再叠加 Compose 启动页或固定延时，减少冷启动等待。
                LaunchedEffect(req) {
                    when {
                        req?.webPageUrl != null -> screen = Screen.WEB
                        req != null -> screen = Screen.PLAYER
                    }
                }

                // 全局大屏适配：内容限宽居中（平板/折叠屏不拉伸变形）
                Box(
                    Modifier.fillMaxSize().background(BgDark),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = 720.dp)
                            .graphicsLayer {
                                alpha = pageMotion.value
                                translationY = (1f - pageMotion.value) * 10.dp.toPx()
                            },
                    ) {
                        when (displayedScreen) {
                            Screen.HOME -> HomeScreen(
                                onPlay = { r -> rememberPlayRequest(r) },
                                onSettings = { previousScreen = Screen.HOME; screen = Screen.SETTINGS },
                                pendingResumeTaskKey = pendingTask?.sourceKey,
                                onResumeTask = { taskKey ->
                                    val request = when {
                                        taskKey.startsWith("youtube:") -> PlayRequest(videoId = taskKey.removePrefix("youtube:"))
                                        taskKey.startsWith("webpage:") -> PlayRequest(webPageUrl = taskKey.removePrefix("webpage:"))
                                        taskKey.startsWith("remote:") -> PlayRequest(mediaUrl = taskKey.removePrefix("remote:"))
                                        taskKey.startsWith("local:") -> PlayRequest(localUri = taskKey.removePrefix("local:"))
                                        else -> null
                                    }
                                    if (request != null) {
                                        rememberPlayRequest(request)
                                        screen = if (request.webPageUrl != null) Screen.WEB else Screen.PLAYER
                                    }
                                },
                            )
                            Screen.PLAYER -> PlayerScreen(
                                videoId = req?.videoId,
                                localUri = req?.localUri?.let(Uri::parse),
                                mediaUrl = req?.mediaUrl,
                                mediaTitle = req?.mediaTitle.orEmpty(),
                                mediaReferrer = req?.mediaReferrer.orEmpty(),
                                mediaCookie = req?.mediaCookie,
                                mediaUserAgent = req?.mediaUserAgent,
                                requestToken = req?.ts ?: 0L,
                                onBack = { screen = Screen.HOME },
                                onGoSettings = { previousScreen = Screen.PLAYER; screen = Screen.SETTINGS },
                            )
                            Screen.WEB -> WebVideoScreen(
                                initialUrl = req?.webPageUrl.orEmpty(),
                                onBack = { playReq.value = null; screen = Screen.HOME },
                                onOpenMedia = { url, title, referrer, cookie, userAgent ->
                                    advancePlayRequest(
                                        PlayRequest(
                                            mediaUrl = url,
                                            mediaTitle = title,
                                            mediaReferrer = referrer,
                                            mediaCookie = cookie,
                                            mediaUserAgent = userAgent,
                                        )
                                    )
                                    screen = Screen.PLAYER
                                },
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                onBack = { screen = previousScreen },
                                onApiManager = { screen = Screen.API },
                                onAppLogs = {
                                    logsReturnScreen = Screen.SETTINGS
                                    screen = Screen.LOGS
                                },
                                onStorageManagement = { screen = Screen.STORAGE },
                            )
                            Screen.API -> ApiManagerScreen(onBack = { screen = Screen.SETTINGS })
                            Screen.LOGS -> AppLogsScreen(onBack = { screen = logsReturnScreen })
                            Screen.STORAGE -> StorageManagementScreen(
                                onBack = { screen = Screen.SETTINGS },
                                onOpenLogs = {
                                    logsReturnScreen = Screen.STORAGE
                                    screen = Screen.LOGS
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val id = NewPipeRepo.extractVideoId(text)
                    ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(text.trim())) text.trim() else null
                if (id != null) {
                    rememberPlayRequest(PlayRequest(videoId = id))
                } else {
                    io.translatetube.local.web.WebMediaUrlPolicy.normalizePageUrl(text)?.let { url ->
                        rememberPlayRequest(PlayRequest(webPageUrl = url))
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme == "content" || uri.scheme == "file") {
                    // 拿持久读授权：播放服务稍后跨组件访问该 uri
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    rememberPlayRequest(PlayRequest(localUri = uri.toString()))
                } else {
                    val id = NewPipeRepo.extractVideoId(uri.toString())
                    if (id != null) {
                        rememberPlayRequest(PlayRequest(videoId = id))
                    } else {
                        io.translatetube.local.web.WebMediaUrlPolicy.normalizePageUrl(uri.toString())?.let { url ->
                            rememberPlayRequest(PlayRequest(webPageUrl = url))
                        }
                    }
                }
            }
        }
    }
    
    private companion object {
        const val RESUME_PREFS = "pending_video_resume"
        const val KEY_SOURCE = "source_key"
        const val KEY_ACTIVE = "active_key"
        const val KEY_GENERATION = "generation"
    }
}

