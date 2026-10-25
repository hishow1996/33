package io.translatetube.local.translate

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/*
 * 免 API Key 的在线翻译引擎：微软翻译（Edge 浏览器翻译接口）、百度翻译（网页接口）。
 *
 * 这些都是非正式接口，随时可能被服务方限流或调整。遇到验证码、封禁或返回格式变化时
 * 只如实报错，不做任何绕过；上层会提示用户换用其他引擎。
 */

private const val WEB_USER_AGENT = "Mozilla/5.0"
private val WEB_JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val webHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private suspend fun executeWebRequest(client: OkHttpClient, request: Request): String {
    val ownerJob = currentCoroutineContext()[Job]
    return withBoundedTranslationRetries(ownerJob) {
        io.translatetube.local.yt.executeCancellableHttpCall(client.newCall(request), ownerJob) { response ->
            readTranslationResponse(response, ownerJob)
        }
    }
}

/** 把本应用的语言码转成各家网页接口使用的语言码。 */
internal object WebLangCodes {
    private fun primary(code: String): String =
        SourceLanguagePolicy.normalizeTag(code).substringBefore('-').lowercase(Locale.ROOT)

    private fun isTraditionalChinese(code: String): Boolean {
        val tag = SourceLanguagePolicy.normalizeTag(code).lowercase(Locale.ROOT)
        return tag == "zh-tw" || tag == "zh-hk" || tag == "zh-mo" || tag.startsWith("zh-hant")
    }

    /** 微软：简体 zh-Hans、繁体 zh-Hant；源语言未知时返回空串，由服务端自动识别。 */
    fun microsoft(code: String, target: Boolean): String {
        if (SourceLanguagePolicy.isUndetermined(code)) return if (target) "zh-Hans" else ""
        val primary = primary(code)
        return when (primary) {
            "zh", "cmn" -> if (isTraditionalChinese(code)) "zh-Hant" else "zh-Hans"
            "nb", "nn", "no" -> "nb"
            "iw" -> "he"
            "in" -> "id"
            "tl" -> "fil"
            else -> primary
        }
    }

    private val baiduCodes = mapOf(
        "en" to "en", "ja" to "jp", "ko" to "kor", "fr" to "fra", "es" to "spa", "th" to "th",
        "ar" to "ara", "ru" to "ru", "pt" to "pt", "de" to "de", "it" to "it", "el" to "el",
        "nl" to "nl", "pl" to "pl", "bg" to "bul", "et" to "est", "da" to "dan", "fi" to "fin",
        "cs" to "cs", "ro" to "rom", "sl" to "slo", "sv" to "swe", "hu" to "hu", "vi" to "vie",
    )

    /** 百度：日语 jp、韩语 kor、法语 fra 等写法特殊；不支持的源语言交给 auto，不支持的目标语言直接报错。 */
    fun baidu(code: String, target: Boolean): String {
        if (SourceLanguagePolicy.isUndetermined(code)) return if (target) "zh" else "auto"
        val primary = primary(code)
        if (primary == "zh" || primary == "cmn") return if (isTraditionalChinese(code)) "cht" else "zh"
        if (primary == "yue") return "yue"
        baiduCodes[primary]?.let { return it }
        if (target) throw IllegalStateException("百度翻译不支持目标语言：$code")
        return "auto"
    }

    /** DeepL oneshot 接口的小写 BCP-47 形式；EN/PT 目标已弃用，须用区域变体。 */
    private val deeplTargetCodes = setOf(
        "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "he", "hu", "id",
        "it", "ja", "ko", "lt", "lv", "nb", "nl", "pl", "ro", "ru", "sk", "sl", "sv",
        "tr", "uk", "vi",
    )

    /**
     * DeepL：目标语言映射到 oneshot 接受的小写形式（zh-Hans/zh-Hant/en-US…），
     * 源语言不支持时交给 auto 由服务端识别，不硬报错。
     */
    fun deepl(code: String, target: Boolean): String {
        if (SourceLanguagePolicy.isUndetermined(code)) return if (target) "zh-Hans" else "auto"
        val primary = primary(code)
        if (primary == "zh" || primary == "cmn") {
            if (!target) return "zh"
            // 繁体中文目标用 zh-Hant（oneshot 支持），简体默认 zh-Hans。
            return if (isTraditionalChinese(code)) "zh-Hant" else "zh-Hans"
        }
        when (primary) {
            "en" -> return if (target) "en-US" else "en"
            "pt" -> return if (target) "pt-BR" else "pt"
            // DeepL 只认挪威语的 nb 形式。
            "no", "nn" -> return "nb"
        }
        if (primary in deeplTargetCodes) return primary
        if (target) throw IllegalStateException("DeepL 翻译不支持目标语言：$code")
        return "auto"
    }
}

