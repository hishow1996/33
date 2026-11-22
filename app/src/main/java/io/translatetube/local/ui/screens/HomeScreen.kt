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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.translatetube.local.PlayRequest
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.Phase
import io.translatetube.local.player.ProcessingErrorDisplayPolicy
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

/** 用户已经粘贴、忽略或提交过的剪贴板链接；进程存活期间不再重复提示同一条。 */
private var handledClipboardUrl: String? = null

@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit = {},
    pendingResumeTaskKey: String? = null,
    pendingResumeTaskPhase: Phase? = null,
    pendingResumeTaskProgress: String? = null,
    onResumeTask: (String) -> Unit = {},
) {
    val playback by PlaybackService.ui.collectAsStateWithLifecycle()
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
    var linkSyncRevision by remember { mutableStateOf(0L) }
    var linkFieldHasFocus by remember { mutableStateOf(false) }
    var linkEditText by remember { mutableStateOf<NativeAddressBarEditText?>(null) }

    // 回到首页时看一眼剪贴板，里面有能处理的视频链接就在地址栏上方提示一键粘贴。
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    val homeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(homeLifecycleOwner, ctx) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    // Android 10 以上要等窗口拿到焦点才允许读剪贴板，稍等一下再读。
                    kotlinx.coroutines.delay(300)
                    val clipText = runCatching {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        if (clipboard?.primaryClipDescription?.hasMimeType("text/*") == true) {
                            clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        } else {
                            null
                        }
                    }.getOrNull()
                    clipboardUrl = ClipboardLinkPolicy.candidate(clipText, handledClipboardUrl)
                }
            }
        }
        homeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { homeLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val addressDensity = LocalDensity.current
    val addressBarHeight = maxOf(56f, 16f * addressDensity.fontScale + 40f).dp

    fun setLink(value: String) {
        link = value
        linkSyncRevision += 1L
        linkEditText?.syncExternalText(value, linkSyncRevision)
    }

    fun submitLink() {
        // Read the native editor directly so submission always uses the latest draft.
        val rawInput = (linkEditText?.text?.toString() ?: link).trim()
        // Bilibili and other apps copy a title/description together with the URL.
        // Extract the first HTTP(S) URL before routing it through existing parsers.
        val input = SharedLinkTextParser.extractFirstHttpUrl(rawInput) ?: rawInput
        val id = io.translatetube.local.yt.NewPipeRepo.extractVideoId(input)
            ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(input)) input else null
        if (id != null) {
            linkError = false
            handledClipboardUrl = input
            clipboardUrl = null
            onPlay(PlayRequest(videoId = id))
            setLink("")
            return
        }

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
                handledClipboardUrl = input
                clipboardUrl = null
                onPlay(PlayRequest(mediaUrl = mediaUrl))
                setLink("")
            }
            pageUrl != null -> {
                linkError = false
                handledClipboardUrl = input
                clipboardUrl = null
                onPlay(PlayRequest(webPageUrl = pageUrl))
                setLink("")
            }
            else -> linkError = true
        }
    }

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
                Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = io.translatetube.local.R.drawable.elephant_mascot_cutout),
                    contentDescription = "小象",
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
                val ytdlpHasUpdate by io.translatetube.local.yt.YtDlpRepo.updateState.collectAsStateWithLifecycle()
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
            val savedStageLabel = when (pendingResumeTaskPhase) {
                Phase.PARSING -> "视频解析"
                Phase.DOWNLOADING_AUDIO -> "音轨准备"
                Phase.TRANSCRIBING -> "语音转写"
                Phase.TRANSLATING -> "字幕翻译"
                Phase.SYNTHESIZING -> "配音合成"
                Phase.READY -> "处理完成"
                Phase.ERROR -> "处理失败"
                else -> null
            }
            val taskTitle = if (hasLiveTask) {
                when (playback.phase) {
                    Phase.ERROR -> if (playback.failedPhase == Phase.PARSING) "重新解析视频" else "重试上次处理"
                    Phase.READY -> "继续观看这个视频"
                    Phase.TRANSLATING -> "继续视频翻译"
                    Phase.SYNTHESIZING -> "继续生成中文配音"
                    Phase.TRANSCRIBING -> "继续识别视频语音"
                    Phase.DOWNLOADING_AUDIO -> "继续准备视频音轨"
                    else -> "继续解析视频"
                }
            } else {
                when (pendingResumeTaskPhase) {
                    Phase.PARSING -> "继续视频解析"
                    Phase.DOWNLOADING_AUDIO -> "继续准备视频音轨"
                    Phase.TRANSCRIBING -> "继续语音转写"
                    Phase.TRANSLATING -> "继续字幕翻译"
                    Phase.SYNTHESIZING -> "继续配音合成"
                    Phase.READY -> "继续观看这个视频"
                    Phase.ERROR -> "重试上次处理"
                    else -> when {
                        resumeTaskKey.startsWith("youtube:") -> "重新解析 YouTube 视频"
                        resumeTaskKey.startsWith("webpage:") -> "继续解析网页视频"
                        resumeTaskKey.startsWith("remote:") -> "重新打开上次视频"
                        else -> "继续上次视频"
                    }
                }
            }
            val taskDetail = if (hasLiveTask) {
                when (playback.phase) {
                    Phase.ERROR -> ProcessingErrorDisplayPolicy.message(
                        playback.key,
                        playback.failedPhase,
                        playback.failedProgress,
                        playback.error,
                    )
                    else -> playback.progress.takeIf { it.isNotBlank() } ?: when (playback.phase) {
                        Phase.READY -> "视频与字幕已准备好"
                        else -> "处理任务仍保存在播放服务中"
                    }
                }
            } else {
                pendingResumeTaskProgress?.takeIf { it.isNotBlank() } ?: when {
                    savedStageLabel != null -> "上次停在${savedStageLabel}阶段，继续时复用已保存进度"
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

        val suggestedUrl = clipboardUrl
        if (suggestedUrl != null && link.isBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                color = Color(0xFFEAF2FF),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = BrandBright,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "剪贴板：${ClipboardLinkPolicy.kind(suggestedUrl).title}",
                            color = OnBg,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            ClipboardLinkPolicy.label(suggestedUrl),
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            handledClipboardUrl = suggestedUrl
                            clipboardUrl = null
                            setLink(suggestedUrl)
                        },
                    ) { Text("粘贴") }
                    IconButton(
                        onClick = {
                            handledClipboardUrl = suggestedUrl
                            clipboardUrl = null
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "忽略这个链接", tint = SubText)
                    }
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
                val addressBarShape = RoundedCornerShape(17.dp)
                val addressBarOutline = when {
                    linkError -> MaterialTheme.colorScheme.error
                    linkFieldHasFocus -> BrandBright
                    else -> OutlineSoft
                }
                val addressBarAppearance = NativeAddressBarAppearance(
                    textColor = OnBg.toArgb(),
                    hintColor = SubText.toArgb(),
                    // The Compose container owns the shared outline; keep the native editor
                    // transparent so the icon and text sit inside one continuous field.
                    backgroundColor = android.graphics.Color.TRANSPARENT,
                    outlineColor = addressBarOutline.toArgb(),
                    cornerRadiusPx = with(addressDensity) { 17.dp.toPx() },
                    strokeWidthPx = 0,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(addressBarHeight)
                        .clip(addressBarShape)
                        .background(Color(0xFFF4F7FC))
                        .border(1.dp, addressBarOutline, addressBarShape),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(48.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = SubText)
                    }
                    AndroidView(
                        factory = { context ->
                            NativeAddressBarEditText(context).apply {
                                linkEditText = this
                                hint = "YouTube、网页视频地址或视频直链"
                                applyAppearance(addressBarAppearance)
                                applyTextMetrics(
                                    textSizeSp = 16f,
                                    paddingLeftPx = 0,
                                    paddingRightPx = with(addressDensity) { 14.dp.roundToPx() },
                                )
                                onAddressTextEdited = {
                                    link = text?.toString().orEmpty()
                                    if (linkError) linkError = false
                                }
                                onAddressFocusChanged = { hasFocus ->
                                    linkFieldHasFocus = hasFocus
                                }
                                onAddressAction = { submitLink() }
                                syncExternalText(link, linkSyncRevision)
                            }
                        },
                        update = { editText ->
                            editText.applyAppearance(addressBarAppearance)
                            editText.applyTextMetrics(
                                textSizeSp = 16f,
                                paddingLeftPx = 0,
                                paddingRightPx = with(addressDensity) { 14.dp.roundToPx() },
                            )
                            editText.syncExternalText(link, linkSyncRevision)
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (linkError) {
                    Text(
                        "请输入有效的 YouTube、HTTP/HTTPS 网页地址或视频直链",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                    onClick = { submitLink() },
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
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { localPicker.launch(arrayOf("video/*")) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = "打开本地视频",
                            tint = BrandBright,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("观看记录", color = OnBg, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onHistory,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.History, contentDescription = "历史记录", tint = SubText)
            }
        }

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
        } else if (videos.isEmpty()) {
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
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = BrandBright,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有观看记录",
                        color = OnBg,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "从上方粘贴链接，开始第一段视频",
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
                itemsIndexed(videos, key = { _, video -> video.key }) { _, video ->
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
                    val historyRequest = when {
                        video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE ->
                            PlayRequest(videoId = video.videoId ?: video.key.removePrefix("youtube:"))
                        video.key.startsWith("remote:") ->
                            PlayRequest(mediaUrl = video.localUri ?: video.key.removePrefix("remote:"))
                        else ->
                            PlayRequest(localUri = video.localUri ?: video.key.removePrefix("local:"))
                    }
                    onPlay(historyRequest.copy(preferReadyHistoryResume = true))
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
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "已收藏",
                        tint = Color(0xFFE3A008),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
