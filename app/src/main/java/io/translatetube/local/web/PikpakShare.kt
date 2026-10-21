package io.translatetube.local.web

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * PikPak 分享链接（mypikpak.com/s/...）的文件夹清单支持。
 *
 * 双通道设计：
 *  1. 被动通道（最可靠）：NET_HOOK 抓网页自己发的 share API 响应（凭证/直链全由网页产生），
 *     [parseHookPayload] 解析成文件树；
 *  2. 主动通道：复用安卓客户端公开鉴权算法（captcha_sign 9 轮 md5 → /v1/shield/captcha/init）
 *     自取 captcha_token，游客态直接调 /drive/v1/share/detail 带 parent_id 递归枚举整棵目录树
 *     （2025-10 沙箱实测 200 通过；无直链的视频再用 /drive/v1/share/file_info 补取）。
 *
 * 直链优先级与 PikPak 网页一致：web_content_link > links{*.url} > medias[].link.url。
 * 直链带时效 token，点播时用最新一次枚举结果即可直接抽轨。
 */
internal object PikpakShare {

    private const val DRIVE_BASE = "https://api-drive.mypikpak.com"
    private const val USER_BASE = "https://user.mypikpak.com"
    private const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
    private const val CLIENT_VERSION = "1.21.0"
    private const val PACKAGE_NAME = "com.pikcloud.pikpak"
    private const val USER_AGENT = "ANDROID-com.pikcloud.pikpak/1.21.0"
    private const val CLIENT_VERSION_CODE = "10083"
    /** captcha_token 缓存有效期（毫秒）；过期后重新 init。 */
    private const val CAPTCHA_TTL_MS = 5 * 60 * 1000L

    /** 安卓客户端 captcha_sign 的 9 轮 md5 盐（客户端逆向固定值）。 */
    private val CAPTCHA_SALTS = listOf(
        "",
        "E32cSkYXC2bciKJGxRsE8ZgwmH/YwkvpD6/O9guSOa2irCwciH4xPHaH",
        "QtqgfMgHP2TFl",
        "zOKgHT56L7nIzFzDpUGhpWFrgP53m3G6ML",
        "S",
        "THxpsktzfFXizUv7DK1y/N7NZ1WhayViluBEvAJJ8bA1Wr6",
        "y9PXH3xGUhG/zQI8CaapRw2LhldCaFM9CRlKpZXJvj+pifu",
        "+RaaG7T8FRTI4cP019N5y9ofLyHE9ySFUr",
        "6Pf1l8UTeuzYldGtb/d",
    )

    internal data class PikpakEntry(
        val id: String,
        val name: String,
        val parentId: String,
        val isFolder: Boolean,
        val isVideo: Boolean,
        val downloadUrl: String,
        val sizeBytes: Long,
        /** HLS 转码播放链（medias[0].link.url）：分段清单天然全量，
         *  播放与音轨提取都比直链流式读取更完整（直链游客态常被 CDN 截断成预览段）。 */
        val mediasUrl: String = "",
    )

    internal data class PikpakHookData(
        val shareId: String?,
        val parentId: String,
        val api: String,
        val passcodeToken: String,
        val headers: Map<String, String>,
        val entries: List<PikpakEntry>,
    )

