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
