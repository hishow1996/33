package io.translatetube.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import io.translatetube.local.web.SharedLinkTextParser

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
        // Initialize defaults only when a preference has never been saved. Explicit user choices
        // are preserved across upgrades: Google Translate + Microsoft Edge TTS are the defaults.
        val enginePrefs = getSharedPreferences("settings", MODE_PRIVATE)
        enginePrefs.edit().also { editor ->
            if (!enginePrefs.contains("active_api_id")) {
                editor.putLong("active_api_id", io.translatetube.local.translate.EngineFactory.ID_GOOGLE)
            }
            if (!enginePrefs.contains("tts_engine")) {
                editor.putString("tts_engine", "edge")
            }
        }.apply()
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
                // Render only one screen at a time. Do not fade the old route to zero:
                // replacing it at alpha=0 and restarting at alpha=0.01 creates a visible blank page.
                // 所有页面使用固定尺寸的根容器，避免不同页面内容高度变化带动整页跳动。
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

                    // 直接切换目标页面，只做轻微淡入；不再先移动旧页面或重置垂直偏移。
                    displayedScreen = screen
                    pageMotion.snapTo(0.96f)
                    pageMotion.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 140,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                var previousScreen by remember { mutableStateOf(Screen.HOME) }
                var logsReturnScreen by remember { mutableStateOf(Screen.SETTINGS) }
                // Preserve the source page independently from the temporary media URL selected
                // for playback so returning from parsing never launches source detection again.
                var webDetectionUrl by remember { mutableStateOf(playReq.value?.webPageUrl) }
                var returnToWebAfterPlayer by remember { mutableStateOf(false) }
                var lastHomeBackPressedAt by remember { mutableLongStateOf(0L) }
                val req by playReq
                val pendingTask by pendingResumeTaskState
                val playback by PlaybackService.ui.collectAsState()
                var startedPendingGeneration by remember { mutableStateOf<Long?>(null) }

                LaunchedEffect(req?.webPageUrl) {
                    req?.webPageUrl?.let { url ->
                        webDetectionUrl = url
                        returnToWebAfterPlayer = false
                    }
                }

                // Use one back policy for the phone gesture and hardware button. On Home, a
                // deliberate second back within two seconds exits instead of accidental closing.
                BackHandler {
                    when (screen) {
                        Screen.PLAYER -> {
                            PlaybackService.instance?.saveProgress()
                            screen = if (returnToWebAfterPlayer && !webDetectionUrl.isNullOrBlank()) {
                                Screen.WEB
                            } else {
                                Screen.HOME
                            }
                        }
                        Screen.WEB -> screen = Screen.HOME
                        Screen.SETTINGS -> screen = previousScreen
                        Screen.API -> screen = Screen.SETTINGS
                        Screen.LOGS -> screen = logsReturnScreen
                        Screen.STORAGE -> screen = Screen.SETTINGS
                        Screen.HOME -> {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (lastHomeBackPressedAt > 0L && now - lastHomeBackPressedAt <= 2_000L) {
                                this@MainActivity.finish()
                            } else {
                                lastHomeBackPressedAt = now
                                Toast.makeText(
                                    this@MainActivity,
                                    "再返回一次退出应用",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }

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
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = pageMotion.value
                            },
                    ) {
                        // Keep the WebView alive beneath both the player and Home. Returning Home
                        // only hides/pauses the page; it must not destroy the WebView, JS session,
                        // detected source cards, or current in-page navigation state.
                        val keepWebDetectionMounted = displayedScreen == Screen.WEB ||
                            (
                                !webDetectionUrl.isNullOrBlank() &&
                                    (
                                        displayedScreen == Screen.HOME ||
                                            (
                                                returnToWebAfterPlayer &&
                                                    displayedScreen != Screen.HOME &&
                                                    (displayedScreen == Screen.PLAYER || previousScreen == Screen.PLAYER)
                                            )
                                    )
                            )
                        if (keepWebDetectionMounted) {
                            // Recreate only when the user submits a different start URL. The same
                            // URL keeps its existing WebView even if the user browsed to another page.
                            key(webDetectionUrl) {
                                WebVideoScreen(
                                    initialUrl = webDetectionUrl.orEmpty(),
                                    visible = displayedScreen == Screen.WEB,
                                    onBack = { screen = Screen.HOME },
                                    onOpenMedia = { url, title, referrer, cookie, userAgent ->
                                        returnToWebAfterPlayer = true
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
                            }
                        }

                        when (displayedScreen) {
                            Screen.HOME -> HomeScreen(
                                onPlay = { request ->
                                    returnToWebAfterPlayer = false
                                    webDetectionUrl = request.webPageUrl
                                    rememberPlayRequest(request)
                                },
                                onSettings = { previousScreen = Screen.HOME; screen = Screen.SETTINGS },
                                pendingResumeTaskKey = pendingTask?.sourceKey,
                                onResumeTask = { taskKey ->
                                    val live = PlaybackService.ui.value
                                    val currentRequest = playReq.value
                                    val savedTask = pendingResumeTaskState.value ?: loadPendingResumeTask()
                                    val belongsToExistingRun = live.phase != Phase.IDLE &&
                                        live.key.isNotBlank() &&
                                        currentRequest?.key == live.key &&
                                        (
                                            taskKey == live.key ||
                                                taskKey == savedTask?.sourceKey ||
                                                taskKey == savedTask?.activeKey
                                            )
                                    if (belongsToExistingRun) {
                                        // Never mint a new request token for a live run; doing so can
                                        // restart parsing and send the user to source detection again.
                                        screen = Screen.PLAYER
                                    } else {
                                        val request = when {
                                            taskKey.startsWith("youtube:") -> PlayRequest(videoId = taskKey.removePrefix("youtube:"))
                                            taskKey.startsWith("webpage:") -> PlayRequest(webPageUrl = taskKey.removePrefix("webpage:"))
                                            taskKey.startsWith("remote:") -> PlayRequest(mediaUrl = taskKey.removePrefix("remote:"))
                                            taskKey.startsWith("local:") -> PlayRequest(localUri = taskKey.removePrefix("local:"))
                                            else -> null
                                        }
                                        if (request != null) {
                                            returnToWebAfterPlayer = false
                                            webDetectionUrl = request.webPageUrl
                                            rememberPlayRequest(request)
                                            screen = if (request.webPageUrl != null) Screen.WEB else Screen.PLAYER
                                        }
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
                                onBack = {
                                    screen = if (returnToWebAfterPlayer && !webDetectionUrl.isNullOrBlank()) {
                                        Screen.WEB
                                    } else {
                                        Screen.HOME
                                    }
                                },
                                onGoSettings = { previousScreen = Screen.PLAYER; screen = Screen.SETTINGS },
                            )
                            Screen.WEB -> Unit
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
                val input = SharedLinkTextParser.extractFirstHttpUrl(text) ?: text.trim()
                val id = NewPipeRepo.extractVideoId(input)
                    ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(input)) input else null
                if (id != null) {
                    rememberPlayRequest(PlayRequest(videoId = id))
                } else {
                    io.translatetube.local.web.WebMediaUrlPolicy.normalizePageUrl(input)?.let { url ->
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

