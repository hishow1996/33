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
@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val videos by db.videoDao().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
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
                Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Brand),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = io.translatetube.local.R.drawable.elephant_logo),
                    contentDescription = "大象翻译图标",
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.headlineSmall)
                Text("让外文视频听得懂", color = SubText, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(
                onClick = onSettings,
                color = Color.White,
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = SubText)
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
                Text("翻译一个视频", color = OnBg, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("粘贴 YouTube 视频链接", color = SubText, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; linkError = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://youtube.com/watch?v=…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = SubText) },
                    isError = linkError,
                    supportingText = if (linkError) {
                        { Text("链接格式不正确，请检查后重试") }
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
                Button(
                    onClick = {
                        val id = io.translatetube.local.yt.NewPipeRepo.extractVideoId(link)
                            ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(link.trim())) link.trim() else null
                        if (id != null) {
                            linkError = false
                            onPlay(PlayRequest(videoId = id))
                            link = ""
                        } else {
                            linkError = true
                        }
                    },
                    enabled = link.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
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
                if (favoritesOnly) "返回全部" else "全部",
                color = BrandBright,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    favoritesOnly = !favoritesOnly
                }.padding(horizontal = 4.dp, vertical = 5.dp),
            )
        }

        val shownVideos = if (favoritesOnly) videos.filter { it.isFavorite } else videos
        if (shownVideos.isEmpty()) {
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
                items(shownVideos, key = { it.key }) { video ->
                    VideoRow(
                        video = video,
                        onPlay = onPlay,
                        onFav = {
                            scope.launch(Dispatchers.IO) {
                                db.videoDao().setFavorite(video.key, !video.isFavorite)
                            }
                        },
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
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
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
                    if (video.sourceType == io.translatetube.local.data.SourceType.YOUTUBE) "YouTube" else "本地视频"
                }) + " · 中文配音" +
                    if (video.durationSec > 0) " · \${SubtitleParsers.fmtTime(video.durationSec * 1000)}" else "",
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
