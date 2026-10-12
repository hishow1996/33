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
fun HomeScreen(onPlay: (PlayRequest) -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val videos by db.videoDao().all().collectAsState(initial = emptyList())
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPlay(PlayRequest(localUri = it.toString()))
        }
    }

    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Brand), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = io.translatetube.local.R.drawable.splash_logo),
                    contentDescription = "大象翻译",
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.headlineSmall)
                Text("让外文视频听得懂", color = SubText, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = SubText)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            color = Color(0xFFF2F6FC),
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Surface(color = Color.White, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("翻译一个视频", color = OnBg, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("粘贴 YouTube 视频链接", color = SubText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it; linkError = false },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://youtube.com/watch?v=…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = SubText) },
                            isError = linkError,
                            supportingText = if (linkError) ({ Text("链接格式不正确，请检查后重试") }) else null,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Brand, unfocusedBorderColor = OutlineSoft,
                                focusedTextColor = OnBg, unfocusedTextColor = OnBg, cursorColor = Brand,
                                focusedContainerColor = Color(0xFFF5F7FB), unfocusedContainerColor = Color(0xFFF5F7FB)
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val id = io.translatetube.local.yt.NewPipeRepo.extractVideoId(link)
                                    ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(link.trim())) link.trim() else null
                                if (id != null) { linkError = false; onPlay(PlayRequest(videoId = id)); link = "" }
                                else linkError = true
                            },
                            enabled = link.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("观看并翻译", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { localPicker.launch(arrayOf("video/*")) }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Brand, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("打开本地视频", color = OnBg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForwardIos, contentDescription = null, tint = MutedGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("观看记录", color = OnBg, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text(if (showAll) "收起" else "全部", color = BrandBright, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = !showAll }.padding(6.dp))
                }
                Spacer(Modifier.height(10.dp))
                val list = if (showAll) videos else videos.take(3)
                if (list.isEmpty()) {
                    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFDCE7F8)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Brand, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("最近观看的视频", color = OnBg, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("YouTube · 中文配音", color = SubText, style = MaterialTheme.typography.bodyMedium)
                                Text("开始观看后会显示在这里", color = SubText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(list, key = { it.key }) { video ->
                            VideoRow(video = video, onPlay = onPlay, onFav = {
                                scope.launch(Dispatchers.IO) { db.videoDao().setFavorite(video.key, !video.isFavorite) }
                            })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(video: VideoEntity, onPlay: (PlayRequest) -> Unit, onFav: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
            .combinedClickable(
                onClick = {
                    onPlay(if (video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE)
                        PlayRequest(videoId = video.videoId ?: video.key.removePrefix("youtube:"))
                    else PlayRequest(localUri = video.localUri ?: video.key.removePrefix("local:")))
                },
                onLongClick = onFav,
            ).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = video.thumbUrl?.takeIf { it.isNotBlank() } ?: video.localUri,
            contentDescription = null,
            modifier = Modifier.size(width = 90.dp, height = 68.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFDCE7F8)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title.ifEmpty { video.key }, color = OnBg, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(video.channel.ifEmpty { if (video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE) "YouTube" else "本地视频" },
                color = SubText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (video.durationSec > 0) Text(SubtitleParsers.fmtTime(video.durationSec * 1000), color = SubText, style = MaterialTheme.typography.bodySmall)
        }
        if (video.isFavorite) Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = Color(0xFFE4A11B))
    }
}