/** 按条数和总字符数把待翻译行切成多次请求，保持原有顺序。 */
internal fun <T> chunkForRequest(
    items: List<T>,
    maxItems: Int,
    maxChars: Int,
    sizeOf: (T) -> Int,
): List<List<T>> {
    val chunks = ArrayList<List<T>>()
    var current = ArrayList<T>()
    var chars = 0
    for (item in items) {
        val size = sizeOf(item)
        if (current.isNotEmpty() && (current.size >= maxItems || chars + size > maxChars)) {
            chunks.add(current)
            current = ArrayList()
            chars = 0
        }
        current.add(item)
        chars += size
    }
    if (current.isNotEmpty()) chunks.add(current)
    return chunks
}

/** 解析微软翻译响应：数组长度必须与请求条数一致，否则拒绝，避免字幕错位。 */
internal fun parseMicrosoftTranslations(body: String, expected: Int): List<String> {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        val message = runCatching {
            val error = JSONObject(trimmed).opt("error")
            if (error is JSONObject) error.optString("message") else error?.toString().orEmpty()
        }.getOrDefault("")
        throw IllegalStateException("微软翻译返回错误" + if (message.isNotBlank()) "：$message" else "")
    }
    val array = try {
        JSONArray(trimmed)
    } catch (e: Exception) {
        throw IllegalStateException("微软翻译返回格式异常（可能被限流或接口已调整）", e)
    }
    if (array.length() != expected) {
        throw IllegalStateException("微软翻译返回数量不匹配：请求 $expected 条，返回 ${array.length()} 条")
    }
    return List(expected) { index ->
        array.optJSONObject(index)
            ?.optJSONArray("translations")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
            .trim()
    }
}

