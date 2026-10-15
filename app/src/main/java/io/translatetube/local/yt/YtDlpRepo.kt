package io.translatetube.local.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * yt-dlp 通用站点提取封装：B站 / TikTok / Twitter 等上千个网站的视频地址解析。
 *
 * 设计要点：
 * - 主入口 YouTube 优先走 NewPipeExtractor（快、稳）；本类兜所有网页源，
 *   YouTube 风控期也可从「网页视频」入口经本类自救；
 * - 只做「解析出可播放的媒体直链」，后续音频提取/转写/配音完全复用现有管线；
 * - 只挑「音视频合一」的可播格式（combined mp4 / HLS m3u8），
 *   分离流（video-only）在播放器侧无法直接合流，故不采用；
 * - 解析出的 URL 带站点签名会过期，调用方必须立即播放，不做长期缓存；
 * - 首次调用会解包 yt-dlp 运行时（约 2~5 秒，仅一次）；
 * - 解析请求带 --no-playlist（合集页取单视频）、--socket-timeout（防挂死），
 *   并透传 WebView Cookie（登录态）提高需登录站点成功率。
 */
object YtDlpRepo {

    private const val TAG = "YtDlpRepo"

    @Volatile private var inited = false

    // ---- 版本自动检测（每日最多一次；仅在用过 yt-dlp 后启用） ----

    data class UpdateState(
        /** 当前已安装的解析规则版本；null = 尚未在本机使用过 yt-dlp */
        val current: String? = null,
        /** GitHub 上游 STABLE 最新版；null = 未检查或检查失败 */
        val latest: String? = null,
        val hasUpdate: Boolean = false,
        /** 上次成功检查的时间戳（ms） */
        val lastCheckMs: Long = 0L,
        val checking: Boolean = false,
    )

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState

    private val checkHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 yt-dlp 是否有新版本（GitHub STABLE 渠道，仅几 KB 流量）。
     * - 从未用过 yt-dlp 的设备跳过（不打扰）；
     * - 上午/下午/晚上三个时段各检测一次（同一时段内不重复；0~4 点深夜静默）；
     *   [force]=true 时立即检查（设置页手动按钮，不受时段限制）；
     * - 网络失败静默（保持旧状态），下次启动再试。
     */
    suspend fun checkForUpdate(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        val prefs = appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        if (!force && !prefs.getBoolean("used", false)) return@withContext
        val last = prefs.getLong("last_check_ms", 0L)
        val slot = updateSlotKey(System.currentTimeMillis())
        if (!force) {
            // 深夜（0~4 点）不检查；同一时段已检查过则只回填持久化结论
            if (slot == null) return@withContext
            if (slot == prefs.getString("last_check_slot", null)) {
                _updateState.value = _updateState.value.copy(
                    current = prefs.getString("current_version", null),
                    latest = prefs.getString("latest_version", null),
                    hasUpdate = prefs.getBoolean("has_update", false),
                    lastCheckMs = last,
                )
                return@withContext
            }
        }
        if (!force) {
            // 自动路径先尝试读本地版本（不用先解包运行时：仅当本进程已 init）
            if (!inited) {
                val persistedCurrent = prefs.getString("current_version", null)
                val latestKnown = fetchLatestReleaseTag() ?: return@withContext
                val hasUpdate = persistedCurrent != null && persistedCurrent != latestKnown
                prefs.edit()
                    .putLong("last_check_ms", System.currentTimeMillis())
                    .putString("last_check_slot", slot)
                    .putString("latest_version", latestKnown)
                    .putBoolean("has_update", hasUpdate)
                    .apply()
                _updateState.value = UpdateState(
                    current = persistedCurrent,
                    latest = latestKnown,
                    hasUpdate = hasUpdate,
                    lastCheckMs = System.currentTimeMillis(),
                )
                return@withContext
            }
        }
        // 强制路径 / 本进程已 init：以运行时真实版本为准
        _updateState.value = _updateState.value.copy(checking = true)
        try {
            val currentVersion = runCatching { YoutubeDL.version(appCtx) }.getOrNull()
            val latestKnown = fetchLatestReleaseTag() ?: run {
                _updateState.value = _updateState.value.copy(checking = false)
                return@withContext
            }
            val hasUpdate = currentVersion != null && currentVersion != latestKnown
            prefs.edit()
                .putLong("last_check_ms", System.currentTimeMillis())
                .putString("last_check_slot", slot)
                .putString("current_version", currentVersion)
                .putString("latest_version", latestKnown)
                .putBoolean("has_update", hasUpdate)
                .apply()
            _updateState.value = UpdateState(
                current = currentVersion,
                latest = latestKnown,
                hasUpdate = hasUpdate,
                lastCheckMs = System.currentTimeMillis(),
                checking = false,
            )
        } finally {
            _updateState.value = _updateState.value.copy(checking = false)
        }
    }

