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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.translatetube.local.ui.screens.*
import io.translatetube.local.ui.theme.BgDark
import io.translatetube.local.ui.theme.TranslateTubeTheme
import io.translatetube.local.yt.NewPipeRepo

enum class Screen { HOME, PLAYER, WEB, SETTINGS, API }

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

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
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
                var screen by remember { mutableStateOf(Screen.HOME) }
                var previousScreen by remember { mutableStateOf(Screen.HOME) }
                val req by playReq

                // 立即展示主界面；启动品牌画面由 Android windowBackground / 系统启动页负责。
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
                    Box(Modifier.widthIn(max = 720.dp)) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                onPlay = { r -> playReq.value = r },
                                onSettings = { previousScreen = Screen.HOME; screen = Screen.SETTINGS },
                                onResumeTask = { taskKey ->
                                    val request = when {
                                        taskKey.startsWith("youtube:") -> PlayRequest(videoId = taskKey.removePrefix("youtube:"))
                                        taskKey.startsWith("remote:") -> PlayRequest(mediaUrl = taskKey.removePrefix("remote:"))
                                        taskKey.startsWith("local:") -> PlayRequest(localUri = taskKey.removePrefix("local:"))
                                        else -> null
                                    }
                                    if (request != null) {
                                        playReq.value = request
                                        screen = Screen.PLAYER
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
                                onBack = { screen = Screen.HOME },
                                onGoSettings = { previousScreen = Screen.PLAYER; screen = Screen.SETTINGS },
                            )
                            Screen.WEB -> WebVideoScreen(
                                initialUrl = req?.webPageUrl.orEmpty(),
                                onBack = { playReq.value = null; screen = Screen.HOME },
                                onOpenMedia = { url, title, referrer, cookie, userAgent ->
                                    playReq.value = PlayRequest(
                                        mediaUrl = url,
                                        mediaTitle = title,
                                        mediaReferrer = referrer,
                                        mediaCookie = cookie,
                                        mediaUserAgent = userAgent,
                                    )
                                    screen = Screen.PLAYER
                                },
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                onBack = { screen = previousScreen },
                                onApiManager = { screen = Screen.API },
                            )
                            Screen.API -> ApiManagerScreen(onBack = { screen = Screen.SETTINGS })
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
                    playReq.value = PlayRequest(videoId = id)
                } else {
                    io.translatetube.local.web.WebMediaUrlPolicy.normalizePageUrl(text)?.let { url ->
                        playReq.value = PlayRequest(webPageUrl = url)
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
                    playReq.value = PlayRequest(localUri = uri.toString())
                } else {
                    val id = NewPipeRepo.extractVideoId(uri.toString())
                    if (id != null) {
                        playReq.value = PlayRequest(videoId = id)
                    } else {
                        io.translatetube.local.web.WebMediaUrlPolicy.normalizePageUrl(uri.toString())?.let { url ->
                            playReq.value = PlayRequest(webPageUrl = url)
                        }
                    }
                }
            }
        }
    }
}

