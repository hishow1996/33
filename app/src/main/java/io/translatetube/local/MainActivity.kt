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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.translatetube.local.ui.screens.*
import io.translatetube.local.ui.theme.BgDark
import io.translatetube.local.ui.theme.TranslateTubeTheme
import io.translatetube.local.yt.NewPipeRepo

enum class Screen { HOME, PLAYER, SETTINGS, API }

/** 一次播放请求：YouTube 或 本地视频 */
data class PlayRequest(
    val videoId: String? = null,
    val localUri: String? = null,
    val ts: Long = System.currentTimeMillis(), // 保证同视频重复点击也能触发
) {
    val key: String get() = if (videoId != null) "youtube:$videoId" else "local:${localUri ?: ""}"
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
                var splashVisible by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) }
                val req by playReq

                // 展示品牌启动页后再进入首页/链接目标页；外部 YouTube 链接不会丢失。
                LaunchedEffect(Unit) {
                    // On Android 12+ the system splash page already contains the brand mark.
                    // Older Android versions use this Compose fallback, with no long fixed delay.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        delay(500)
                        splashVisible = false
                    }
                }
                LaunchedEffect(req) { if (req != null) screen = Screen.PLAYER }

                if (splashVisible) {
                    SplashScreen()
                } else {
                    // 全局大屏适配：内容限宽居中（平板/折叠屏不拉伸变形）
                    Box(
                        Modifier.fillMaxSize().background(BgDark),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.widthIn(max = 720.dp)) {
                            when (screen) {
                                Screen.HOME -> HomeScreen(
                                    onPlay = { r -> playReq.value = r },
                                    onSettings = { screen = Screen.SETTINGS },
                                )
                                Screen.PLAYER -> PlayerScreen(
                                    videoId = req?.videoId,
                                    localUri = req?.localUri?.let(Uri::parse),
                                    onBack = { screen = Screen.HOME },
                                    onGoSettings = { screen = Screen.SETTINGS },
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    onBack = { screen = Screen.HOME },
                                    onApiManager = { screen = Screen.API },
                                )
                                Screen.API -> ApiManagerScreen(onBack = { screen = Screen.SETTINGS })
                            }
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
                    ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(text)) text else null
                if (id != null) playReq.value = PlayRequest(videoId = id)
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
                    if (id != null) playReq.value = PlayRequest(videoId = id)
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_fg),
            contentDescription = "大象应用图标",
            modifier = Modifier
                .align(Alignment.Center)
                .size(176.dp),
            contentScale = ContentScale.Fit,
        )

        Text(
            text = "大象",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp),
            color = Color(0xFF18221D),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
        )
    }
}