    /** 检测时段：上午 5-11 / 下午 12-17 / 晚上 18-23；0-4 点深夜返回 null（不检查）。 */
    private fun updateSlotKey(nowMs: Long): String? {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        val slot = when (cal.get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "AM"
            in 12..17 -> "PM"
            in 18..23 -> "NIGHT"
            else -> return null
        }
        val date = "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        return "$date-$slot"
    }

    private fun fetchLatestReleaseTag(): String? = runCatching {
        checkHttp.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** 懒初始化（线程安全）。 */
    suspend fun ensureReady(ctx: Context): Unit = withContext(Dispatchers.IO) {
        if (inited) return@withContext
        synchronized(this) {
            if (inited) return@synchronized
            YoutubeDL.init(ctx.applicationContext)
            inited = true
            // 记录「已使用」+ 当前版本，供启动时的每日自动检查使用
            val appCtx = ctx.applicationContext
            val currentVersion = runCatching { YoutubeDL.version(appCtx) }.getOrNull()
            appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).edit()
                .putBoolean("used", true)
                .putString("current_version", currentVersion)
                .apply()
            _updateState.value = _updateState.value.copy(current = currentVersion)
            Log.i(TAG, "yt-dlp runtime ready, version=$currentVersion")
        }
    }

    fun isReady(): Boolean = inited

    // ---- 会话级来源标记：resolve 成功后记录 mediaUrl → 源页面/Cookie 映射 ----
    // 最近 8 条（LRU）：用户在多个视频间往返切换时，旧会话的转写仍能走 yt-dlp 直下。
    // 进程重建后丢失（claim 返回 null），调用方自动回落现有流式管线；
    // 标记只存内存，Cookie 不持久化，与工程「下载凭据不落盘」约定一致。

    data class ClaimedOrigin(val pageUrl: String, val cookie: String?)