/** 解析百度 transapi 响应：把各分句的 dst 拼起来。 */
internal fun parseBaiduTranslation(body: String, targetCode: String): String {
    val root = try {
        JSONObject(body.trim())
    } catch (e: Exception) {
        throw IllegalStateException("百度翻译返回了非预期内容（可能需要验证或接口已调整），请换用其他翻译引擎", e)
    }
    val data = root.optJSONArray("data")
        ?: throw IllegalStateException(
            "百度翻译未返回译文" + if (root.has("error")) "（错误码 ${root.opt("error")}）" else "",
        )
    val pieces = (0 until data.length()).mapNotNull { index ->
        data.optJSONObject(index)?.optString("dst")?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (pieces.isEmpty()) throw IllegalStateException("百度翻译未返回译文")
    // 中日韩泰目标语言的分句直接相连，其他语言用空格隔开。
    val joiner = if (targetCode in setOf("zh", "cht", "yue", "jp", "kor", "th")) "" else " "
    return pieces.joinToString(joiner)
}

private fun isAuthRejected(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("HTTP 401") || message.contains("HTTP 403")
}

private object MicrosoftRoute {
    /** 直连接口被拒绝（401/403）后，本进程内改走「先取令牌再翻译」的路线。 */
    @Volatile var preferAuthFlow = false
    @Volatile var token: String? = null
    @Volatile var tokenFetchedAt = 0L
}

private const val MICROSOFT_MAX_ITEMS = 25
private const val MICROSOFT_MAX_CHARS = 4000
private const val MICROSOFT_TOKEN_TTL_MS = 8L * 60L * 1000L

/** 微软翻译（免费在线）：Edge 浏览器内置翻译使用的接口，不需要 API Key。 */
class MicrosoftWebEngine : TranslationEngine {
    override val id = "microsoft-web"
    override val displayName = "微软翻译（免费在线）"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("微软翻译已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val from = WebLangCodes.microsoft(srcLang, target = false)
        val to = WebLangCodes.microsoft(tgtLang, target = true)
        val output = MutableList(lines.size) { "" }
        val pending = lines.withIndex().filter { it.value.isNotBlank() }
        val chunks = chunkForRequest(pending, MICROSOFT_MAX_ITEMS, MICROSOFT_MAX_CHARS) { it.value.length }
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            val translated = translateChunk(chunk.map { it.value }, from, to)
            chunk.forEachIndexed { position, item -> output[item.index] = translated[position] }
        }
        return output
    }

    private suspend fun translateChunk(texts: List<String>, from: String, to: String): List<String> {
        val body = if (MicrosoftRoute.preferAuthFlow) {
            requestWithToken(texts, from, to)
        } else {
            try {
                requestDirect(texts, from, to)
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                if (!isAuthRejected(e)) throw e
                MicrosoftRoute.preferAuthFlow = true
                requestWithToken(texts, from, to)
            }
        }
        return parseMicrosoftTranslations(body, texts.size)
    }

    /** 开源项目（kiss-translator）使用的直连写法：请求体是字符串数组。 */
    private suspend fun requestDirect(texts: List<String>, from: String, to: String): String {
        val url = "https://edge.microsoft.com/translate/translatetext".toHttpUrl().newBuilder()
            .addQueryParameter("from", from)
            .addQueryParameter("to", to)
            .addQueryParameter("isEnterpriseClient", "false")
            .build()
        val payload = JSONArray().apply { texts.forEach { put(it) } }.toString()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(WEB_JSON_MEDIA))
            .header("Accept", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        return executeWebRequest(webHttp, request)
    }

    /** 备用路线：先向 Edge 取临时令牌，再调用微软翻译 v3。令牌过期（401）时刷新一次。 */
    private suspend fun requestWithToken(texts: List<String>, from: String, to: String): String {
        var forceRefresh = false
        while (true) {
            val token = authToken(forceRefresh)
            val urlBuilder = "https://api-edge.cognitive.microsofttranslator.com/translate".toHttpUrl().newBuilder()
                .addQueryParameter("api-version", "3.0")
                .addQueryParameter("to", to)
            if (from.isNotEmpty()) urlBuilder.addQueryParameter("from", from)
            val payload = JSONArray().apply { texts.forEach { put(JSONObject().put("Text", it)) } }.toString()
            val request = Request.Builder()
                .url(urlBuilder.build())
                .post(payload.toRequestBody(WEB_JSON_MEDIA))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            try {
                return executeWebRequest(webHttp, request)
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                if (!forceRefresh && isAuthRejected(e)) {
                    forceRefresh = true
                    continue
                }
                if (isAuthRejected(e)) MicrosoftRoute.preferAuthFlow = false
                throw e
            }
        }
    }

    private suspend fun authToken(forceRefresh: Boolean): String {
        val now = System.currentTimeMillis()
        val cached = MicrosoftRoute.token
        if (!forceRefresh && cached != null && now - MicrosoftRoute.tokenFetchedAt < MICROSOFT_TOKEN_TTL_MS) {
            return cached
        }
        val request = Request.Builder()
            .url("https://edge.microsoft.com/translate/auth")
            .get()
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        val token = executeWebRequest(webHttp, request).trim()
        if (token.isEmpty() || token.startsWith("{") || token.startsWith("<")) {
            throw IllegalStateException("微软翻译未能获取访问令牌（接口可能已调整）")
        }
        MicrosoftRoute.token = token
        MicrosoftRoute.tokenFetchedAt = now
        return token
    }
}

/** 只存在内存里的 Cookie 容器：百度网页接口需要先访问首页拿到会话 Cookie。 */
private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { CopyOnWriteArrayList() }
        for (cookie in cookies) {
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return list.filter { it.expiresAt > now && it.matches(url) }
    }
}

private const val BAIDU_SESSION_TTL_MS = 20L * 60L * 1000L
private const val BAIDU_REQUEST_GAP_MS = 150L
private val newlineRuns = Regex("""\s*\n\s*""")

private object BaiduSession {
    val client: OkHttpClient = webHttp.newBuilder().cookieJar(MemoryCookieJar()).build()
    @Volatile var readyAt = 0L
}

