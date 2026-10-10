package io.translatetube.local.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.withContext

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
    var tab by remember { mutableStateOf(0) } // 0 历史 1 收藏
    var linkError by remember { mutableStateOf(false) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPlay(PlayRequest(localUri = it.toString()))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        // ===== 标题栏 =====
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.titleLarge)
                Text("外文视频 · 中文配音 · 全本地", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
            }
            Text("⚙", color = SubText, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSettings() }.padding(8.dp))
        }

        Spacer(Modifier.height(14.dp))

        // ===== 链接输入 =====
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; if (linkError) linkError = false },
                modifier = Modifier.weight(1f),
                placeholder = { Text("粘贴 YouTube 链接或视频ID", color = MutedGreen) },
                isError = linkError,
                supportingText = if (linkError) {
                    { Text("无法识别的视频链接，试试完整链接或 11 位视频ID", color = WarnYellow) }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand, unfocusedBorderColor = SurfaceHigh,
                    focusedTextColor = OnBg, unfocusedTextColor = OnBg,
                    cursorColor = BrandBright,
                ),
            )
            Spacer(Modifier.width(8.dp))
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
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) { Text("观看") }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 本地视频入口 =====
        AssistChip(
            onClick = { localPicker.launch(arrayOf("video/*")) },
            label = { Text("📂 打开本地视频（支持配音/转写/导入字幕）") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = SurfaceDark, labelColor = OnBg,
                leadingIconContentColor = BrandBright,
            ),
        )

        Spacer(Modifier.height(12.dp))

        // ===== Tab =====
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("最近") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SurfaceHigh, selectedLabelColor = BrandBright))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("收藏 ★") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SurfaceHigh, selectedLabelColor = BrandBright))
        }

        Spacer(Modifier.height(8.dp))

        val list = if (tab == 0) videos else videos.filter { it.isFavorite }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    if (tab == 0) "暂无观看记录\n粘贴链接或打开本地视频开始" else "长按列表项可加入收藏",
                    color = MutedGreen, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.key }) { v ->
                    VideoRow(v, onPlay = onPlay, onFav = {
                        scope.launch(Dispatchers.IO) { db.videoDao().setFavorite(v.key, !v.isFavorite) }
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    v: VideoEntity,
    onPlay: (PlayRequest) -> Unit,
    onFav: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .combinedClickable(
                onClick = {
                    onPlay(
                        if (v.sourceType == io.translatetube.local.data.SourceType.YOUTUBE)
                            PlayRequest(videoId = v.videoId ?: v.key.removePrefix("youtube:"))
                        else PlayRequest(localUri = v.localUri ?: v.key.removePrefix("local:"))
                    )
                },
                onLongClick = onFav,
            )
            .padding(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = v.thumbUrl?.takeIf { it.isNotBlank() } ?: v.localUri,
            contentDescription = null,
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceHigh),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title.ifEmpty { v.key }, color = OnBg, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    v.channel.ifEmpty { if (v.sourceType == io.translatetube.local.data.SourceType.YOUTUBE) "YouTube" else "本地视频" },
                    color = SubText, style = MaterialTheme.typography.bodySmall,
                )
                if (v.durationSec > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(SubtitleParsers.fmtTime(v.durationSec * 1000), color = MutedGreen, style = MaterialTheme.typography.bodySmall)
                }
                if (v.isFavorite) { Spacer(Modifier.width(6.dp)); Text("★", color = WarnYellow, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