    private val resolvedOrigins = object : LinkedHashMap<String, ClaimedOrigin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClaimedOrigin>): Boolean = size > 8
    }

    /** 若 mediaUrl 来自本进程内最近几次 yt-dlp 解析，返回其源页面地址与 Cookie。 */
    fun claimForMediaUrl(mediaUrl: String): ClaimedOrigin? =
        synchronized(resolvedOrigins) { resolvedOrigins[mediaUrl] }

    /**
     * 用 yt-dlp 直接下载网页视频的纯音频（bestaudio），供 Whisper 转写。
     * 不转码不合并（优先 bestaudio），文件由调用方放入 ASR 音频缓存（.bin）。
     * 所有中间产物都留在独立暂存目录，只有校验完成的媒体文件才会发布到缓存路径。
     *
     * @param onProgress 0..100
     */
    suspend fun downloadAudio(
        ctx: Context,
        pageUrl: String,
        dest: File,
        onProgress: (Int) -> Unit = {},
        cookieHeader: String? = null,
    ): File = withContext(Dispatchers.IO) {
        ensureReady(ctx)
        val parent = dest.parentFile
        if (parent == null || dest.name.endsWith(".part")) {
            throw IllegalArgumentException("下载目标路径不合法")
        }
        // yt-dlp can leave partial files after cancellation or process failure. Keep all output
        // in an isolated staging directory and publish only one validated, complete media file.
        val stagingDir = File(parent, ".${dest.nameWithoutExtension}.ytdlp-${System.nanoTime()}")
        if (!stagingDir.mkdirs()) throw java.io.IOException("无法创建 yt-dlp 音频暂存目录")
        val outputTemplate = File(stagingDir, "audio.%(ext)s").absolutePath
        try {
            val request = YoutubeDLRequest(pageUrl.trim()).apply {
                addOption("-f", "bestaudio/best")
                addOption("--no-playlist")
                addOption("--socket-timeout", "20")
                addOption("--retries", "3")
                addOption("--max-filesize", "160M")
                addOption("-N", "4")
                addOption("--newline")
                addOption("-o", outputTemplate)
                cookieHeader?.takeIf { it.isNotBlank() }?.let {
                    addOption("--add-headers", "Cookie:$it")
                }
            }
            try {
                YoutubeDL.execute(request) { percent, _, _ ->
                    if (percent >= 0f) onProgress(percent.toInt().coerceIn(0, 100))
                }
            } catch (e: java.io.InterruptedIOException) {
                // Preserve cooperative cancellation so no fallback download starts after cancel.
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("yt-dlp 下载被中断", e)
            }
            currentCoroutineContext().ensureActive()
            val outputs = stagingDir.listFiles().orEmpty()
                .filter { it.isFile && YtDlpAudioPolicy.isCandidateOutput(it.name) }
            val downloaded = outputs.singleOrNull()
                ?: throw IllegalStateException("yt-dlp 没有生成唯一的完整音频文件")
            val downloadedBytes = downloaded.length()
            if (!YtDlpAudioPolicy.isValidDownload(downloaded.isFile, downloadedBytes)) {
                val reason = when {
                    downloadedBytes <= 0L -> "yt-dlp 音频下载结果为空"
                    downloadedBytes > YtDlpAudioPolicy.MAX_AUDIO_BYTES ->
                        "yt-dlp 音频超过 160 MB 限制，正在回落至受限的流式下载"
                    else -> "yt-dlp 音频下载结果异常"
                }
                throw IllegalStateException(reason)
            }
            currentCoroutineContext().ensureActive()
            // The staging folder is created below the cache parent, so publication stays on the
            // same filesystem. If rename fails, keep any previous valid cache untouched.
            if (!downloaded.renameTo(dest)) {
                throw IllegalStateException("yt-dlp 音频已下载，但无法原子保存缓存；旧缓存仍保留")
            }
            dest.setLastModified(System.currentTimeMillis())
            dest
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    /** 是否为适合 yt-dlp 处理的 URL（http/https 均可；主入口 YouTube 仍走 NewPipe，此处含 YouTube 作为风控兜底）。 */
    fun isEligible(url: String): Boolean {
        val u = url.trim()
        if (!YtDlpMediaPolicy.isSafeHttpUrl(u)) return false
        val host = runCatching { java.net.URI(u).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
            ?: return false
        return host.isNotEmpty() && !host.endsWith(".local")
    }

    /**
     * 更新 yt-dlp 站点解析规则（STABLE 渠道）。
     * 网站改版后旧规则失效导致解析失败时，更新规则是第一自救手段；
     * 下载量约 30MB，由用户显式触发。返回用户可读的结果文案。
     */
    suspend fun updateRules(ctx: Context): String = withContext(Dispatchers.IO) {
        ensureReady(ctx)
        val appCtx = ctx.applicationContext
        val message = when (YoutubeDL.updateYoutubeDL(appCtx, YoutubeDL.UpdateChannel.STABLE)) {
            YoutubeDL.UpdateStatus.DONE -> "yt-dlp 解析规则已更新到最新版，可重试解析"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp 解析规则已是最新版；失败可能是站点限制或网络问题"
        }
        // 更新后刷新版本检测结论（清掉「有新版本」标记）
        val currentVersion = runCatching { YoutubeDL.version(appCtx) }.getOrNull()
        val latestKnown = _updateState.value.latest
        appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).edit()
            .putString("current_version", currentVersion)
            .putBoolean("has_update", false)
            .apply()
        _updateState.value = UpdateState(
            current = currentVersion,
            latest = latestKnown,
            hasUpdate = false,
            lastCheckMs = _updateState.value.lastCheckMs,
        )
        message
    }

    /** 解析结果：可直接交给播放器的媒体地址 + 必要的请求头。 */
    data class ResolvedMedia(
        val title: String,
        val durationSec: Int,
        val mediaUrl: String,
        val referrer: String,
        val cookie: String,
    )

    /**
     * 解析网页为可播放媒体地址。挑格式优先级：
     * 1) combined（音视频合一，按 tbr 最高）
     * 2) HLS m3u8 master（播放器侧 exoplayer-hls 可播）
     * 3) 任何含音频的流（黑屏可听，保底）
     *
     * @param cookieHeader 站点 Cookie（如 WebView 已登录态），可显著提高需登录站点的成功率；
     *                    格式为 "k=v; k2=v2"。
     */
    suspend fun resolve(ctx: Context, pageUrl: String, cookieHeader: String? = null): ResolvedMedia {
        if (!isEligible(pageUrl)) throw IllegalArgumentException("网页地址无效或不受支持")
        ensureReady(ctx)
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(pageUrl.trim()).apply {
                // 合集/播放列表页只取单视频，避免多行 JSON 撞坏单视频解析
                addOption("--no-playlist")
                // 网络层超时：防慢站点/死链把解析进程挂死
                addOption("--socket-timeout", "20")
                addOption("--retries", "3")
                if (!cookieHeader.isNullOrBlank()) {
                    addOption("--add-headers", "Cookie:$cookieHeader")
                }
            }
            val info = YoutubeDL.getInfo(request)
            val formats = info.formats.orEmpty()
            val playableFormats = formats.mapNotNull { format ->
                val url = format.url?.takeIf { YtDlpMediaPolicy.isSafeHttpUrl(it) } ?: return@mapNotNull null
                YtDlpFormatCandidate(
                    url = url,
                    extension = format.ext,
                    hasVideo = !format.vcodec.isNullOrBlank() && format.vcodec != "none",
                    hasAudio = !format.acodec.isNullOrBlank() && format.acodec != "none",
                    bitrate = format.tbr.toDouble(),
                )
            }
            val url = YtDlpMediaPolicy.selectPlayableUrl(
                formats = playableFormats,
                fallbackUrl = info.url.takeIf { formats.isEmpty() },
            ) ?: throw IllegalStateException(
                "yt-dlp 未返回可播放的视频流；只找到音频流或媒体地址格式无效"
            )
            // 透传给播放器的 Cookie 优先用请求头里的（保持与解析一致的登录态）
            val headers = info.httpHeaders.orEmpty()
            val effectiveCookie = headers["Cookie"].orEmpty().ifBlank { cookieHeader.orEmpty() }
            // 记录会话级来源标记：转写音频将优先经 yt-dlp 直下纯音频（downloadAudio）
            synchronized(resolvedOrigins) {
                resolvedOrigins[url] = ClaimedOrigin(pageUrl.trim(), effectiveCookie.ifBlank { null })
            }
            ResolvedMedia(
                title = info.title ?: info.fulltitle ?: "yt-dlp 视频",
                durationSec = info.duration,
                mediaUrl = url,
                referrer = headers["Referer"].orEmpty(),
                cookie = effectiveCookie,
            )
        }
    }

    /** 用户可读的解析失败原因。 */
    fun failureHint(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Unsupported URL", ignoreCase = true) ->
                "该网站暂不在 yt-dlp 支持列表内"
            msg.contains("Sign in", ignoreCase = true) || msg.contains("login", ignoreCase = true) ->
                "该视频需要登录/会员，暂不支持"
            msg.contains("unavailable", ignoreCase = true) || msg.contains("removed", ignoreCase = true) ->
                "视频不可用（可能已删除或地区限制）"
            msg.contains("timed out", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                "网络超时，请检查网络后重试"
            else -> "yt-dlp 解析失败：${e.message ?: "未知错误"}"
        }
    }
}
