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

enum class Screen { HOME, WEB_SOURCE, PLAYER, SETTINGS, API }

/** YouTube、本地文件、网页 URL 与已发现的媒体流共用一个播放请求。 */
data class PlayRequest(
    val videoId: String? = null,
    val localUri: String? = null,
    val webPageUrl: String? = null,
    val mediaHeaders: Map<String, String> = emptyMap(),
    val ts: Long = System.currentTimeMillis(), // 保证同视频重复点击也能触发
) {
    val key: String get() = when {
        videoId != null -> "youtube:$videoId"
        !webPageUrl.isNullOrBlank() -> "webpage:$webPageUrl"
        localUri?.startsWith("http://", true) == true ||
            localUri?.startsWith("https://", true) == true -> "webmedia:$localUri"
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
        // Android 13+ 通知运行时权限（媒体控制通知需要）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TranslateTubeTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var previousScreen by remember { mutableStateOf(Screen.HOME) }
                val req by playReq

                // 立即展示主界面；启动品牌画面由 Android windowBackground / 系统启动页负责。
                // 不再叠加 Compose 启动页或固定延时，减少冷启动等待。
                LaunchedEffect(req) {
                    when {
                        !req?.webPageUrl.isNullOrBlank() -> screen = Screen.WEB_SOURCE
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
                                        taskKey.startsWith("local:") -> PlayRequest(localUri = taskKey.removePrefix("local:"))
                                        taskKey.startsWith("webmedia:") -> PlayRequest(localUri = taskKey.removePrefix("webmedia:"))
                                        else -> null
                                    }
                                    if (request != null) {
                                        playReq.value = request
                                        screen = Screen.PLAYER
                                    }
                                },
                            )
                            Screen.WEB_SOURCE -> WebSourceScreen(
                                pageUrl = req?.webPageUrl.orEmpty(),
                                onBack = { screen = Screen.HOME },
                                onUseMedia = { mediaUrl, headers ->
                                    playReq.value = PlayRequest(localUri = mediaUrl, mediaHeaders = headers)
                                    screen = Screen.PLAYER
                                },
                            )
                            Screen.PLAYER -> PlayerScreen(
                                videoId = req?.videoId,
                                localUri = req?.localUri?.let(Uri::parse),
                                remoteHeaders = req?.mediaHeaders.orEmpty(),
                                onBack = { screen = Screen.HOME },
                                onGoSettings = { previousScreen = Screen.PLAYER; screen = Screen.SETTINGS },
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
                    io.translatetube.local.web.WebMediaCandidatePolicy.normalizePageUrl(text)?.let {
                        playReq.value = PlayRequest(webPageUrl = it)
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
                        io.translatetube.local.web.WebMediaCandidatePolicy.normalizePageUrl(uri.toString())?.let {
                            playReq.value = PlayRequest(webPageUrl = it)
                        }
                    }
                }
            }
        }
    }
}

