package io.translatetube.local.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B 站链接原生解析：不经过 WebView 页面渲染，直接拿可播直链。
 *
 * 背景：m.bilibili.com 视频页在应用内 WebView 里只渲染出推荐流封面，播放器本体
 * 不出现，用户没有任何播放入口；页面推荐位小视频还会被嗅探成「已验证候选源」，
 * 反过来挡住 yt-dlp 自动兜底（有候选就不启动），形成死锁。
 *
 * 路线（按顺序尝试）：
 * 1. api.bilibili.com 的 view + playurl（HTML5 平台，免登录 mp4 durl）——
 *    手机网络（家宽/移动 IP）下可用性高；登录 cookie 可提升清晰度。
 * 2. www.bilibili.com 页面 HTML 里的 __INITIAL_STATE__（数据中心 IP 对 API 有
 *    412 风控时页面不受影响）——用于拿元信息，playurl 仍走 API。
 * 3. 解析失败（风控/多段 durl/无权限）返回 null，上层回退 yt-dlp 或 WebView。
 */
internal object BilibiliNativeResolver {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    internal data class PlayResult(
        val playUrl: String,
        val backupUrls: List<String>,
        val title: String,
        val coverUrl: String?,
        val durationMs: Long,
        val referer: String,
        val userAgent: String,
    )