    internal data class PikpakEnumeration(
        val entries: List<PikpakEntry>,
        val visitedParents: Set<String>,
        val needsPasscode: Boolean,
        val error: String?,
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "wmv",
        "mpg", "mpeg", "ts", "3gp", "ogv", "m2ts",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** mypikpak.com/s/{shareId} 形式的分享页地址。 */
    fun parseShareId(url: String): String? {
        val raw = url.substringBefore('#')
        if (!raw.contains("mypikpak.com", ignoreCase = true)) return null
        val match = Regex("""/s/([A-Za-z0-9_-]{6,})""").find(raw) ?: return null
        return match.groupValues[1]
    }

    /**
     * 从任意粘贴文本（链接 + 密码行 + 杂讯混排）提取 PikPak 提取码。
     * 支持「密码: bubq」「提取码：xx」「口令 xx」「pass code: xx」「code: xx」，
     * 以及链接自身查询参数 ?p= / ?code= / ?pass_code=。
     * 返回原始码值（不做大小写转换——PikPak 区分大小写）。
     */
    fun extractPassCode(text: String): String? {
        if (text.isBlank()) return null
        // 1) 链接查询参数优先：https://.../s/xxx?p=abc / ?code=abc / ?pass_code=abc
        val urlPattern = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
        for (url in urlPattern.findAll(text)) {
            for (key in listOf("pass_code", "code", "p")) {
                val query = Regex("""[?&]$key=([A-Za-z0-9_-]{2,32})""", RegexOption.IGNORE_CASE).find(url.value)
                if (query != null) return query.groupValues[1]
            }
        }
        // 2) 文本里的密码标签行
        val labelPattern = Regex(
            """(?:密码|提取码|提取碼|访问码|訪問碼|口令|pass[ _-]?code|code)\s*[:：\s]\s*([A-Za-z0-9_-]{2,32})""",
            RegexOption.IGNORE_CASE,
        )
        val labeled = labelPattern.findAll(text).firstOrNull { it.groupValues[1].let { v -> v.isNotBlank() && !v.equals("code", true) } }
        return labeled?.groupValues?.get(1)
    }

    /** 持久化的自生成设备 ID（32 位 hex，PikPak 接受客户端自造设备标识）。 */
    fun deviceIdentifier(context: Context): String {
        val prefs = context.getSharedPreferences("pikpak_share", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val chars = "0123456789abcdef"
        val uuid = buildString { repeat(32) { append(chars.random()) } }
        prefs.edit().putString("device_id", uuid).apply()
        return uuid
    }

    /**
     * 解析 NET_HOOK 上报的 share API 结构化载荷。
     * api 取值：share（校验/提取码）、list、detail（目录树）、file_info（单文件）。
     */
    fun parseHookPayload(payload: String): PikpakHookData? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val reqUrl = root.optString("reqUrl").trim()
        val reqBody = root.optString("reqBody").trim()
        val shareId = queryParam(reqUrl, "share_id")
            ?: runCatching { JSONObject(reqBody).optString("share_id") }.getOrNull()?.takeIf { it.isNotBlank() }
        // GET 端点（detail/list）的 parent_id 在 query；POST body 为空则兜底 query
        val parentId = runCatching { JSONObject(reqBody).optString("parent_id") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: queryParam(reqUrl, "parent_id")
            .orEmpty()
        val headers = buildMap {
            val obj = root.optJSONObject("headers")
            if (obj != null) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key.lowercase(), value)
                }
            }
        }
        val entries = mutableListOf<PikpakEntry>()
        val files = root.optJSONArray("files")
        if (files != null) {
            for (index in 0 until files.length()) {
                val entry = parseFile(files.optJSONObject(index) ?: continue, parentId) ?: continue
                entries.add(entry)
            }
        }
        return PikpakHookData(
            shareId = shareId,
            parentId = parentId,
            api = root.optString("api"),
            passcodeToken = root.optString("passcodeToken"),
            headers = headers,
            entries = entries,
        )
    }

    /**
     * BFS 递归枚举分享目录树（rootParentId="" 为整棵树；传文件夹 id 则展开该子树）。
     * 游客态直连：本地计算 captcha_sign 换取 captcha_token 后 GET /drive/v1/share/detail。
     * skipParents：已列出过的目录 id（增量枚举时跳过，省 API 调用）。
     */
    fun enumerateAll(
        shareId: String,
        rootParentId: String = "",
        passcodeToken: String = "",
        passCode: String = "",
        deviceUuid: String = "",
        skipParents: Set<String> = emptySet(),
        maxFolders: Int = 120,
        maxEntries: Int = 900,
    ): PikpakEnumeration {
        val deviceId = deviceUuid.ifBlank { randomHex32() }
        val entries = mutableListOf<PikpakEntry>()
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(rootParentId))
        var needsPasscode = false
        var lastError: String? = null
        var captcha = captchaToken(deviceId, "GET:$DRIVE_BASE/drive/v1/share/detail")
        if (captcha == null) {
            return PikpakEnumeration(emptyList(), visited, needsPasscode, "PikPak 鉴权初始化失败，请检查网络后重试。")
        }
        var captchaRefreshed = false
        var fileInfoQuota = 30
        while (queue.isNotEmpty() && visited.size < maxFolders && entries.size < maxEntries) {
            val parent = queue.removeFirst()
            if (!visited.add(parent)) continue
            // 已完整枚举过的子树直接跳过（调用方只应传入主动 BFS 过的目录，
            // hook 抓过的单层目录不在此列，避免漏掉其子文件夹）
            if (parent != rootParentId && parent in skipParents) continue
            var pageToken = ""
            var pages = 0
            while (pages < 20 && entries.size < maxEntries) {
                pages++
                val url = "$DRIVE_BASE/drive/v1/share/detail".toHttpUrl().newBuilder()
                    .addQueryParameter("share_id", shareId)
                    .addQueryParameter("pass_code_token", passcodeToken)
                    .apply {
                        // 明文提取码直传（实测 detail 接口接受 pass_code 并直接放行文件列表）
                        if (passCode.isNotBlank()) addQueryParameter("pass_code", passCode)
                    }
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("thumbnail_size", "SIZE_LARGE")
                    .addQueryParameter("order", "3")
                    .addQueryParameter("folders_first", "true")
                    .addQueryParameter("client_id", CLIENT_ID)
                    .addQueryParameter("captcha_token", captcha)
                    .apply {
                        if (parent.isNotBlank()) addQueryParameter("parent_id", parent)
                        if (pageToken.isNotBlank()) addQueryParameter("page_token", pageToken)
                    }
                    .build()
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("X-Device-Id", deviceId)
                    .header("X-Peer-Id", deviceId)
                    .header("Country", "CN")
                    .header("X-User-Region", "1")
                    .header("X-Alt-Capability", "3")
                    .header("X-Client-Version-Code", CLIENT_VERSION_CODE)
                    .header("X-Captcha-Token", captcha.orEmpty())
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull()
                if (response == null) {
                    lastError = "网络请求失败，请检查网络后重试。"
                    break
                }
                response.use { resp ->
                    val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    when {
                        resp.code == 400 && json != null && json.optString("error") == "captcha_invalid" && !captchaRefreshed -> {
                            captchaRefreshed = true
                            captcha = captchaToken(deviceId, "GET:$DRIVE_BASE/drive/v1/share/detail") ?: captcha
                            return@use
                        }
                        resp.code == 400 || resp.code == 409 -> {
                            needsPasscode = true
                            lastError = json?.optString("error_description")
                                ?.takeIf { it.isNotBlank() }
                                ?: "该分享需要提取码：请先在网页里输入提取码，再点展开。"
                        }
                        resp.code == 401 || resp.code == 403 -> {
                            lastError = "PikPak 接口拒绝了直接读取（HTTP ${resp.code}）。请在网页里点开任意文件夹，让它自己列出清单。"
                        }
                        resp.code !in 200..299 -> {
                            lastError = "PikPak 接口返回 HTTP ${resp.code}"
                        }
                    }
                    if (lastError != null) return@use
                    if (json == null) {
                        lastError = "清单响应解析失败。"
                        return@use
                    }
                    val status = json.optString("share_status")
                    if (status.isNotBlank() && status != "OK") {
                        val statusText = json.optString("share_status_text")
                        if (status.contains("PASS_CODE", ignoreCase = true)) {
                            needsPasscode = true
                            lastError = statusText.ifBlank { "该分享需要提取码：请先在网页里输入提取码，再点展开。" }
                        } else {
                            lastError = statusText.ifBlank { "分享状态异常：$status" }
                        }
                        return@use
                    }
                    if (passcodeToken.isBlank()) {
                        // detail 响应里带新的 pass_code_token 时记住它（后续分页/子目录需要）
                        val freshToken = json.optString("pass_code_token")
                        if (freshToken.isNotBlank()) capturedPasscodeToken = freshToken
                    }
                    val files = json.optJSONArray("files")
                    if (files != null) {
                        for (index in 0 until files.length()) {
                            val entry = parseFile(files.optJSONObject(index) ?: continue, parent) ?: continue
                            entries.add(entry)
                        }
                    }
                    pageToken = json.optString("next_page_token").orEmpty()
                }
                if (lastError != null) break
                if (captchaRefreshed) {
                    // 换了新 captcha_token，重试当前页
                    captchaRefreshed = false
                    pages--
                    continue
                }
                if (pageToken.isBlank()) break
            }
            if (lastError != null) break
            // 本层新发现的子文件夹入队（BFS）
            entries.filter { it.parentId == parent && it.isFolder && it.id !in visited }
                .forEach { queue.addLast(it.id) }
        }
        // 无直链的视频条目补取 file_info（限量，防拖慢清单；只补视频，图片/文档不浪费配额）
        if (lastError == null && fileInfoQuota > 0) {
            val detailCaptcha = captchaToken(deviceId, "GET:$DRIVE_BASE/drive/v1/share/file_info")
            if (detailCaptcha != null) {
                val updated = entries.map { entry ->
                    if (entry.isVideo && entry.downloadUrl.isBlank() && fileInfoQuota > 0) {
                        fileInfoQuota--
                        fetchFileInfo(shareId, entry.id, passcodeToken, passCode, deviceId, detailCaptcha)?.let { fetched ->
                            PikpakEntry(entry.id, entry.name, entry.parentId, entry.isFolder,
                                fetched.isVideo, fetched.downloadUrl, fetched.sizeBytes)
                        } ?: entry
                    } else {
                        entry
                    }
                }
                entries.clear()
                entries.addAll(updated)
            }
        }
        if (queue.isNotEmpty() && lastError == null && entries.size >= maxEntries) {
            lastError = "分享内容较多，已读取前 $maxEntries 个文件；可点具体文件夹继续展开。"
        }
        return PikpakEnumeration(entries.toList(), visited.toSet(), needsPasscode, lastError)
    }

    /** 最近一次主动枚举拿到的 pass_code_token（无输入码时 detail 可能下发）。 */
    @Volatile
    internal var capturedPasscodeToken: String = ""

    /** captcha_token 内存缓存（action → token/过期时刻），5 分钟内复用省 init 请求。 */
    private val captchaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** 本地计算 captcha_sign（9 轮 md5 链）→ /v1/shield/captcha/init 换 captcha_token。 */
    private fun captchaToken(deviceId: String, action: String): String? {
        val cached = captchaCache[action]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            // 缓存的 token 绑定同一 deviceId；持久化设备 ID 稳定，直接复用
            if (cachedDeviceId == deviceId) return cached.first
        }
        val timestamp = System.currentTimeMillis().toString()
        var sign = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + deviceId + timestamp
        for (salt in CAPTCHA_SALTS) {
            sign = md5Hex(sign + salt)
        }
        val body = JSONObject().apply {
            put("action", action)
            put("captcha_token", "")
            put("client_id", CLIENT_ID)
            put("device_id", deviceId)
            put("meta", JSONObject().apply {
                put("captcha_sign", "1.$sign")
                put("user_id", "")
                put("package_name", PACKAGE_NAME)
                put("client_version", CLIENT_VERSION)
                put("timestamp", timestamp)
            })
            put("redirect_uri", "https://api.mypikpak.com/v1/auth/callback")
        }
        val url = "$USER_BASE/v1/shield/captcha/init".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .build()
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("X-Device-Id", deviceId)
            .header("X-Peer-Id", deviceId)
            .build()
        val token = runCatching {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                json.optString("captcha_token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        if (token != null) {
            cachedDeviceId = deviceId
            captchaCache[action] = token to (System.currentTimeMillis() + CAPTCHA_TTL_MS)
        }
        return token
    }

    /** 缓存 token 对应的设备 ID（设备变了缓存作废）。 */
    @Volatile
    private var cachedDeviceId: String = ""

    /** 单文件直链补取：GET /drive/v1/share/file_info?share_id=&file_id=&pass_code_token=。 */
    private fun fetchFileInfo(
        shareId: String,
        fileId: String,
        passcodeToken: String,
        passCode: String = "",
        deviceId: String,
        captcha: String,
    ): PikpakEntry? {
        val url = "$DRIVE_BASE/drive/v1/share/file_info".toHttpUrl().newBuilder()
            .addQueryParameter("share_id", shareId)
            .addQueryParameter("file_id", fileId)
            .addQueryParameter("pass_code_token", passcodeToken)
            .apply {
                if (passCode.isNotBlank()) addQueryParameter("pass_code", passCode)
            }
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("captcha_token", captcha)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-Device-Id", deviceId)
            .header("X-Peer-Id", deviceId)
            .header("Country", "CN")
            .header("X-User-Region", "1")
            .header("X-Alt-Capability", "3")
            .header("X-Client-Version-Code", CLIENT_VERSION_CODE)
            .header("X-Captcha-Token", captcha)
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                parseFile(json, "")
            }
        }.getOrNull()
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomHex32(): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(32) { append(chars.random()) } }
    }

    /**
     * 解析单个文件条目。文件夹判定 kind=="drive#folder" 或 file_type=="[F]"；
     * 直链优先级 web_content_link > links{*.url} > medias[].link.url > download_url。
     */
    private fun parseFile(file: JSONObject, parentId: String): PikpakEntry? {
        val id = file.optString("id").trim()
        if (id.isEmpty()) return null
        val name = file.optString("name").trim()
        val kind = file.optString("kind").trim()
        val fileType = file.optString("file_type").trim()
        val mime = file.optString("mime_type").trim().lowercase()
        val size = file.optString("size").trim().toLongOrNull() ?: 0L
        var mediaUrl = file.optString("web_content_link").orEmpty()
        if (mediaUrl.isBlank()) {
            val links = file.optJSONObject("links")
            if (links != null) {
                val keys = links.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val candidate = links.optJSONObject(key)?.optString("url").orEmpty()
                    if (candidate.isNotBlank()) {
                        mediaUrl = candidate
                        break
                    }
                }
            }
        }
        // medias 转码链单独提取：HLS 分段形态用于播放与全量音轨提取，
        // 与下载直链互为备份（保留原优先级语义）。
        var hlsUrl = ""
        val medias = file.optJSONArray("medias")
        if (medias != null && medias.length() > 0) {
            for (index in 0 until medias.length()) {
                val media = medias.optJSONObject(index) ?: continue
                val candidate = media.optJSONObject("link")?.optString("url").orEmpty()
                if (candidate.isNotBlank()) {
                    hlsUrl = candidate
                    break
                }
            }
        }
        if (mediaUrl.isBlank()) mediaUrl = hlsUrl.ifBlank { file.optString("download_url").orEmpty() }
        val isFolder = kind == "drive#folder" || fileType == "[F]"
        val isVideo = !isFolder && (
            mediaUrl.isNotBlank() ||
                mime.startsWith("video/") ||
                hasVideoExtension(name)
            )
        return PikpakEntry(id, name, parentId, isFolder, isVideo, mediaUrl, size, hlsUrl)
    }

    private fun hasVideoExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in VIDEO_EXTENSIONS
    }

    private fun queryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index <= 0) continue
            if (pair.substring(0, index) == key) {
                return runCatching { java.net.URLDecoder.decode(pair.substring(index + 1), "UTF-8") }.getOrNull()
            }
        }
        return null
    }
}
