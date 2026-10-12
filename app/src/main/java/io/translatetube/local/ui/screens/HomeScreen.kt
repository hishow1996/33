package io.translatetube.local.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.translatetube.local.PlayRequest
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val videos by db.videoDao().all().collectAsState(initial = emptyList())
    var link by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var linkError by remember { mutableStateOf(false) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPlay(PlayRequest(localUri = it.toString()))
        }
    }

    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = io.translatetube.local.R.drawable.splash_logo),
                    contentDescription = "大象翻译图标",
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.titleLarge)
                Text("让外语视频自然地说中文", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = SubText)
            }
        }

        Text("开始观看", color = OnBg, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(5.dp))
        Text("粘贴链接，开始字幕翻译与中文配音", color = SubText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; linkError = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("粘贴 YouTube 链接或视频 ID") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = SubText) },
                    isError = linkError,
                    supportingText = if (linkError) {
                        { Text("链接格式不正确，请检查后重试") }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand,
                        unfocusedBorderColor = Color(0xFFE0E7F0),
                        focusedTextColor = OnBg,
                        unfocusedTextColor = OnBg,
                        cursorColor = BrandBright,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val id = io.translatetube.local.yt.NewPipeRepo.extractVideoId(link)
                            ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(link.trim())) link.trim() else null
                        if (id != null) {
                            linkError = false
                            onPlay(PlayRequest(videoId = id))
                            link = ""
                        } else linkError = true
                    },
                    enabled = link.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("观看并翻译", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { localPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE5F1)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnBg),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BrandBright)
                    Spacer(Modifier.width(8.dp))
                    Text("打开本地视频")
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("视频记录", color = OnBg, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(11.dp)).background(Color(0xFFE8EEF7)).padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeTab("最近", selected = tab == 0) { tab = 0 }
                HomeTab("收藏", selected = tab == 1) { tab = 1 }
            }
        }
        Spacer(Modifier.height(12.dp))

        val list = if (tab == 0) videos else videos.filter { it.isFavorite }
        if (list.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFE8F0FC)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (tab == 0) Icons.Filled.PlayArrow else Icons.Filled.Star,
                            contentDescription = null,
                            tint = BrandBright,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (tab == 0) "还没有观看记录" else "还没有收藏视频", color = OnBg, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (tab == 0) "从上方粘贴链接，开始第一段视频" else "在视频记录上长按，即可添加或取消收藏",
                        color = SubText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.key }) { video ->
                    VideoRow(
                        video = video,
                        onPlay = onPlay,
                        onFav = {
                            scope.launch(Dispatchers.IO) {
                                db.videoDao().setFavorite(video.key, !video.isFavorite)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            color = if (selected) BrandBright else SubText,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: VideoEntity,
    onPlay: (PlayRequest) -> Unit,
    onFav: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(SurfaceDark)
            .combinedClickable(
                onClick = {
                    onPlay(
                        if (video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE)
                            PlayRequest(videoId = video.videoId ?: video.key.removePrefix("youtube:"))
                        else PlayRequest(localUri = video.localUri ?: video.key.removePrefix("local:"))
                    )
                },
                onLongClick = onFav,
            )
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = video.thumbUrl?.takeIf { it.isNotBlank() } ?: video.localUri,
            contentDescription = null,
            modifier = Modifier.width(104.dp).height(62.dp)
                .clip(RoundedCornerShape(10.dp)).background(SurfaceHigh),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title.ifEmpty { video.key },
                color = OnBg,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    video.channel.ifEmpty {
                        if (video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE) "YouTube" else "本地视频"
                    },
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                if (video.durationSec > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(SubtitleParsers.fmtTime(video.durationSec * 1000), color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                if (video.isFavorite) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = Color(0xFFE4A11B), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