    private data class VideoInfo(
        val bvid: String?,
        val aid: Long?,
        val cid: Long,
        val title: String,
        val coverUrl: String?,
        val durationMs: Long,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** B 站域（含短链与客户端深链）。 */
    fun isBilibiliLink(url: String): Boolean {
        val u = url.trim()
        if (u.startsWith("bilibili://", ignoreCase = true)) return true
        val host = runCatching { java.net.URI(u).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
            ?: return false
        return BilibiliVideoRefPolicy.isShortLinkHost(host) ||
            host.endsWith(".bilibili.com") || host == "bilibili.com"
    }

    /** b23.tv / bili2233.cn 短链展开：跟随最多 5 次 30x，返回最终 URL（非短链原样返回）。 */
    private fun expandShortLink(url: String): String? {
        val host = runCatching { java.net.URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
            ?: return url
        if (!BilibiliVideoRefPolicy.isShortLinkHost(host)) return url
        var current = url
        repeat(5) {
            // HEAD 优先（省流量）；个别短链服务不支持 HEAD 时 GET 兜底（body 不读）。
            val location = redirectLocation(current, head = true)
                ?: redirectLocation(current, head = false)
                ?: return current
            val absolute = runCatching { java.net.URI(current).resolve(location).toString() }.getOrNull()
                ?: return current
            // 深链转 web（B 站客户端 scheme）
            val converted = BilibiliDeepLinkPolicy.toWebUrl(absolute) ?: absolute
            current = converted
            val nextHost = runCatching { java.net.URI(current).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
            if (!BilibiliVideoRefPolicy.isShortLinkHost(nextHost)) return current
        }
        return current
    }

    private fun redirectLocation(url: String, head: Boolean): String? = runCatching {
        val request = if (head) Request.Builder().url(url).head().build() else Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (response.isRedirect) response.header("Location") else null
        }
    }.getOrNull()

    /** 从 URL 提取 BV/av 号与分P序号（含短链展开与深链转换）。 */
    private fun extractVideoRef(url: String): BilibiliVideoRefPolicy.Ref? {
        val expanded = expandShortLink(url) ?: return null
        val webUrl = if (expanded.startsWith("bilibili://", ignoreCase = true)) {
            BilibiliDeepLinkPolicy.toWebUrl(expanded) ?: return null
        } else {
            expanded
        }
        return BilibiliVideoRefPolicy.parse(webUrl)
    }

    /** API/页面里的 pages 数组 → 分P列表；缺字段的条目直接丢弃。 */
    private fun parsePages(array: org.json.JSONArray?): List<BilibiliVideoRefPolicy.PageInfo> {
        if (array == null) return emptyList()
        val result = ArrayList<BilibiliVideoRefPolicy.PageInfo>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val cid = item.optLong("cid", -1L)
            if (cid <= 0) continue
            result.add(
                BilibiliVideoRefPolicy.PageInfo(
                    cid = cid,
                    page = item.optInt("page", i + 1),
                    part = item.optString("part"),
                    durationSec = item.optLong("duration", 0L),
                ),
            )
        }
        return result
    }

    /** 按链接里的 ?p= 选分P；没有 pages 数组时退回视频级 cid（单P视频）。 */
    private fun buildVideoInfo(
        data: JSONObject,
        bvid: String?,
        aid: Long?,
        requestedPage: Int?,
    ): VideoInfo? {
        val pages = parsePages(data.optJSONArray("pages"))
        val picked = BilibiliVideoRefPolicy.pickPage(pages, requestedPage)
        val cid = picked?.cid ?: data.optLong("cid", -1L)
        if (cid <= 0) return null
        val baseTitle = data.optString("title").takeIf { it.isNotBlank() } ?: "B站视频"
        val durationSec = picked?.durationSec?.takeIf { it > 0 } ?: data.optLong("duration")
        return VideoInfo(
            bvid = data.optString("bvid").takeIf { it.isNotBlank() } ?: bvid,
            aid = data.optLong("aid", aid ?: -1L).takeIf { it > 0 },
            cid = cid,
            title = if (picked != null) {
                BilibiliVideoRefPolicy.displayTitle(baseTitle, pages, picked)
            } else {
                baseTitle
            },
            coverUrl = data.optString("pic").takeIf { it.isNotBlank() },
            durationMs = durationSec * 1000L,
        )
    }

    /** view API：标题/cid/时长/封面（手机网络直连）。 */
    private fun fetchView(bvid: String?, aid: Long?, requestedPage: Int?, cookie: String?): VideoInfo? {
        val query = when {
            bvid != null -> "bvid=$bvid"
            aid != null -> "aid=$aid"
            else -> return null
        }
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/view?$query")
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/")
            .apply { cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                if (json.optInt("code") != 0) return@runCatching null
                val data = json.optJSONObject("data") ?: return@runCatching null
                buildVideoInfo(data, bvid, aid, requestedPage)
            }
        }.getOrNull()
    }

    /**
     * 页面 HTML 兜底：从 www.bilibili.com 视频页的 __INITIAL_STATE__ 提取元信息。
     * API 被风控（412）时页面本身仍可访问（已在转写管线中验证）。
     */
    private fun fetchViewFromHtml(bvid: String, requestedPage: Int?): VideoInfo? {
        val request = Request.Builder()
            .url("https://www.bilibili.com/video/$bvid/")
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val html = response.body?.string() ?: return@runCatching null
                val state = extractInitialState(html) ?: return@runCatching null
                val videoData = state.optJSONObject("videoData") ?: return@runCatching null
                buildVideoInfo(videoData, bvid, null, requestedPage)
            }
        }.getOrNull()
    }

    /** 截取 `window.__INITIAL_STATE__={...};` 的 JSON 主体（大括号配平扫描）。 */
    private fun extractInitialState(html: String): JSONObject? {
        val marker = "window.__INITIAL_STATE__="
        val start = html.indexOf(marker)
        if (start < 0) return null
        var index = start + marker.length
        if (index >= html.length || html[index] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        while (index < html.length) {
            val c = html[index]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(html.substring(start + marker.length, index + 1)) }.getOrNull()
                    }
                }
            }
            index++
        }
        return null
    }

    /**
     * playurl（HTML5 平台，免登录 mp4）。登录 cookie 存在时提升清晰度。
     * 多段 durl（超长视频分片）单 URL 播不了，返回 null 让上层回退 yt-dlp。
     */
    private fun fetchPlayUrl(info: VideoInfo, cookie: String?): Pair<String, List<String>>? {
        val query = buildString {
            when {
                info.bvid != null -> append("bvid=").append(info.bvid)
                info.aid != null -> append("aid=").append(info.aid)
                else -> return null
            }
            append("&cid=").append(info.cid)
            append("&qn=64&platform=html5&high_quality=1&fnval=1")
        }
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/player/playurl?$query")
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/")
            .apply { cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                if (json.optInt("code") != 0) return@runCatching null
                val data = json.optJSONObject("data") ?: return@runCatching null
                val durl = data.optJSONArray("durl") ?: return@runCatching null
                if (durl.length() == 0) return@runCatching null
                val first = durl.optJSONObject(0) ?: return@runCatching null
                val url = first.optString("url").takeIf { it.startsWith("http") } ?: return@runCatching null
                val backups = mutableListOf<String>()
                val backupArray = first.optJSONArray("backupUrl")
                if (backupArray != null) {
                    for (i in 0 until backupArray.length()) {
                        val candidate = backupArray.optString(i)
                        if (candidate.startsWith("http")) backups.add(candidate)
                    }
                }
                // 多段 mp4：ExoPlayer 单 URL 无法顺序拼接，交回上层走 yt-dlp
                if (durl.length() > 1) return@runCatching null
                url to backups
            }
        }.getOrNull()
    }

    /**
     * 解析 B 站链接为可直接进播放器的结果；失败返回 null（上层回退）。
     * 成功结果的播放请求需携带 Referer + 桌面 UA（B 站 CDN 校验）。
     */
    suspend fun resolve(rawUrl: String, cookie: String?): PlayResult? = withContext(Dispatchers.IO) {
        val ref = extractVideoRef(rawUrl) ?: return@withContext null
        val info = fetchView(ref.bvid, ref.aid, ref.page, cookie)
            ?: (ref.bvid?.let { fetchViewFromHtml(it, ref.page) })
            ?: return@withContext null
        val (playUrl, backups) = fetchPlayUrl(info, cookie) ?: return@withContext null
        PlayResult(
            playUrl = playUrl,
            backupUrls = backups,
            title = info.title,
            coverUrl = info.coverUrl,
            durationMs = info.durationMs,
            referer = "https://www.bilibili.com/",
            userAgent = DESKTOP_UA,
        )
    }
}