/** 百度翻译（免费在线）：网页版 transapi 接口，不需要 appid/密钥。逐条请求，译文与字幕一一对应。 */
class BaiduWebEngine : TranslationEngine {
    override val id = "baidu-web"
    override val displayName = "百度翻译（免费在线）"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("百度翻译已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val from = WebLangCodes.baidu(srcLang, target = false)
        val to = WebLangCodes.baidu(tgtLang, target = true)
        ensureSession()
        val output = ArrayList<String>(lines.size)
        var requestedBefore = false
        for (line in lines) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) {
                output.add("")
                continue
            }
            // 连续请求之间留一点间隔，降低被限流的概率。
            if (requestedBefore) delay(BAIDU_REQUEST_GAP_MS)
            output.add(translateLine(line.replace(newlineRuns, " ").trim(), from, to))
            requestedBefore = true
        }
        return output
    }

    private suspend fun ensureSession() {
        val now = System.currentTimeMillis()
        if (now - BaiduSession.readyAt < BAIDU_SESSION_TTL_MS) return
        // 与开源项目一致：先访问两次首页，让服务端下发会话 Cookie。
        repeat(2) {
            val request = Request.Builder()
                .url("https://fanyi.baidu.com/")
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            executeWebRequest(BaiduSession.client, request)
        }
        BaiduSession.readyAt = now
    }

    private suspend fun translateLine(text: String, from: String, to: String): String {
        val form = FormBody.Builder()
            .add("from", from)
            .add("to", to)
            .add("query", text)
            .add("source", "txt")
            .build()
        val request = Request.Builder()
            .url("https://fanyi.baidu.com/transapi")
            .post(form)
            .header("Origin", "https://fanyi.baidu.com")
            .header("Referer", "https://fanyi.baidu.com/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        val body = try {
            executeWebRequest(BaiduSession.client, request)
        } catch (e: IllegalStateException) {
            // 会话失效时下次请求重新拿 Cookie。
            BaiduSession.readyAt = 0L
            throw e
        }
        return parseBaiduTranslation(body, to)
    }
}

/*
 * DeepL（免费在线）：伪装 DeepL iOS 客户端的 OneShot 接口（oneshot-free.www.deepl.com），
 * 不需要 API Key。请求形状照抄社区逆向实现（DeepLX）：
 *   - Authorization 头是字面量 "None"（匿名流量），不是 Bearer。
 *   - x-app-instance-id / x-app-session-id 是进程内稳定的 UUID，逐请求轮换反而是更强的机器特征。
 *   - User-Agent 是 DeepL/<version> CFNetwork/... Darwin/... 形式，且与请求体 app_information
 *     里的版本号保持一致（不一致会被服务端判定为异常流量）。
 *   - 匿名流量单次请求的 text 总长上限约 1500 个 UTF-16 码元，超过会失败，所以按字符切批。
 * 已知限制：真实 iOS 客户端的 TLS 指纹与 OkHttp 不同，服务端若严格校验 TLS+UA 一致性会限流
 * （HTTP 429）。对此如实报错，不做绕过；用户网络环境不同，成功率也不同。
 */
private object DeepLSession {
    /** 与 iOS 客户端一致的稳定身份标识；整个进程复用同一组 UUID。 */
    val instanceId: String = java.util.UUID.randomUUID().toString()
    val sessionId: String = java.util.UUID.randomUUID().toString()

    /** 伪装的客户端版本，请求体与 User-Agent 同源，改动时两处一起改。 */
    const val APP_VERSION = "26.52"
    const val APP_BUILD = "5443737"
    const val OS_VERSION = "26.0"
    const val CFNETWORK_VERSION = "3826.600.41"
    const val DARWIN_VERSION = "25.0.0"

    val userAgent: String = "DeepL/$APP_VERSION CFNetwork/$CFNETWORK_VERSION Darwin/$DARWIN_VERSION"

    /** OneShot 接口要求 .deepl.com 域的 Cookie（冷启动访问主页会种下 userCountry 等）。 */
    private val cookies = CopyOnWriteArrayList<Cookie>()

    @Volatile var warmedUp = false

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
                // 直接平铺保存，取用时用 Cookie.matches 做域匹配：
                // 主页种在 www.deepl.com / .deepl.com 的 Cookie 要能匹配 oneshot-free.www.deepl.com。
                incoming.forEach { cookie ->
                    DeepLSession.cookies.removeIf { it.name == cookie.name && it.domain == cookie.domain }
                    DeepLSession.cookies.add(cookie)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                DeepLSession.cookies.filter { it.matches(url) }
        })
        .build()

    /** 尽力而为的主页预热：拿一次 Cookie；失败不阻塞翻译请求。 */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://www.deepl.com/translator")
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
            ).execute().use { it.body?.close() }
        }
    }
}

private const val DEEPL_MAX_ITEMS = 32
private const val DEEPL_MAX_CHARS = 1500
private const val DEEPL_REQUEST_GAP_MS = 250L
private const val DEEPL_ENDPOINT = "https://oneshot-free.www.deepl.com/v1/translate"

