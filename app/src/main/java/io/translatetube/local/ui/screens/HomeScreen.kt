package io.translatetube.local.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.translatetube.local.PlayRequest
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.Phase
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.web.WebMediaUrlPolicy
import io.translatetube.local.web.SharedLinkTextParser
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onSettings: () -> Unit,
    pendingResumeTaskKey: String? = null,
    onResumeTask: (String) -> Unit = {},
) {
    val playback by PlaybackService.ui.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var db by remember(ctx) { mutableStateOf<AppDatabase?>(null) }
    var videos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 先完成首帧绘制，再在 IO 线程打开数据库并订阅历史记录；冷启动不被历史库初始化拖住。
    LaunchedEffect(ctx) {
        withFrameNanos { }
        val database = withContext(Dispatchers.IO) { AppDatabase.get(ctx) }
        db = database
        database.videoDao().all().collect { items ->
            videos = items
            historyLoaded = true
        }
    }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onPlay(PlayRequest(localUri = it.toString()))
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFF2F6FC))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = io.translatetube.local.R.drawable.elephant_logo),
                    contentDescription = "大象翻译图标",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.headlineSmall)
            }
            Box {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = SubText)
                }
                // yt-dlp 检测到新版本解析规则时提示红点；在设置里更新后消失
                val ytdlpHasUpdate by io.translatetube.local.yt.YtDlpRepo.updateState.collectAsState()
                if (ytdlpHasUpdate.hasUpdate) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5484D))
                    )
                }
            }
        }

        // The service state covers an in-process task; the saved key restores the entry after force-stop.
        val liveTaskKey = playback.key.takeIf { it.isNotBlank() && playback.phase != Phase.IDLE }
        val resumeTaskKey = when {
            playback.phase == Phase.ERROR && !pendingResumeTaskKey.isNullOrBlank() -> pendingResumeTaskKey
            liveTaskKey != null -> liveTaskKey
            else -> pendingResumeTaskKey?.takeIf { it.isNotBlank() }
        }
        if (resumeTaskKey != null) {
            val hasLiveTask = liveTaskKey != null
            val taskTitle = if (hasLiveTask) {
                when (playback.phase) {
                    Phase.ERROR -> "查看处理失败的任务"
                    Phase.READY -> "继续观看这个视频"
                    Phase.TRANSLATING -> "继续视频翻译"
                    Phase.SYNTHESIZING -> "继续生成中文配音"
                    Phase.TRANSCRIBING -> "继续识别视频语音"
                    Phase.DOWNLOADING_AUDIO -> "继续准备视频音轨"
                    else -> "继续解析视频"
                }
            } else {
                when {
                    resumeTaskKey.startsWith("youtube:") -> "重新解析 YouTube 视频"
                    resumeTaskKey.startsWith("webpage:") -> "继续解析网页视频"
                    resumeTaskKey.startsWith("remote:") -> "重新打开上次视频"
                    else -> "继续上次视频"
                }
            }
            val taskDetail = if (hasLiveTask) {
                when (playback.phase) {
                    Phase.ERROR -> playback.error?.takeIf { it.isNotBlank() } ?: "上次处理未完成，点击查看详情"
                    else -> playback.progress.takeIf { it.isNotBlank() } ?: when (playback.phase) {
                        Phase.READY -> "视频与字幕已准备好"
                        else -> "处理任务仍保存在播放服务中"
                    }
                }
            } else {
                when {
                    resumeTaskKey.startsWith("webpage:") -> resumeTaskKey.removePrefix("webpage:")
                    resumeTaskKey.startsWith("youtube:") -> "上次解析因应用退出而中断，点击后重新开始"
                    resumeTaskKey.startsWith("remote:") -> "上次视频处理被中断，点击重新打开"
                    else -> "上次视频处理被中断，点击继续"
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                onClick = { onResumeTask(resumeTaskKey) },
                color = Color(0xFFE6EFFF),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD4E2FA)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = BrandBright)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(taskTitle, color = OnBg, style = MaterialTheme.typography.titleMedium)
                        Text(
                            taskDetail,
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Filled.ArrowForwardIos,
                        contentDescription = if (hasLiveTask) "返回当前任务" else "恢复上次任务",
                        tint = BrandBright,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F3F8)),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; linkError = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("YouTube、网页视频地址或视频直链", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = SubText) },
                    isError = linkError,
                    supportingText = if (linkError) {
                        { Text("请输入有效的 YouTube、HTTP/HTTPS 网页地址或视频直链") }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(17.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandBright,
                        unfocusedBorderColor = OutlineSoft,
                        focusedContainerColor = Color(0xFFF4F7FC),
                        unfocusedContainerColor = Color(0xFFF4F7FC),
                        focusedTextColor = OnBg,
                        unfocusedTextColor = OnBg,
                        cursorColor = BrandBright,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                val playButtonInteraction = remember { MutableInteractionSource() }
                val playButtonPressed by playButtonInteraction.collectIsPressedAsState()
                val playButtonScale by animateFloatAsState(
                    targetValue = if (playButtonPressed) 0.975f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "homePlayButtonPress",
                )
                Button(
                    interactionSource = playButtonInteraction,
                    onClick = {
                        val rawInput = link.trim()
                        // Bilibili and other apps copy a title/description together with the URL.
                        // Extract the first HTTP(S) URL before routing it through existing parsers.
                        val input = SharedLinkTextParser.extractFirstHttpUrl(rawInput) ?: rawInput
                        val id = io.translatetube.local.yt.NewPipeRepo.extractVideoId(input)
                            ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(input)) input else null
                        if (id != null) {
                            linkError = false
                            onPlay(PlayRequest(videoId = id))
                            link = ""
                        } else {
                            // Direct MP4/HLS/audio links should enter playback directly; pages
                            // still go through WebView probing and yt-dlp fallback.
                            val mediaUrl = WebMediaUrlPolicy.normalizeDirectMediaUrl(input)
                            val pageUrl = if (mediaUrl == null) {
                                WebMediaUrlPolicy.normalizePageUrl(input)
                            } else {
                                null
                            }
                            when {
                                mediaUrl != null -> {
                                    linkError = false
                                    onPlay(PlayRequest(mediaUrl = mediaUrl))
                                    link = ""
                                }
                                pageUrl != null -> {
                                    linkError = false
                                    onPlay(PlayRequest(webPageUrl = pageUrl))
                                    link = ""
                                }
                                else -> linkError = true
                            }
                        }
                    },
                    enabled = link.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(58.dp).graphicsLayer {
                        scaleX = playButtonScale
                        scaleY = playButtonScale
                    },
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand,
                        contentColor = Color.White,
                        disabledContainerColor = Brand.copy(alpha = 0.5f),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("观看并翻译", style = MaterialTheme.typography.titleLarge)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .clickable { localPicker.launch(arrayOf("video/*")) }
                        .padding(horizontal = 6.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BrandBright)
                    Spacer(Modifier.width(12.dp))
                    Text("打开本地视频", color = OnBg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("›", color = SubText, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("观看记录", color = OnBg, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text(
                if (favoritesOnly) "全部记录" else "收藏",
                color = BrandBright,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    favoritesOnly = !favoritesOnly
                }.padding(horizontal = 4.dp, vertical = 5.dp),
            )
        }

        val shownVideos = if (favoritesOnly) videos.filter { it.isFavorite } else videos
        if (!historyLoaded) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = BrandBright,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在加载观看记录", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (shownVideos.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(62.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFFE3ECFB)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (favoritesOnly) Icons.Filled.Star else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = BrandBright,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (favoritesOnly) "还没有收藏视频" else "还没有观看记录",
                        color = OnBg,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (favoritesOnly) "长按视频记录即可收藏" else "从上方粘贴链接，开始第一段视频",
                        color = SubText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(shownVideos, key = { _, video -> video.key }) { _, video ->
                    VideoRow(
                        video = video,
                        onPlay = onPlay,
                        onFav = {
                            scope.launch(Dispatchers.IO) {
                                db?.videoDao()?.setFavorite(video.key, !video.isFavorite)
                            }
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 170),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            fadeOutSpec = tween(durationMillis = 120),
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: VideoEntity,
    onPlay: (PlayRequest) -> Unit,
    onFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .combinedClickable(
                onClick = {
                    onPlay(
                        when {
                            video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE ->
                                PlayRequest(videoId = video.videoId ?: video.key.removePrefix("youtube:"))
                            video.key.startsWith("remote:") ->
                                PlayRequest(mediaUrl = video.localUri ?: video.key.removePrefix("remote:"))
                            else ->
                                PlayRequest(localUri = video.localUri ?: video.key.removePrefix("local:"))
                        }
                    )
                },
                onLongClick = onFav,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = video.thumbUrl?.takeIf { it.isNotBlank() } ?: video.localUri,
            contentDescription = null,
            modifier = Modifier.size(84.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0xFFDCE8F9)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                video.title.ifEmpty { video.key },
                color = OnBg,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (video.channel.ifEmpty {
                    when { video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE -> "YouTube"; video.key.startsWith("remote:") -> "网页视频"; else -> "本地视频" }
                }) + " · 中文配音" +
                    if (video.durationSec > 0) " · " + SubtitleParsers.fmtTime(video.durationSec * 1000) else "",
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("继续观看", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Text("›", color = BrandBright, style = MaterialTheme.typography.titleMedium)
                if (video.isFavorite) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = Color(0xFFE3A008), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
