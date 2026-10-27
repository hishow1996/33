package io.translatetube.local.translate

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.translatetube.local.TranslateTubeApp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    // 免密钥网页接口：限流时不反复重试，如实报错（见 DeepL 说明）。
    return withBoundedTranslationRetries(ownerJob, allowThrottleRetries = false) {
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

private const val BAIDU_REQUEST_GAP_MS = 150L
private const val BAIDU_PAGE_READY_TIMEOUT_MS = 30_000L
private const val BAIDU_CALL_TIMEOUT_MS = 20_000L
private val newlineRuns = Regex("""\s*\n\s*""")

/*
 * 百度翻译的 transapi 接口已启用 Acs-Token 风控：请求必须携带页面内反爬脚本
 * （heicha/abclite）生成的 Acs-Token 头，否则一律返回 errno 1022「访问出现异常」。
 * 该令牌无法在 App 直连里伪造，因此这里改为离屏 WebView 方案：
 *   1. 后台加载一次 fanyi.baidu.com，让页面自带的反爬脚本初始化完成；
 *   2. 翻译请求通过页面暴露的 window.v2FetchJSON 发出，Acs-Token、X-MT-* 头、
 *      Cookie 由页面逻辑自动附带，与真实浏览器行为一致；
 *   3. 结果经 AndroidBaiduBridge 回传 Kotlin，响应里的 data[].dst 与旧解析逻辑兼容。
 * 已在桌面 Chromium（headless）里端到端验证：v2FetchJSON + Acs-Token 能拿到
 * status:0 与 data[].dst 译文；真机 WebView 的环境更接近真实浏览器，理论上更稳。
 */
private object BaiduWebViewHost {
    private const val PAGE_URL = "https://fanyi.baidu.com/"
    /** 桌面 Chrome UA：fanyi.baidu.com 只在桌面版页面暴露 v2FetchJSON，移动版没有。 */
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val callSeq = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    @Volatile private var lastLoadError: String? = null
    @Volatile private var lastPageTitle: String = ""

    /** 带 HTTP 响应体内容的异常：1022=风控会话需刷新，1000/999=接口参数或格式变化。 */
    class BaiduWebException(message: String) : IllegalStateException(message)

    /** JS 侧把 v2FetchJSON 的结果/错误带回 Kotlin 的桥。 */
    class Bridge {
        @JavascriptInterface
        fun onResult(id: String, body: String) {
            pending.remove(id)?.let { cont -> if (cont.isActive) cont.resume(body) }
        }

        @JavascriptInterface
        fun onError(id: String, message: String) {
            pending.remove(id)?.let { cont ->
                if (cont.isActive) cont.resumeWithException(BaiduWebException(message))
            }
        }
    }

    private fun createWebView(context: android.content.Context): WebView {
        val view = WebView(context.applicationContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        // 纯文本翻译用不到图片，直接禁掉省流量。
        view.settings.blockNetworkImage = true
        view.settings.loadsImagesAutomatically = false
        // 关键：必须用桌面 UA。fanyi.baidu.com 会按 UA 分发不同页面，
        // 移动版页面里没有 v2FetchJSON（真机 WebView 默认移动 UA 会一直超时）。
        view.settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptCookie(true)
        view.addJavascriptInterface(Bridge(), "AndroidBaiduBridge")
        // 记录页面加载状态：DNS/SSL/HTTP 失败在这里暴露，probe 才能快速失败而不是空转 30 秒。
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                if (failingUrl != null && failingUrl.startsWith("https://fanyi.baidu.com")) {
                    lastLoadError = "HTTP $errorCode $description"
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    lastLoadError = "HTTP ${errorResponse.statusCode} 主页请求失败"
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                lastPageTitle = view.title.orEmpty()
            }
        }
        webView = view
        return view
    }

    /**
     * 把 WebView 挂到当前前台 Activity 的窗口上（1x1、不可见）。
     * 部分 ROM（华为等）对完全不 attach 的 WebView 会停掉渲染管线导致 JS 不执行，
     * 因此只要存在前台 Activity 就真实 attach；拿不到 Activity 才退回纯离屏。
     */
    private fun attachToHostWindow(view: WebView) {
        val activity = TranslateTubeApp.instance.currentActivity
        if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
            if (view.parent == null) {
                val host = activity.window.decorView as? android.view.ViewGroup
                if (host != null) {
                    runCatching {
                        host.addView(
                            view,
                            android.widget.FrameLayout.LayoutParams(1, 1),
                        )
                    }
                }
            }
            view.visibility = View.INVISIBLE
        } else {
            // 纯后台（无 Activity）：退回离屏模式。
            if (view.parent == null) view.visibility = View.GONE
        }
    }

    /** 风控会话失效（1022）后重载页面，重新初始化 Acs-Token。 */
    suspend fun reset() {
        pageReady = false
        val view = webView ?: return
        withContext(Dispatchers.Main) { view.loadUrl(PAGE_URL) }
    }

    suspend fun ensureReady() {
        if (pageReady) return
        mutex.withLock {
            if (pageReady) return
            val appCtx = TranslateTubeApp.instance
            lastLoadError = null
            withContext(Dispatchers.Main) {
                val view = webView ?: createWebView(appCtx)
                // 先真实附着到前台 Activity 的窗口（华为等 ROM 对未附着 WebView 可能不执行 JS），再加载。
                attachToHostWindow(view)
                view.loadUrl(PAGE_URL)
            }
            // 轮询页面 bundle 加载完成（v2FetchJSON 由页面脚本定义）。
            val deadline = System.currentTimeMillis() + BAIDU_PAGE_READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                // 主帧加载失败（DNS/SSL/HTTP 错误）直接快速失败，不空转 30 秒。
                lastLoadError?.let { error ->
                    throw IllegalStateException("百度翻译页面加载失败：$error。请检查网络后重试")
                }
                val probe = eval("(typeof window.v2FetchJSON === 'function') ? 'ready' : 'no'")
                if (probe.contains("ready")) {
                    pageReady = true
                    return
                }
                delay(800L)
            }
            // 超时则附带页面诊断（标题/接口状态/UA），便于真机排查是网络、老内核还是页面改版。
            val diag = eval(
                "(function(){return (document.title||'').slice(0,24)+' | v2='+typeof window.v2FetchJSON+"
                    + "' | ua='+navigator.userAgent.slice(0,48);})()",
            )
            val loadHint = lastLoadError?.let { " | load=$it" }.orEmpty()
            throw IllegalStateException(
                "百度翻译页面加载超时（页面状态: ${diag.trim('"')}$loadHint）。请检查网络后重试；若持续失败可换用微软/DeepL 翻译",
            )
        }
    }

    /** 主线程 evaluateJavascript 并等待同步返回值（用于页面状态探测）。回调丢失时防挂死。 */
    private suspend fun eval(script: String): String = withContext(Dispatchers.Main) {
        withTimeout(8_000L) {
            suspendCancellableCoroutine { cont ->
                val view = webView
                if (view == null) {
                    cont.resume("")
                    return@suspendCancellableCoroutine
                }
                view.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result.orEmpty())
                }
            }
        }
    }

    suspend fun transapi(from: String, to: String, query: String): String {
        ensureReady()
        val callId = "baidu-" + callSeq.incrementAndGet()
        val args = JSONObject()
            .put("from", from)
            .put("to", to)
            .put("query", query)
            .put("source", "txt")
            .toString()
        // args 以 JSON 字符串注入再用 JSON.parse 还原，任意文本（引号/换行/emoji）都安全。
        // 注意：v2FetchJSON 内部把 errno 字段缺失的成功响应也当错误 throw（parseInt(undefined)=NaN），
        // 所以 catch 里要先检查 e.data——带 data 的是真成功，照常走 onResult。
        val script = buildString {
            append("(function(){var id=").append(jsString(callId)).append(";var args;")
            append("try{args=JSON.parse(").append(jsString(args)).append(");}catch(e){")
            append("AndroidBaiduBridge.onError(id,'参数序列化失败');return;}")
            append("window.v2FetchJSON({url:'/transapi',args:args,method:'POST',")
            append("acsConf:{useAcsToken:true,useCaptcha:false}})")
            append(".then(function(r){try{AndroidBaiduBridge.onResult(id,JSON.stringify(r));}catch(e){}})")
            append(".catch(function(e){try{")
            append("if(e&&e.data){AndroidBaiduBridge.onResult(id,JSON.stringify(e));return;}")
            append("AndroidBaiduBridge.onError(id,typeof e==='string'?e:JSON.stringify(e));")
            append("}catch(err){}})")
            append("})();")
        }
        return withTimeout(BAIDU_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pending[callId] = cont
                cont.invokeOnCancellation { pending.remove(callId) }
                mainHandler.post {
                    val view = webView
                    if (view == null) {
                        pending.remove(callId)?.let {
                            if (it.isActive) it.resumeWithException(IllegalStateException("百度翻译页面未初始化"))
                        }
                    } else {
                        view.evaluateJavascript(script, null)
                    }
                }
            }
        }
    }

    /** 把任意文本转成安全的 JS 单引号字符串字面量。 */
    private fun jsString(s: String): String = buildString {
        append('\'')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('\'')
    }
}

/** 百度翻译（免费在线）：经离屏 WebView 借页面 JS 上下文走 transapi 接口，不需要 appid/密钥。逐条请求，译文与字幕一一对应。 */
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

    private suspend fun translateLine(text: String, from: String, to: String): String {
        val body = try {
            BaiduWebViewHost.transapi(from, to, text)
        } catch (e: BaiduWebViewHost.BaiduWebException) {
            val message = e.message.orEmpty()
            val riskRejected = message.contains("1022") || message.contains("captcha", ignoreCase = true)
            if (!riskRejected) throw e
            // 风控会话失效：重载页面重新初始化 Acs-Token 后重试一次；再失败就如实报错。
            BaiduWebViewHost.reset()
            BaiduWebViewHost.transapi(from, to, text)
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
