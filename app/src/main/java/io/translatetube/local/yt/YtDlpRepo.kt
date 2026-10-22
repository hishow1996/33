package io.translatetube.local.yt

import io.translatetube.local.util.AudioDownloadProgressFormatter
import io.translatetube.local.util.HttpDownloadResponsePolicy

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import java.io.FileOutputStream
import java.util.Locale
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
    private const val MAX_RELEASE_METADATA_BYTES = 1024L * 1024L
    private const val MAX_PYPI_METADATA_BYTES = 8L * 1024L * 1024L
    private const val MAX_RESOLVE_JSON_CHARS = 4 * 1024 * 1024
    private const val MAX_RESOLVE_FORMATS = 500
    private const val VERSION_COMMAND_TIMEOUT_SECONDS = 25L

    /** 版本查询：单个来源遇到瞬时错误（超时、断连、5xx）时最多尝试的次数。 */
    private const val VERSION_LOOKUP_ATTEMPTS = 2
    private const val VERSION_LOOKUP_RETRY_DELAY_MS = 700L

    /** 更新包下载：遇到瞬时错误（超时、断连、5xx、下载不完整）时最多尝试的次数。 */
    private const val UPDATE_DOWNLOAD_ATTEMPTS = 3
    private const val UPDATE_DOWNLOAD_RETRY_DELAY_MS = 1500L

    /** 重试也不会改变结果的下载失败（HTTP 4xx、大小或版本校验不通过），不再重试。 */
    private class NonRetryableDownloadException(message: String) : java.io.IOException(message)

    /**
     * 与 App 内 WebView 一致的移动端 Chrome UA。yt-dlp 默认 UA（python-* 类）
     * 会被 B 站等风控站点以 HTTP 412 直接拒绝，是「网页视频解析失败」的最常见原因；
     * WebView 能正常打开的页面，yt-dlp 用同一 UA 请求通常也能通过。
     */
    const val BROWSER_UA: String =
        "Mozilla/5.0 (Linux; Android 9; COR-AL10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
        /** 最近一次检测失败原因，供设置页显示。 */
        val error: String? = null,
        /** 更新流程阶段（下载、校验、安装），仅显示真实状态。 */
        val updateStage: String? = null,
        /** 只有知道真实总字节数时才提供百分比。 */
        val updateProgress: Int? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val updateRunning: Boolean = false,
    )

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState

    private val checkHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 更新包较大，使用独立长超时客户端，避免版本查询的短超时中断下载。
    private const val MAX_UPDATE_DOWNLOAD_BYTES = 100L * 1024L * 1024L
    private const val MIN_UPDATE_DOWNLOAD_BYTES = 512L * 1024L
    private val updateHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
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
            // 深夜（0~4 点）不自动检查；同一时段只回填上次成功结果。
            if (slot == null) return@withContext
            if (slot == prefs.getString("last_check_slot", null)) {
                _updateState.value = _updateState.value.copy(
                    current = prefs.getString("current_version", null),
                    latest = prefs.getString("latest_version", null),
                    hasUpdate = prefs.getBoolean("has_update", false),
                    lastCheckMs = last,
                    checking = false,
                    error = null,
                )
                return@withContext
            }
        }

        _updateState.value = _updateState.value.copy(checking = true, error = null)
        try {
            // Local runtime initialization and upstream stable-version lookup are independent.
            // Keep the runtime failure available to the settings page instead of hiding it.
            var runtimeInitError: String? = null
            if (force) {
                try {
                    ensureReady(appCtx)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (initError: Exception) {
                    Log.w(TAG, "yt-dlp runtime initialization failed during a manual version check", initError)
                    runtimeInitError = "运行时初始化失败：" + describeError(initError)
                }
            }

            val local = detectLocalVersion(appCtx, allowProcess = true)
            local.version?.let { prefs.edit().putString("current_version", it).apply() }
            val currentVersion = local.version
                ?: runCatching { YoutubeDL.version(appCtx) }.getOrNull()
                    ?.trim()
                    ?.takeIf { YtDlpVersionPolicy.isInstalledBuild(it) }
                ?: prefs.getString("current_version", null)
                    ?.trim()
                    ?.takeIf { YtDlpVersionPolicy.isInstalledBuild(it) }
            val localVersionError: String? = if (currentVersion == null) {
                "无法读取本机 yt-dlp 版本：" +
                    listOfNotNull(runtimeInitError, local.failure).joinToString("；")
            } else {
                null
            }

            val latestKnown = fetchLatestReleaseTag()
            if (latestKnown == null) {
                val diagnostic = lastProbeDetail
                    ?: "所有版本源均未响应；请确认网络可访问版本源后重试"
                _updateState.value = _updateState.value.copy(
                    current = currentVersion,
                    latest = prefs.getString("latest_version", null),
                    checking = false,
                    error = listOfNotNull(
                        "无法获取 yt-dlp 上游稳定版：$diagnostic",
                        localVersionError,
                    ).joinToString("；"),
                )
                return@withContext
            }

            val hasUpdate = YtDlpVersionPolicy.isUpdateAvailable(currentVersion, latestKnown)
            val checkedAt = System.currentTimeMillis()
            prefs.edit()
                .putLong("last_check_ms", checkedAt)
                .putString("last_check_slot", slot)
                .putString("latest_version", latestKnown)
                .putBoolean("has_update", hasUpdate)
                .apply()
            if (currentVersion != null) {
                prefs.edit().putString("current_version", currentVersion).apply()
            }
            _updateState.value = UpdateState(
                current = currentVersion,
                latest = latestKnown,
                hasUpdate = hasUpdate,
                lastCheckMs = checkedAt,
                checking = false,
                error = localVersionError,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "yt-dlp update check failed", error)
            val detail = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
            _updateState.value = _updateState.value.copy(
                checking = false,
                error = "版本检查异常：$detail",
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
        val date = String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        return "$date-$slot"
    }

    /**
     * 多来源读取上游稳定版（依次尝试，任一成功即返回）：
     * 1) PyPI 国内镜像（阿里云、清华）；
     * 2) GitHub latest 发布页的最终重定向地址（不占 REST API 限额）；
     * 3) GitHub REST API（未登录每小时仅 60 次，共享 IP 常 403，所以排在发布页之后）；
     * 4) PyPI 官方源兜底。
     *
     * 每个来源遇到瞬时错误（超时、断连、5xx）会再试一次，见 [fetchVersionText]。
     * 显式设置 User-Agent：GitHub REST API 对缺少该请求头的客户端可能拒绝请求。
     */
    private fun fetchLatestReleaseTag(): String? {
        lastProbeDetail = null

        fun pypiMirror(url: String, source: String): String? {
            val json = fetchVersionText(
                url = url,
                accept = "application/json",
                maxBytes = MAX_PYPI_METADATA_BYTES,
                source = source,
            ) ?: return null
            val version = YtDlpVersionPolicy.latestStableFromPypiJson(json)
            if (version == null) {
                recordProbe(source, "响应不是有效的 PyPI 稳定版元数据")
                return null
            }
            return version
        }

        // 不下载整个发布页；从 /releases/latest 的最终重定向地址读取稳定版 tag。
        // 该请求不走 REST API，不占未登录每小时 60 次的限额。
        fun githubReleasePage(): String? {
            var attempt = 0
            while (true) {
                attempt++
                var failure: String? = null
                var transient = false
                val tag = try {
                    val request = Request.Builder()
                        .url("https://github.com/yt-dlp/yt-dlp/releases/latest")
                        .header("User-Agent", "TranslateTube-Android")
                        .header("Accept", "text/html")
                        .header("Cache-Control", "no-cache")
                        .build()
                    checkHttp.newCall(request).execute().use { response ->
                        if (response.code != 200) {
                            failure = "HTTP ${response.code}"
                            transient = isTransientHttpCode(response.code)
                            return@use null
                        }
                        val parsed = YtDlpVersionPolicy.stableTagFromReleasePath(response.request.url.encodedPath)
                        if (parsed == null) failure = "重定向地址不包含稳定版版本号"
                        parsed
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "GitHub release-page version lookup failed", error)
                    failure = error.javaClass.simpleName + ": " + (error.message?.take(60) ?: "网络错误")
                    transient = error is java.io.IOException
                    null
                }
                if (tag != null) return tag
                if (!transient || attempt >= VERSION_LOOKUP_ATTEMPTS) {
                    recordProbe("GitHub 发布页", failure ?: "未知错误")
                    return null
                }
                if (!sleepBeforeRetry(VERSION_LOOKUP_RETRY_DELAY_MS)) {
                    recordProbe("GitHub 发布页", failure ?: "未知错误")
                    return null
                }
            }
        }

        // 各地网络可达性不同，依次尝试 PyPI 镜像、GitHub 发布页、GitHub API 和 PyPI 官方源。
        // 必须选择 stable 版本，避免把 PyPI 上的 dev 预发布误报成 STABLE 更新。
        pypiMirror(
            url = "https://mirrors.aliyun.com/pypi/web/pypi/yt-dlp/json",
            source = "Aliyun PyPI 镜像",
        )?.let { return it }

        pypiMirror(
            url = "https://pypi.tuna.tsinghua.edu.cn/pypi/yt-dlp/json",
            source = "Tsinghua PyPI 镜像",
        )?.let { return it }

        githubReleasePage()?.let { return it }

        // REST API 放在发布页之后：未登录限额低，共享 IP 常被 403。
        fetchVersionText(
            url = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest",
            accept = "application/vnd.github+json",
            maxBytes = MAX_RELEASE_METADATA_BYTES,
            source = "GitHub API",
        )?.let { json ->
            val tag = YtDlpVersionPolicy.stableGitHubTag(json)
            if (tag != null) return tag
            recordProbe("GitHub API", "响应中没有可识别的稳定版 tag")
        }

        // 官方 PyPI JSON 是最后兜底；解析 releases 列表中的稳定版，过滤 .dev / rc / a / b。
        pypiMirror(
            url = "https://pypi.org/pypi/yt-dlp/json",
            source = "PyPI 官方源",
        )?.let { return it }

        Log.w(TAG, "Unable to fetch yt-dlp stable version from all sources")
        recordProbe("汇总", "全部版本源失败")
        return null
    }

    /** 最近一次版本探测中各源的失败明细（设置页错误文案直接引用，便于用户反馈定位）。 */
    @Volatile
    private var lastProbeDetail: String? = null

    private fun recordProbe(source: String, detail: String) {
        lastProbeDetail = ((lastProbeDetail ?: "") + "；" + source + "：" + detail)
    }

    /** 5xx 是服务端瞬时故障，值得再试一次；4xx（含 403 限流、404）再试结果也一样。 */
    private fun isTransientHttpCode(code: Int): Boolean = code in 500..599

    /** 重试前的短暂等待；线程被中断时返回 false，调用方应放弃重试。 */
    private fun sleepBeforeRetry(delayMs: Long): Boolean = try {
        Thread.sleep(delayMs)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * 读取一个版本元数据来源。遇到瞬时错误（超时、断连、5xx）最多尝试
     * [VERSION_LOOKUP_ATTEMPTS] 次；403/404、响应过大等再试也不会变的失败直接放弃。
     * 失败明细只在最终放弃时记录一次，避免设置页错误文案里出现重复条目。
     */
    private fun fetchVersionText(
        url: String,
        accept: String,
        maxBytes: Long,
        source: String,
    ): String? {
        var attempt = 0
        while (true) {
            attempt++
            var failure: String? = null
            var transient = false
            val text = try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TranslateTube-Android")
                    .header("Accept", accept)
                    .header("Cache-Control", "no-cache")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                checkHttp.newCall(request).execute().use { response ->
                    if (!HttpDownloadResponsePolicy.isCompleteGetResponse(response.code)) {
                        Log.w(TAG, "$source version lookup returned HTTP ${response.code}")
                        failure = "HTTP " + response.code
                        transient = isTransientHttpCode(response.code)
                        return@use null
                    }
                    val body = response.body ?: run {
                        failure = "响应没有内容"
                        return@use null
                    }
                    val length = body.contentLength()
                    if (length > maxBytes) {
                        Log.w(TAG, "$source version metadata exceeded size limit: $length")
                        failure = "响应超过大小限制"
                        return@use null
                    }
                    val bytes = BoundedTextPayload.readBytes(
                        body.byteStream(),
                        maxBytes = maxBytes,
                        resourceLabel = "yt-dlp $source 版本检查响应",
                    )
                    String(bytes, Charsets.UTF_8)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "$source version lookup failed", error)
                failure = error.javaClass.simpleName + ": " + (error.message?.take(60) ?: "网络错误")
                transient = error is java.io.IOException
                null
            }
            if (text != null) return text
            if (!transient || attempt >= VERSION_LOOKUP_ATTEMPTS || !sleepBeforeRetry(VERSION_LOOKUP_RETRY_DELAY_MS)) {
                failure?.let { recordProbe(source, it) }
                return null
            }
        }
    }

    /** Local-version probing result; failure summarizes the attempts when version is null. */
    private data class LocalVersionResult(val version: String?, val failure: String?)

    /** Compress an exception and its cause chain into one settings-friendly line. */
    private fun describeError(error: Throwable): String =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
            .take(4)
            .map { (it.message?.takeIf { message -> message.isNotBlank() } ?: it.javaClass.simpleName).take(200) }
            .distinct()
            .joinToString(" ← ")

    /**
     * Detect the local script version without depending on one mechanism:
     * 1) read yt_dlp/version.py from the unpacked zipapp without launching Python;
     * 2) fall back to python yt-dlp --version if allowed;
     * 3) if the runtime is not unpacked yet, inspect the bundled raw resource.
     */
    private fun detectLocalVersion(ctx: Context, allowProcess: Boolean): LocalVersionResult {
        val appCtx = ctx.applicationContext
        val failures = mutableListOf<String>()

        fun attempt(label: String, read: () -> String?): String? = try {
            val version = read()?.trim()
            if (version != null && YtDlpVersionPolicy.isInstalledBuild(version)) {
                version
            } else {
                failures += "$label：没有读到可识别的版本号"
                null
            }
        } catch (error: Exception) {
            Log.w(TAG, "$label failed", error)
            failures += "$label：" + describeError(error)
            null
        }

        val installedScript = File(
            File(File(appCtx.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName),
            YoutubeDL.ytdlpBin,
        )
        val version: String? = if (installedScript.isFile) {
            attempt("读取已解包的 yt-dlp 文件") {
                installedScript.inputStream().use { YtDlpVersionPolicy.versionFromZipapp(it) }
            } ?: if (allowProcess) {
                attempt("运行 yt-dlp --version") { readInstalledBinaryVersion(appCtx) }
            } else {
                null
            }
        } else {
            failures += "yt-dlp 运行时尚未解包（${installedScript.absolutePath}）"
            attempt("读取安装包内置的 yt-dlp") { readBundledScriptVersion(appCtx) }
        }
        if (version != null) Log.i(TAG, "Detected local yt-dlp version=$version")
        return LocalVersionResult(version, if (version == null) failures.joinToString("；") else null)
    }

    /** The youtubedl-android library bundles yt-dlp as a raw APK resource. */
    private fun readBundledScriptVersion(ctx: Context): String? {
        val appCtx = ctx.applicationContext
        val resources = appCtx.resources
        val resourceId = resources.getIdentifier("ytdlp", "raw", appCtx.packageName)
        check(resourceId != 0) { "安装包里没有找到内置的 yt-dlp 资源" }
        return resources.openRawResource(resourceId).use { YtDlpVersionPolicy.versionFromZipapp(it) }
    }

    /**
     * Read the actual yt-dlp script installed by youtubedl-android.
     *
     * Do not use YoutubeDL.version() as the primary source: the library stores that value only
     * after its updater has run, so it can be empty on a fresh installation. Run the bundled
     * interpreter/script directly instead of YoutubeDL.execute(), whose wrapper appends downloader
     * options and only exposes stdout. Merging output gives actionable errors if the runtime fails.
     */
    private fun readInstalledBinaryVersion(ctx: Context): String {
        val appCtx = ctx.applicationContext
        val baseDir = File(appCtx.noBackupFilesDir, YoutubeDL.baseName)
        val nativeLibDir = File(appCtx.applicationInfo.nativeLibraryDir)
        val pythonPath = File(nativeLibDir, "libpython.so")
        val ytdlpPath = File(File(baseDir, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
        val packagesDir = File(baseDir, "packages")
        val pythonDir = File(packagesDir, "python")
        val ffmpegDir = File(packagesDir, "ffmpeg")
        val aria2cDir = File(packagesDir, "aria2c")

        val nativeFiles = nativeLibDir.list()?.sorted()?.joinToString(",").orEmpty().ifBlank { "（空）" }
        check(pythonPath.isFile) {
            "找不到内置 Python：${pythonPath.absolutePath}；原生库目录内容：$nativeFiles" +
                "（目录为空说明安装包没有解压原生库，需要 jniLibs.useLegacyPackaging = true）"
        }
        check(ytdlpPath.isFile) {
            "找不到已解包的 yt-dlp 文件：${ytdlpPath.absolutePath}"
        }

        val processBuilder = ProcessBuilder(
            pythonPath.absolutePath,
            ytdlpPath.absolutePath,
            "--ignore-config",
            "--version",
        ).redirectErrorStream(true)

        // Mirror the runtime environment used by youtubedl-android 0.17.4.
        processBuilder.environment().apply {
            put(
                "LD_LIBRARY_PATH",
                listOf(
                    File(pythonDir, "usr/lib"),
                    File(ffmpegDir, "usr/lib"),
                    File(aria2cDir, "usr/lib"),
                ).joinToString(":") { it.absolutePath },
            )
            put("SSL_CERT_FILE", File(pythonDir, "usr/etc/tls/cert.pem").absolutePath)
            put("PATH", System.getenv("PATH").orEmpty() + ":" + nativeLibDir.absolutePath)
            put("PYTHONHOME", File(pythonDir, "usr").absolutePath)
            put("HOME", File(pythonDir, "usr").absolutePath)
            put("TMPDIR", appCtx.cacheDir.absolutePath)
        }

        val process = processBuilder.start()
        val output = StringBuilder()
        val readFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        // Drain output concurrently so an unexpected Python traceback cannot fill the pipe.
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { output.append(it.readText()) }
            } catch (error: Exception) {
                readFailure.set(error)
            }
        }, "yt-dlp-version-reader").apply {
            isDaemon = true
            start()
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(VERSION_COMMAND_TIMEOUT_SECONDS)
        var exitCode: Int? = null
        try {
            while (System.nanoTime() < deadlineNanos) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(25)
                }
            }
        } catch (interrupted: InterruptedException) {
            process.destroy()
            reader.interrupt()
            Thread.currentThread().interrupt()
            throw IllegalStateException("等待本机 yt-dlp 版本命令时被中断", interrupted)
        }

        if (exitCode == null) {
            process.destroy()
            runCatching { reader.join(500) }
            throw IllegalStateException("本机 yt-dlp --version 执行超时（$VERSION_COMMAND_TIMEOUT_SECONDS 秒）")
        }

        reader.join(1_000)
        check(!reader.isAlive) { "读取本机 yt-dlp 版本输出超时" }
        readFailure.get()?.let {
            throw IllegalStateException("读取本机版本输出失败：${it.message}", it)
        }

        val outputText = output.toString().trim()
        check(exitCode == 0) {
            "本机 yt-dlp --version 退出码为 $exitCode；输出：${outputText.take(180).ifBlank { "（无输出）" }}"
        }
        val version = YtDlpVersionPolicy.installedBuildFromOutput(outputText)
            ?: throw IllegalStateException(
                "本机 yt-dlp 已执行，但版本输出无法识别：${outputText.take(180).ifBlank { "（无输出）" }}",
            )
        Log.i(TAG, "Detected installed yt-dlp version=$version")
        return version
    }

    /** 懒初始化（线程安全）。 */
    suspend fun ensureReady(ctx: Context): Unit = withContext(Dispatchers.IO) {
        if (inited) return@withContext
        synchronized(this) {
            if (inited) return@synchronized
            YoutubeDL.init(ctx.applicationContext)
            inited = true
            // Record first use and the actual version of the installed executable.
            // YoutubeDL.version() alone can be null until its own updater has run once.
            val appCtx = ctx.applicationContext
            val currentVersion = detectLocalVersion(appCtx, allowProcess = false).version
                ?: runCatching { YoutubeDL.version(appCtx) }.getOrNull()
                    ?.trim()
                    ?.takeIf { YtDlpVersionPolicy.isInstalledBuild(it) }
            val prefs = appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            val editor = prefs.edit().putBoolean("used", true)
            if (currentVersion != null) editor.putString("current_version", currentVersion)
            editor.apply()
            _updateState.value = _updateState.value.copy(
                current = currentVersion ?: _updateState.value.current,
            )
            Log.i(TAG, "yt-dlp runtime ready, version=${currentVersion ?: "unknown"}")
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
     * Reports both the progress percentage and bytes observed in the active output file.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun downloadAudio(
        ctx: Context,
        pageUrl: String,
        dest: File,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _, _ -> },
        cookieHeader: String? = null,
    ): File = withContext(Dispatchers.IO) {
        if (!isEligible(pageUrl)) throw IllegalArgumentException("网页地址无效或不受支持")
        if (!YtDlpMediaPolicy.isSafeCookieHeader(cookieHeader)) {
            throw IllegalArgumentException("Cookie 请求头不能包含控制字符")
        }
        ensureReady(ctx)
        val ownerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        ownerJob?.ensureActive()
        val processId = "elephant-audio-" + java.util.UUID.randomUUID().toString()
        val cancellationHandle = ownerJob?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause is java.util.concurrent.CancellationException) {
                YoutubeDL.destroyProcessById(processId)
            }
        }
        val parent = dest.parentFile
        if (parent == null || dest.name.endsWith(".part")) {
            cancellationHandle?.dispose()
            throw IllegalArgumentException("下载目标路径不合法")
        }
        val stagingDir = File(
            parent,
            "." + dest.nameWithoutExtension + ".ytdlp-" + System.nanoTime(),
        )
        if (!stagingDir.mkdirs()) {
            cancellationHandle?.dispose()
            throw java.io.IOException("无法创建 yt-dlp 音频暂存目录")
        }
        val outputTemplate = File(stagingDir, "audio.%(ext)s").absolutePath
        try {
            val request = YoutubeDLRequest(pageUrl.trim()).apply {
                // Only select an audio-only format. If none exists, fail so PlaybackService can
                // fall back to track extraction instead of silently downloading a muxed video.
                addOption("-f", "bestaudio")
                addOption("--no-playlist")
                addOption("--socket-timeout", "20")
                addOption("--retries", "3")
                addOption("-N", "4")
                addOption("--newline")
                addOption("-o", outputTemplate)
                // 与 resolve 相同的浏览器 UA，避免直链下载被站点风控拒绝
                addOption("--add-headers", "User-Agent:" + BROWSER_UA)
                cookieHeader?.takeIf { it.isNotBlank() }?.let {
                    addOption("--add-headers", "Cookie:" + it)
                }
            }
            try {
                YoutubeDL.execute(request, processId) { percent, _, outputLine ->
                    val normalizedPercent = if (percent >= 0f) {
                        percent.toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }
                    val downloadedBytes = stagingDir.walkTopDown()
                        .filter { file -> file.isFile && file.name.startsWith("audio.") }
                        .maxOfOrNull { file -> file.length().coerceAtLeast(0L) }
                        ?: 0L
                    val totalBytes = parseDownloadTotalBytes(outputLine)
                    if (normalizedPercent >= 0 || downloadedBytes > 0L || totalBytes != null) {
                        onProgress(normalizedPercent, downloadedBytes, totalBytes)
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("yt-dlp 下载被中断", e)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            }
            currentCoroutineContext().ensureActive()
            val outputs = stagingDir.listFiles().orEmpty()
                .filter { it.isFile && YtDlpAudioPolicy.isCandidateOutput(it.name) }
            val downloaded = outputs.singleOrNull()
                ?: throw IllegalStateException("yt-dlp 没有生成唯一的完整音频文件")
            val downloadedBytes = downloaded.length()
            if (!YtDlpAudioPolicy.isValidDownload(downloaded.isFile, downloadedBytes)) {
                throw IllegalStateException("yt-dlp 音频下载结果为空或无效")
            }
            currentCoroutineContext().ensureActive()
            if (!downloaded.renameTo(dest)) {
                throw IllegalStateException("yt-dlp 音频已下载，但无法原子保存缓存；旧缓存仍保留")
            }
            dest.setLastModified(System.currentTimeMillis())
            dest
        } finally {
            cancellationHandle?.dispose()
            YoutubeDL.destroyProcessById(processId)
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun parseDownloadTotalBytes(outputLine: String?): Long? {
        val match = Regex(
            """(?i)\bof\s+~?\s*([0-9]+(?:\.[0-9]+)?)\s*(KiB|MiB|GiB|TiB|KB|MB|GB|TB|B)\b"""
        ).find(outputLine.orEmpty()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        val multiplier = when (unit) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            "KIB" -> 1024.0
            "MIB" -> 1024.0 * 1024.0
            "GIB" -> 1024.0 * 1024.0 * 1024.0
            "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        val total = amount * multiplier
        return total.toLong().takeIf { total.isFinite() && total > 0.0 && total <= Long.MAX_VALUE.toDouble() }
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
        val appCtx = ctx.applicationContext
        if (_updateState.value.updateRunning) {
            return@withContext "yt-dlp 更新已在进行中，请稍候"
        }

        _updateState.value = _updateState.value.copy(
            updateRunning = true,
            updateStage = "正在获取更新包信息…",
            updateProgress = null,
            downloadedBytes = 0L,
            totalBytes = null,
            error = null,
        )

        var tempFile: File? = null
        var backupFile: File? = null
        var targetFile: File? = null
        var oldBinaryBackedUp = false
        var newBinaryInstalled = false
        var installationSucceeded = false
        var downloadedBytes = 0L
        var totalBytes: Long? = null

        try {
            ensureReady(appCtx)

            // api.github.com 未登录每小时 60 次，共享 IP 常 403，只作后备。
            // 版本号复用检查更新的多源探测：PyPI 镜像 → GitHub 发布页 → GitHub API → PyPI 官方
            val version = fetchLatestReleaseTag()
                ?: throw java.io.IOException(
                    "获取更新包信息失败：" + (lastProbeDetail?.trimStart('；') ?: "所有版本源均未响应"),
                )

            val currentVersion = detectLocalVersion(appCtx, allowProcess = false).version
                ?: runCatching { YoutubeDL.version(appCtx) }.getOrNull()
                    ?.trim()
                    ?.takeIf { YtDlpVersionPolicy.isInstalledBuild(it) }
            if (currentVersion != null && !YtDlpVersionPolicy.isUpdateAvailable(currentVersion, version)) {
                val now = System.currentTimeMillis()
                appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).edit()
                    .putString("current_version", currentVersion)
                    .putString("latest_version", version)
                    .putBoolean("has_update", false)
                    .putLong("last_check_ms", now)
                    .apply()
                _updateState.value = _updateState.value.copy(
                    current = currentVersion,
                    latest = version,
                    hasUpdate = false,
                    lastCheckMs = now,
                    updateRunning = false,
                    updateStage = "已是最新版本",
                    updateProgress = null,
                    downloadedBytes = 0L,
                    totalBytes = null,
                )
                return@withContext "yt-dlp 解析规则已是最新版本；失败可能是站点限制或网络问题"
            }

            val ytdlpDir = File(File(appCtx.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
            if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
                throw java.io.IOException("无法创建 yt-dlp 安装目录")
            }
            targetFile = File(ytdlpDir, YoutubeDL.ytdlpBin)
            tempFile = File.createTempFile(".yt-dlp-update-", ".part", ytdlpDir)

            totalBytes = null
            _updateState.value = _updateState.value.copy(
                updateStage = "正在下载 yt-dlp " + version,
                updateProgress = null,
                downloadedBytes = 0L,
                totalBytes = null,
            )

            val candidates = listOf(
                "https://github.com/yt-dlp/yt-dlp/releases/download/$version/${YoutubeDL.ytdlpBin}",
            )
            val failures = mutableListOf<String>()
            var downloaded = false
            for (candidate in candidates) {
                var lastError: Exception? = null
                for (attempt in 1..UPDATE_DOWNLOAD_ATTEMPTS) {
                    try {
                        downloadedBytes = downloadYtDlpZipapp(candidate, tempFile!!, version) { done, total ->
                            totalBytes = total
                            _updateState.value = _updateState.value.copy(
                                updateStage = "正在下载 yt-dlp " + version,
                                updateProgress = total?.let { ((done * 100L) / it).toInt().coerceIn(0, 99) },
                                downloadedBytes = done,
                                totalBytes = total,
                            )
                        }
                        downloaded = true
                        break
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: NonRetryableDownloadException) {
                        // HTTP 4xx、包过大/过小、版本不符：重试结果不会变。
                        Log.w(TAG, "yt-dlp 更新包下载失败（不重试）：$candidate", e)
                        lastError = e
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "yt-dlp 更新包下载失败（第 $attempt/$UPDATE_DOWNLOAD_ATTEMPTS 次）：$candidate", e)
                        lastError = e
                        if (attempt < UPDATE_DOWNLOAD_ATTEMPTS) {
                            _updateState.value = _updateState.value.copy(
                                updateStage = "下载失败，正在重试（第 ${attempt + 1}/$UPDATE_DOWNLOAD_ATTEMPTS 次）…",
                                updateProgress = null,
                                downloadedBytes = 0L,
                                totalBytes = null,
                            )
                            kotlinx.coroutines.delay(UPDATE_DOWNLOAD_RETRY_DELAY_MS * attempt)
                        }
                    }
                }
                if (downloaded) break
                failures += (java.net.URI(candidate).host ?: candidate) + "：" +
                    (lastError?.let { describeError(it) } ?: "未知错误")
            }
            if (!downloaded) throw java.io.IOException("获取更新包失败：" + failures.joinToString("；"))

            _updateState.value = _updateState.value.copy(
                updateStage = "下载完成，正在校验文件…",
                updateProgress = 100,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes ?: downloadedBytes,
            )

            val downloadedVersion = tempFile!!.inputStream().use {
                YtDlpVersionPolicy.versionFromZipapp(it)
            }
            if (downloadedVersion != version) {
                throw java.io.IOException(
                    "更新包版本校验失败：预期 " + version + "，文件识别为 " +
                        (downloadedVersion ?: "未知版本"),
                )
            }

            _updateState.value = _updateState.value.copy(updateStage = "校验通过，正在安装…")
            backupFile = File(ytdlpDir, ".yt-dlp-backup-" + System.nanoTime())
            if (targetFile!!.exists()) {
                if (!targetFile!!.renameTo(backupFile!!)) {
                    throw java.io.IOException("无法安全备份当前 yt-dlp 文件，已取消安装")
                }
                oldBinaryBackedUp = true
            }
            if (!tempFile!!.renameTo(targetFile!!)) {
                throw java.io.IOException("无法将已校验的更新文件原子安装到目标位置")
            }
            newBinaryInstalled = true

            val installedVersion = targetFile!!.inputStream().use {
                YtDlpVersionPolicy.versionFromZipapp(it)
            }
            if (installedVersion != version) {
                throw java.io.IOException("安装后复核失败，正在恢复旧版本")
            }

            // 与 youtubedl-android 的版本缓存保持一致，避免下次重复下载同一版本。
            appCtx.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
                .putString("dlpVersion", version)
                .putString("dlpVersionName", version)
                .apply()

            val now = System.currentTimeMillis()
            appCtx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).edit()
                .putString("current_version", version)
                .putString("latest_version", version)
                .putBoolean("has_update", false)
                .putLong("last_check_ms", now)
                .apply()

            installationSucceeded = true
            oldBinaryBackedUp = false
            backupFile?.delete()

            _updateState.value = _updateState.value.copy(
                current = version,
                latest = version,
                hasUpdate = false,
                lastCheckMs = now,
                checking = false,
                error = null,
                updateRunning = false,
                updateStage = "更新完成",
                updateProgress = 100,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes ?: downloadedBytes,
            )
            "yt-dlp 解析规则已更新到 " + version + "，可重试解析"
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "yt-dlp update failed", error)
            val detail = describeError(error)
            _updateState.value = _updateState.value.copy(
                updateRunning = false,
                updateStage = "更新失败",
                updateProgress = null,
                error = detail,
            )
            "yt-dlp 更新失败：" + detail
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
            if (!installationSucceeded) {
                if (newBinaryInstalled) targetFile?.takeIf { it.exists() }?.delete()
                if (oldBinaryBackedUp && backupFile?.exists() == true && targetFile != null) {
                    backupFile!!.renameTo(targetFile!!)
                }
            } else {
                backupFile?.takeIf { it.exists() }?.delete()
            }
            if (_updateState.value.updateRunning) {
                _updateState.value = _updateState.value.copy(
                    updateRunning = false,
                    updateStage = "更新已终止",
                    updateProgress = null,
                )
            }
        }
    }

    /**
     * Download a yt-dlp zipapp with bounded size, complete-length and embedded-version checks.
     * Transient failures are retried by updateRules() using a fresh overwrite of the temp file;
     * [NonRetryableDownloadException] marks failures that a retry cannot change.
     */
    private suspend fun downloadYtDlpZipapp(
        url: String,
        dest: File,
        expectedVersion: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): Long {
        require(url.startsWith("https://")) { "更新包地址必须是 HTTPS" }
        val request = Request.Builder().url(url)
            .header("User-Agent", "TranslateTube-Android")
            .header("Accept", "application/octet-stream")
            .build()
        var received = 0L
        updateHttp.newCall(request).execute().use { response ->
            if (response.code != 200) {
                // 5xx 和 429 是服务端瞬时故障，可重试；其余 4xx（403/404 等）重试结果不会变。
                val message = "HTTP " + response.code
                throw if (response.code == 429 || isTransientHttpCode(response.code)) {
                    java.io.IOException(message)
                } else {
                    NonRetryableDownloadException(message)
                }
            }
            val body = response.body ?: throw java.io.IOException("响应没有文件内容")
            val total = body.contentLength().takeIf { it > 0L }
            if (total != null && total > MAX_UPDATE_DOWNLOAD_BYTES) {
                throw NonRetryableDownloadException("下载包超过安全大小限制")
            }
            onProgress(0L, total)
            body.byteStream().use { source ->
                FileOutputStream(dest).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var lastAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        received += n
                        if (received > MAX_UPDATE_DOWNLOAD_BYTES) {
                            throw NonRetryableDownloadException("下载包超过安全大小限制")
                        }
                        sink.write(buffer, 0, n)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastAt >= 120L) {
                            onProgress(received, total)
                            lastAt = now
                        }
                    }
                    sink.fd.sync()
                }
            }
            if (total != null && received != total) {
                throw java.io.IOException("下载长度不完整：$received / $total 字节")
            }
        }
        if (received < MIN_UPDATE_DOWNLOAD_BYTES) {
            throw NonRetryableDownloadException("下载包过小，疑似错误页面")
        }
        val actual = dest.inputStream().use { YtDlpVersionPolicy.versionFromZipapp(it) }
        if (actual != expectedVersion) {
            throw NonRetryableDownloadException(
                "更新包版本校验失败：预期 $expectedVersion，识别为 ${actual ?: "未知版本"}",
            )
        }
        onProgress(received, received)
        return received
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
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun resolve(ctx: Context, pageUrl: String, cookieHeader: String? = null): ResolvedMedia {
        if (!isEligible(pageUrl)) throw IllegalArgumentException("网页地址无效或不受支持")
        if (!YtDlpMediaPolicy.isSafeCookieHeader(cookieHeader)) {
            throw IllegalArgumentException("Cookie 请求头不能包含控制字符")
        }
        currentCoroutineContext().ensureActive()
        ensureReady(ctx)
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            val ownerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
            ownerJob?.ensureActive()
            val processId = "elephant-resolve-" + java.util.UUID.randomUUID().toString()
            val cancellationHandle = ownerJob?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is java.util.concurrent.CancellationException) {
                    // getInfo() supplies no process ID; execute() lets us stop the native process.
                    YoutubeDL.destroyProcessById(processId)
                }
            }
            try {
                val request = YoutubeDLRequest(pageUrl.trim()).apply {
                    addOption("--dump-json")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "20")
                    addOption("--retries", "3")
                    // 浏览器 UA：对不被风控的站点无副作用，对 B 站等 412 风控站点是关键
                    addOption("--add-headers", "User-Agent:" + BROWSER_UA)
                    if (!cookieHeader.isNullOrBlank()) {
                        addOption("--add-headers", "Cookie:" + cookieHeader)
                    }
                }
                val response = YoutubeDL.execute(request, processId)
                currentCoroutineContext().ensureActive()
                // Native extractors can return very large metadata for pages containing many
                // embedded videos. Bound the JSON before allocating JSONObject's object graph,
                // then cap inspected formats to avoid memory spikes on low-RAM phones.
                if (response.out.length > MAX_RESOLVE_JSON_CHARS) {
                    throw IllegalStateException(
                        "yt-dlp 返回的媒体信息过大（${response.out.length} 字符），为避免应用内存不足已停止解析。请在网页源列表中选取具体视频或刷新页面重试。"
                    )
                }
                // Parse yt-dlp's --dump-json payload with Android's built-in JSON API.
                // This avoids depending on a transitive Jackson type exposed by the library.
                val info = JSONObject(response.out)
                val formatsJson = info.optJSONArray("formats")
                val formatCount = (formatsJson?.length() ?: 0).coerceAtMost(MAX_RESOLVE_FORMATS)
                val playableFormats = (0 until formatCount).mapNotNull { index ->
                    val format = formatsJson?.optJSONObject(index) ?: return@mapNotNull null
                    val formatUrl = format.optString("url").takeIf { YtDlpMediaPolicy.isSafeHttpUrl(it) }
                        ?: return@mapNotNull null
                    val videoCodec = format.optString("vcodec")
                    val audioCodec = format.optString("acodec")
                    YtDlpFormatCandidate(
                        url = formatUrl,
                        extension = format.optString("ext").takeIf { it.isNotBlank() && it != "null" },
                        hasVideo = videoCodec.isNotBlank() && videoCodec != "none" && videoCodec != "null",
                        hasAudio = audioCodec.isNotBlank() && audioCodec != "none" && audioCodec != "null",
                        bitrate = format.optDouble("tbr", 0.0),
                    )
                }
                val fallbackUrl = info.optString("url")
                    .takeIf { formatCount == 0 && YtDlpMediaPolicy.isSafeHttpUrl(it) }
                val url = YtDlpMediaPolicy.selectPlayableUrl(
                    formats = playableFormats,
                    fallbackUrl = fallbackUrl,
                ) ?: throw IllegalStateException(
                    "yt-dlp 未返回可播放的视频流；只找到音频流或媒体地址格式无效"
                )
                val headers = info.optJSONObject("http_headers")
                val effectiveCookie = headers?.optString("Cookie").orEmpty().ifBlank { cookieHeader.orEmpty() }
                synchronized(resolvedOrigins) {
                    resolvedOrigins[url] = ClaimedOrigin(pageUrl.trim(), effectiveCookie.ifBlank { null })
                }
                val title = info.optString("title").takeIf { it.isNotBlank() && it != "null" }
                    ?: info.optString("fulltitle").takeIf { it.isNotBlank() && it != "null" }
                    ?: "yt-dlp 视频"
                ResolvedMedia(
                    title = title,
                    durationSec = info.optDouble("duration", 0.0).toInt().coerceAtLeast(0),
                    mediaUrl = url,
                    referrer = headers?.optString("Referer").orEmpty().takeUnless { it == "null" }.orEmpty(),
                    cookie = effectiveCookie,
                )
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                cancellationHandle?.dispose()
                YoutubeDL.destroyProcessById(processId)
            }
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
            else -> "yt-dlp 解析失败：${e.message ?: "未知错误"}（网站改版常导致旧规则失效，可在设置里更新 yt-dlp 解析规则）"
        }
    }
}