/** DeepL 翻译（免费在线）：iOS 客户端 OneShot 接口，不需要 API Key。 */
class DeepLWebEngine : TranslationEngine {
    override val id = "deepl-web"
    override val displayName = "DeepL（免费在线）"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("DeepL 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val from = WebLangCodes.deepl(srcLang, target = false)
        val to = WebLangCodes.deepl(tgtLang, target = true)
        val output = MutableList(lines.size) { "" }
        val pending = lines.withIndex().filter { it.value.isNotBlank() }
        // oneshot 限制的是 text 数组的 UTF-16 总长，所以 sizeOf 用 Kotlin 的 length（就是 UTF-16 码元数）。
        val chunks = chunkForRequest(pending, DEEPL_MAX_ITEMS, DEEPL_MAX_CHARS) { it.value.length }
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            if (chunk !== chunks.first()) delay(DEEPL_REQUEST_GAP_MS)
            val translated = translateChunk(chunk.map { it.value }, from, to)
            chunk.forEachIndexed { position, item -> output[item.index] = translated[position] }
        }
        return output
    }

    private suspend fun translateChunk(texts: List<String>, from: String, to: String): List<String> {
        DeepLSession.warmUp()
        // 手工按 iOS 客户端的字段顺序拼 JSON（org.json 的字段序不保证）；
        // text 数组仍用 JSONArray 序列化，保证转义正确。
        val textArray = JSONArray().apply { texts.forEach { put(it) } }
        val appInformation = JSONObject().apply {
            put("os", "iOS")
            put("os_version", DeepLSession.OS_VERSION)
            put("app_version", DeepLSession.APP_VERSION)
            put("app_build", DeepLSession.APP_BUILD)
            put("instance_id", DeepLSession.instanceId)
        }.toString()
        val payload = buildString {
            append("{\"text\":").append(textArray)
            append(",\"target_lang\":\"").append(to).append('"')
            if (from != "auto") append(",\"source_lang\":\"").append(from).append('"')
            append(",\"usage_type\":\"translate\"")
            append(",\"app_information\":").append(appInformation)
            append('}')
        }
        val request = Request.Builder()
            .url(DEEPL_ENDPOINT)
            .post(payload.toRequestBody(WEB_JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("Authorization", "None")
            .header("User-Agent", DeepLSession.userAgent)
            .header("x-app-os-version", DeepLSession.OS_VERSION)
            .header("x-app-instance-id", DeepLSession.instanceId)
            .header("x-app-session-id", DeepLSession.sessionId)
            .header("Accept", "application/json")
            .build()
        val body = try {
            executeWebRequest(DeepLSession.client, request)
        } catch (e: IllegalStateException) {
            throw translateDeepLHttpError(e)
        }
        return parseDeepLTranslations(body, texts.size)
    }
}

/** 把 oneshot 的 HTTP 状态码翻成用户能看懂的原因，不改写其他异常。 */
private fun translateDeepLHttpError(error: IllegalStateException): IllegalStateException {
    val message = error.message.orEmpty()
    return when {
        message.contains("HTTP 429") -> IllegalStateException(
            "DeepL 拒绝了请求（限流）。匿名流量有频率限制，请稍后重试或降低字幕长度；" +
                "持续失败时可换用微软/百度翻译",
            error,
        )
        message.contains("HTTP 403") || message.contains("HTTP 401") ->
            IllegalStateException("DeepL 拒绝了请求（访问被拒）。可能是网络环境或客户端特征被风控，请稍后重试", error)
        message.contains("HTTP 5") -> IllegalStateException("DeepL 服务暂时不可用，请稍后重试", error)
        else -> error
    }
}

/** 解析 DeepL oneshot 响应：translations 数组必须与请求条数一致，否则拒绝，避免字幕错位。 */
internal fun parseDeepLTranslations(body: String, expected: Int): List<String> {
    val root = try {
        JSONObject(body.trim())
    } catch (e: Exception) {
        throw IllegalStateException("DeepL 返回了非预期内容（可能被限流或接口已调整），请换用其他翻译引擎", e)
    }
    if (root.has("error") || (root.has("message") && !root.has("translations"))) {
        val message = root.optJSONObject("error")?.optString("message")
            ?: root.optString("message")
        throw IllegalStateException("DeepL 返回错误：" + message.ifBlank { "未知错误" })
    }
    val translations = root.optJSONArray("translations")
        ?: throw IllegalStateException("DeepL 响应缺少 translations 字段")
    if (translations.length() != expected) {
        throw IllegalStateException("DeepL 返回数量不匹配：请求 $expected 条，返回 ${translations.length()} 条")
    }
    return List(expected) { index ->
        translations.optJSONObject(index)?.optString("text").orEmpty()
    }
}
