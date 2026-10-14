package io.translatetube.local.ui.screens

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.translatetube.local.ui.theme.Brand
import io.translatetube.local.ui.theme.BrandBright
import io.translatetube.local.ui.theme.OnBg
import io.translatetube.local.ui.theme.SubText
import io.translatetube.local.web.WebMediaCandidate
import io.translatetube.local.web.WebMediaCandidatePolicy
import io.translatetube.local.web.WebMediaKind
import org.json.JSONArray
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

@Composable
fun WebSourceScreen(
    pageUrl: String,
    onBack: () -> Unit,
    onUseMedia: (String, Map<String, String>, String?) -> Unit,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val discovered = remember(pageUrl) { ConcurrentHashMap<String, WebMediaCandidate>() }
    var candidates by remember(pageUrl) { mutableStateOf(emptyList<WebMediaCandidate>()) }
    var currentPage by remember(pageUrl) { mutableStateOf(pageUrl) }
    var loading by remember(pageUrl) { mutableStateOf(true) }
    var notice by remember(pageUrl) { mutableStateOf("网页加载后，可点击“检查音轨”；部分网站需要先在网页播放器里点播放。") }
    var browser by remember(pageUrl) { mutableStateOf<WebView?>(null) }

    fun publish(candidate: WebMediaCandidate) {
        discovered[candidate.url] = candidate
        val ordered = discovered.values.sortedWith(
            compareBy<WebMediaCandidate> { candidatePriority(it.kind) }
                .thenBy { it.url.length }
                .thenBy { it.url }
        )
        candidates = ordered
    }

    fun headersForMedia(url: String, captured: Map<String, String>, referer: String): Map<String, String> {
        val values = captured.toMutableMap()
        if (values.keys.none { it.equals("Referer", true) } && referer.startsWith("http", true)) {
            values["Referer"] = referer
        }
        if (values.keys.none { it.equals("User-Agent", true) }) {
            values["User-Agent"] = runCatching { WebSettings.getDefaultUserAgent(context) }
                .getOrDefault(ANDROID_WEBVIEW_UA)
        }
        // CookieManager scopes cookies to the requested media URL's domain/path. Do not copy the
        // whole webpage's Cookie header to an unrelated CDN host.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { values["Cookie"] = it }
        return WebMediaCandidatePolicy.safeHeaders(values)
    }

    fun addFromUrl(url: String, mime: String?, element: String?, fromPage: String, captured: Map<String, String>) {
        val candidate = WebMediaCandidatePolicy.classify(
            url = url,
            mimeType = mime,
            mediaElement = element,
            pageUrl = fromPage,
        ) ?: return
        publish(candidate.copy(headers = headersForMedia(candidate.url, captured, fromPage)))
    }

    LaunchedEffect(pageUrl) {
        val direct = WebMediaCandidatePolicy.classify(pageUrl)
        if (direct != null) {
            publish(direct.copy(headers = headersForMedia(direct.url, emptyMap(), pageUrl)))
        }
    }

    val latestBrowser = rememberUpdatedState(browser)
    // Do not key this effect to browser: AndroidView.factory sets that state. Keying by it would
    // dispose the newly-created WebView immediately on its first state update.
    DisposableEffect(pageUrl) {
        onDispose {
            latestBrowser.value?.let { view ->
                runCatching { view.evaluateJavascript(PAUSE_MEDIA_JS, null) }
                runCatching { view.stopLoading() }
                runCatching { view.onPause() }
                runCatching { view.removeAllViews() }
                runCatching { view.destroy() }
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFFF2F6FC))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = OnBg)
            }
            Column(Modifier.weight(1f)) {
                Text("网页视频", color = OnBg, style = MaterialTheme.typography.titleLarge)
                Text(
                    currentPage,
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    discovered.clear()
                    candidates = emptyList()
                    notice = "正在重新加载网页并检测媒体请求…"
                    browser?.reload()
                },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新网页", tint = BrandBright)
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (!url.isNullOrBlank()) {
                                currentPage = url
                                loading = true
                                if (url != pageUrl) {
                                    discovered.clear()
                                    candidates = emptyList()
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            loading = false
                            if (!url.isNullOrBlank()) currentPage = url
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val req = request ?: return null
                            val url = req.url?.toString() ?: return null
                            val reqHeaders = req.requestHeaders.orEmpty()
                            val page = currentPage
                            // WebResourceRequest exposes request headers, not response Content-Type.
                            // Do not mislabel an Accept header as the media's actual MIME type.
                            val candidate = WebMediaCandidatePolicy.classify(
                                url = url,
                                pageUrl = page,
                            ) ?: return null
                            val safe = headersForMedia(candidate.url, reqHeaders, page)
                            mainHandler.post { publish(candidate.copy(headers = safe)) }
                            return null
                        }
                    }
                    loadUrl(pageUrl)
                }
            },
            update = { view ->
                if (view.url.isNullOrBlank()) view.loadUrl(pageUrl)
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    loading -> "网页加载中…"
                    candidates.isEmpty() -> "暂未发现可直接读取的媒体流"
                    else -> "检测到 ${candidates.size} 个媒体源"
                },
                color = OnBg,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val view = browser
                if (view == null) {
                    notice = "网页播放器还未准备好。"
                } else {
                    notice = "正在检查网页 video/audio 元素与当前媒体地址…"
                    view.evaluateJavascript(EXTRACT_MEDIA_JS) { result ->
                        val jsonString = runCatching { JSONTokener(result ?: "null").nextValue() as? String }
                            .getOrNull()
                        if (jsonString.isNullOrBlank() || jsonString == "null") {
                            notice = "未读取到网页播放器媒体地址。请先在网页中点击播放，再检查一次。"
                        } else {
                            val array = runCatching { JSONArray(jsonString) }.getOrNull()
                            if (array == null || array.length() == 0) {
                                notice = "未找到可访问的 video/audio 地址。若播放器使用 blob/MSE 或 DRM，网页音轨可能不可导出。"
                            } else {
                                var sawBlob = false
                                var sawEncrypted = false
                                for (i in 0 until array.length()) {
                                    val item = array.optJSONObject(i) ?: continue
                                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                                    val element = item.optString("tag").takeIf { it.isNotBlank() }
                                    val encrypted = item.optBoolean("encrypted", false)
                                    if (url.startsWith("blob:", true)) {
                                        sawBlob = true
                                        continue
                                    }
                                    if (encrypted) {
                                        sawEncrypted = true
                                        continue
                                    }
                                    addFromUrl(url, item.optString("mime").takeIf { it.isNotBlank() }, element, currentPage, emptyMap())
                                }
                                notice = when {
                                    sawEncrypted -> "网页报告该媒体可能使用 DRM/加密保护；本应用不会尝试绕过保护。"
                                    candidates.isNotEmpty() && sawBlob ->
                                        "播放器使用了 blob/MSE，但同时发现公开的媒体清单或音轨地址；请优先选择 HLS/DASH 清单，而不是 blob 地址。"
                                    candidates.isNotEmpty() ->
                                        "已更新媒体列表。选择一个可访问的媒体源后，将由原生播放器读取音轨。"
                                    sawBlob ->
                                        "检测到 blob 媒体地址。正在使用网页公开的网络资源清单寻找可访问的音轨；如果没有公开清单，可能无法提取。"
                                    else -> "网页没有暴露可直接读取的媒体地址；可尝试先点网页播放器播放后再检查。"
                                }
                            }
                        }
                    }
                }
            }) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text("检查音轨")
            }
        }

        if (notice.isNotBlank()) {
            Text(
                notice,
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (candidates.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(candidates, key = { it.url }) { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.kind.displayName, color = OnBg, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    candidate.url,
                                    color = SubText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = {
                                    browser?.let { view ->
                                        runCatching { view.evaluateJavascript(PAUSE_MEDIA_JS, null) }
                                        view.onPause()
                                    }
                                    onUseMedia(candidate.url, candidate.headers, candidate.mimeType)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(3.dp))
                                Text("使用")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun candidatePriority(kind: WebMediaKind): Int = when (kind) {
    WebMediaKind.VIDEO_FILE -> 0
    WebMediaKind.HLS -> 1
    WebMediaKind.DASH -> 2
    WebMediaKind.MEDIA_ELEMENT -> 3
    WebMediaKind.AUDIO_FILE -> 4
}

private const val ANDROID_WEBVIEW_UA =
    "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"

private const val PAUSE_MEDIA_JS =
    "Array.from(document.querySelectorAll('video,audio')).forEach(function(e){try{e.pause();e.muted=true;}catch(x){}});"

private const val EXTRACT_MEDIA_JS = """(function(){var media=Array.from(document.querySelectorAll('video,audio')).reduce(function(all,e){all.push({url:e.currentSrc||e.src||'',tag:e.tagName.toLowerCase(),mime:e.type||'',encrypted:!!e.mediaKeys});Array.from(e.querySelectorAll('source')).forEach(function(s){all.push({url:s.src||'',tag:e.tagName.toLowerCase(),mime:s.type||'',encrypted:!!e.mediaKeys});});return all;},[]);var resources=[];try{resources=performance.getEntriesByType('resource').map(function(e){return e.name;}).filter(function(url){return /\.(m3u8|mpd)(?:[?#]|$)/i.test(url);}).map(function(url){return {url:url,tag:'resource',mime:'',encrypted:false};});}catch(e){}var seen={};return JSON.stringify(media.concat(resources).filter(function(x){if(!x.url||seen[x.url])return false;seen[x.url]=true;return true;}));})()"""
