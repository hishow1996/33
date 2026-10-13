package io.translatetube.local.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Be tolerant of common model formatting variations while preserving subtitle row alignment.
 * Providers sometimes ignore the requested index|text format and return numbered Markdown or
 * plain one-translation-per-line output; treating that as an all-empty batch caused a false
 * "status placeholder" error even though the model returned translations.
 */
private fun parseModelTranslations(content: String, size: Int): List<String> {
    if (size <= 0) return emptyList()

    val normalized = content
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("(?m)^\\s*```[\\w-]*\\s*$"), "")
        .trim()
    if (normalized.isEmpty()) return List(size) { "" }

    // Accept JSON arrays when a model disregards the requested text format.
    val jsonArray = runCatching { JSONArray(normalized) }.getOrNull()
    if (jsonArray != null && jsonArray.length() == size) {
        return List(size) { i -> jsonArray.optString(i).trim() }
    }

    // Accepted examples: 0|text, 1 | text, 1. text, 1) text, 1：text, and Markdown bullets.
    val indexedLine = Regex("""^\s*(?:[-*•]\s*)?(?:\*\*)?(\d{1,4})(?:\*\*)?\s*(?:[|｜:：.、)]\s*|-\s+)(.*?)\s*$""")
    val indexed = linkedMapOf<Int, String>()
    normalized.lines().forEach { raw ->
        val line = raw.trim().trim('`').trim()
        val match = indexedLine.matchEntire(line) ?: return@forEach
        val index = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (index in 0..size) {
            val value = match.groupValues[2].trim().removePrefix("**").removeSuffix("**").trim()
            if (value.isNotBlank()) indexed[index] = value
        }
    }
    if (indexed.isNotEmpty()) {
        // If numbering begins at 1 and no zero row exists, accept the common one-based format.
        val base = if (0 !in indexed.keys && indexed.keys.minOrNull() == 1) 1 else 0
        return List(size) { i -> indexed[i + base].orEmpty() }
    }

    // Fall back to positional output only when each input row has exactly one output row.
    val plainLines = normalized.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").trim().removeSurrounding("**").trim() }
        .filter { it.isNotBlank() }
        .filterNot {
            Regex("(?i)^(here are (the )?translations?:?|translations?:?|translation results?:?|译文如下[:：]?)$")
                .matches(it)
        }
    if (plainLines.size == size) return plainLines

    // Single-row probes occasionally return a sentence without any index or wrapper.
    if (size == 1) {
        val single = normalized
            .replace(Regex("(?i)^(here is (the )?translation:?|translation:?|译文[:：]?)\\s*"), "")
            .trim()
        if (single.isNotBlank()) return listOf(single)
    }

    // Keep alignment deterministic; the caller turns an entirely unparseable batch into an error.
    return List(size) { "" }
}


/**
 * Show the actual response URL in connection errors so endpoint/redirect problems can be
 * diagnosed. Never include API keys or access tokens from query parameters in user-visible errors.
 */
private fun safeDiagnosticUrl(url: okhttp3.HttpUrl): String {
    val sensitive = setOf("key", "api_key", "apikey", "token", "access_token")
    val builder = url.newBuilder()
    url.queryParameterNames
        .filter { it.lowercase(java.util.Locale.ROOT) in sensitive }
        .forEach { name -> builder.setQueryParameter(name, "[redacted]") }
    return builder.build().toString()
}

private fun responseRoute(response: okhttp3.Response): String {
    val hops = mutableListOf<String>()
    var current: okhttp3.Response? = response
    while (current != null) {
        hops += "${current.request.method} ${safeDiagnosticUrl(current.request.url)}"
        current = current.priorResponse
    }
    return hops.asReversed().joinToString(" -> ")
}

private fun post(url: String, headers: Map<String, String>, body: String): String {
    val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { resp ->
        val text = resp.body?.string() ?: throw IllegalStateException("空响应")
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} [${responseRoute(resp)}]: ${text.take(500)}")
        }
        return text
    }
}

private fun get(url: String, headers: Map<String, String>): String {
    val b = Request.Builder().url(url).get()
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { resp ->
        val text = resp.body?.string() ?: throw IllegalStateException("空响应")
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} [${responseRoute(resp)}]: ${text.take(500)}")
        }
        return text
    }
}

/**
 * ML Kit 翻译模型下载协调器。
 * 启动预下载和播放重试共用同一把语言锁，并在下载前检查模型是否已安装，
 * 避免每次重新翻译/重试都重复请求同一个压缩模型包。
 */
object MlKitModelCoordinator {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** 播放时只检查模型，不允许隐式下载。 */
    suspend fun ensureDownloaded(languageTag: String) {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalStateException("不支持的离线翻译语言: $languageTag")
        val model = TranslateRemoteModel.Builder(language).build()
        if (!RemoteModelManager.getInstance().isModelDownloaded(model).await()) {
            throw IllegalStateException("离线翻译包 $languageTag 尚未下载，请到设置中手动下载，或切换在线翻译引擎。")
        }
    }

    /** 由设置页面主动调用，用户明确操作后才下载。 */
    suspend fun downloadLanguage(languageTag: String, onProgress: (String) -> Unit = {}) {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalStateException("不支持的离线翻译语言: $languageTag")
        val model = TranslateRemoteModel.Builder(language).build()
        val lock = locks.computeIfAbsent(language) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            val manager = RemoteModelManager.getInstance()
            if (manager.isModelDownloaded(model).await()) {
                onProgress("$languageTag 已下载")
                return
            }
            onProgress("正在下载 $languageTag 离线翻译包…")
            manager.download(model, DownloadConditions.Builder().build()).await()
            if (!manager.isModelDownloaded(model).await()) {
                throw IllegalStateException("$languageTag 离线翻译包下载后仍不可用")
            }
            onProgress("$languageTag 已下载")
        }
    }
}

/** 本地离线翻译（ML Kit bundled 引擎；语言模型首次下载后永久离线） */
class MlKitEngine : TranslationEngine {
    override val id = "local"
    override val displayName = "本地引擎（离线）"

    private suspend fun ensureModel(lang: String) {
        MlKitModelCoordinator.ensureDownloaded(lang)
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        // 源语言未知时按英语兜底（最常见外文视频）
        val s = LangMap.mlkit(if (srcLang.isBlank()) "en" else srcLang)
        val t = LangMap.mlkit(tgtLang)
        ensureModel(s)
        ensureModel(t)
        val opts = TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build()
        val translator = Translation.getClient(opts)
        try {
            return lines.map { if (it.isBlank()) "" else translator.translate(it).await() }
        } finally {
            translator.close()
        }
    }
}

/** OpenAI 兼容引擎（OpenAI / DeepSeek / 智谱 / Kimi / Ollama / 任意兼容中转） */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TranslationEngine {
    override val id = "openai"
    override val displayName = model.ifEmpty { "OpenAI 兼容" }

    private fun endpoint(path: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/v1")) b + path else "$b/v1$path"
    }

    override suspend fun listModels(): List<String> {
        val body = get(endpoint("/models"), mapOf("Authorization" to "Bearer $apiKey"))
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.sorted()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        // 分批：每批 30 行，避免长输出截断/超时
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val batch = lines.subList(i, (i + 30).coerceAtMost(lines.size))
            out += translateBatch(batch, srcLang, tgtLang)
            i += 30
        }
        return out
    }

    private suspend fun translateBatch(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API Key 为空，请在 API 管理中配置私有密钥")
        }
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = "You are a professional subtitle translator. Translate each numbered line from $src to $tgt. " +
            "Reply with the exact same `index|translation` format, one per line, nothing else. " +
            "Include every input index exactly once; never return progress/status text. If unsure, keep the original line after the separator. " +
            "Preserve meaning, tone and speaker style. Keep empty or already-$tgt lines unchanged."
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 1024)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", numbered))
            )
        val body = post(
            endpoint("/chat/completions"),
            mapOf("Authorization" to "Bearer $apiKey"),
            payload.toString(),
        )
        val content = JSONObject(body).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
        return parseNumbered(content, lines.size)
    }

    private fun parseNumbered(content: String, size: Int): List<String> =
        parseModelTranslations(content, size)
}

/** Google Gemini */
class GeminiEngine(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : TranslationEngine {
    private val endpointBase = baseUrl.trimEnd('/')
    override val id = "gemini"
    override val displayName = model.ifEmpty { "Gemini" }

    override suspend fun listModels(): List<String> {
        val body = get("$endpointBase/v1beta/models", mapOf("x-goog-api-key" to apiKey))
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var ok = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
            if (ok) out += m.optString("name").removePrefix("models/")
        }
        return out.sorted()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        // 分批：每批 30 行
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val batch = lines.subList(i, (i + 30).coerceAtMost(lines.size))
            out += translateBatch(batch, srcLang, tgtLang)
            i += 30
        }
        return out
    }

    private suspend fun translateBatch(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Gemini API Key 为空，请在卡片右侧「•••」填入您的 API Key（可前往 Google AI Studio 免费获取）")
        }
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = "Translate each numbered subtitle line from $src to $tgt. " +
            "Reply with exactly `index|translation` per line, nothing else. Include every input index exactly once; never return progress/status text. Preserve tone."
        val payload = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$numbered"))))
        )
        val body = post(
            "$endpointBase/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey),
            payload.toString(),
        )
        val text = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parseNumbered(text, lines.size)
    }

    private fun parseNumbered(content: String, size: Int): List<String> =
        parseModelTranslations(content, size)
}

/** Google Translate 网页端免密钥接口。需要联网；属于非正式接口，可能受服务方限流或调整。 */
class GoogleWebEngine : TranslationEngine {
    override val id = "google-web"
    override val displayName = "Google 翻译（免费在线）"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val source = if (srcLang.isBlank() || srcLang == "auto") "auto" else srcLang.substringBefore('-')
        val target = when (tgtLang) {
            "zh", "zh-CN", "zh-Hans" -> "zh-CN"
            "zh-TW", "zh-Hant" -> "zh-TW"
            else -> tgtLang.substringBefore('-')
        }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) { out += ""; continue }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=" + java.net.URLEncoder.encode(source, "UTF-8") +
                "&tl=" + java.net.URLEncoder.encode(target, "UTF-8") +
                "&dt=t&q=" + java.net.URLEncoder.encode(line, "UTF-8")
            val root = JSONArray(get(url, mapOf("User-Agent" to "Mozilla/5.0")))
            val segments = root.optJSONArray(0) ?: throw IllegalStateException("Google 翻译返回格式异常")
            val translated = buildString {
                for (i in 0 until segments.length()) {
                    val segment = segments.optJSONArray(i) ?: continue
                    append(segment.optString(0))
                }
            }
            if (translated.isBlank()) throw IllegalStateException("Google 翻译未返回译文")
            out += translated
        }
        return out
    }
}

/** Google unified engine: online first, downloaded ML Kit language packs offline/fallback. */
class GoogleHybridEngine(private val context: Context) : TranslationEngine {
    override val id = "google-hybrid"
    override val displayName = "Google 翻译"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (!hasValidatedNetwork()) {
            return try {
                MlKitEngine().translate(lines, srcLang, tgtLang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "当前没有可用网络，且所需离线语言包尚未下载。请联网后在 Google 翻译中下载离线包。",
                    e,
                )
            }
        }
        return try {
            GoogleWebEngine().translate(lines, srcLang, tgtLang)
        } catch (e: CancellationException) {
            throw e
        } catch (onlineError: Exception) {
            try {
                MlKitEngine().translate(lines, srcLang, tgtLang)
            } catch (e: CancellationException) {
                throw e
            } catch (offlineError: Exception) {
                throw IllegalStateException(
                    "Google 在线翻译暂不可用，且离线语言包无法使用。请检查网络，或在 Google 翻译中下载离线包。",
                    offlineError,
                ).also { it.addSuppressed(onlineError) }
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }
}

/** Google Cloud Translation Basic v2：固定官方端点，使用 API Key 请求头认证。 */
class GoogleCloudEngine(private val apiKey: String) : TranslationEngine {
    override val id = "gcloud"
    override val displayName = "Google 翻译 API"

    private val endpoint = "https://translation.googleapis.com/language/translate/v2"

    private fun targetCode(code: String): String = when (code) {
        "zh", "zh-CN", "zh-Hans" -> "zh-CN"
        "zh-TW", "zh-Hant" -> "zh-TW"
        else -> code.substringBefore('-')
    }

    private fun requestPayload(texts: List<String>, srcLang: String, tgtLang: String): String {
        val payload = JSONObject()
            .put("q", JSONArray().apply { texts.forEach { put(it) } })
            .put("target", targetCode(tgtLang))
            .put("format", "text")
        val source = srcLang.trim()
        if (source.isNotBlank() && !source.equals("auto", ignoreCase = true)) {
            payload.put("source", source.substringBefore('-'))
        }
        return payload.toString()
    }

    private suspend fun translateChunk(texts: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (apiKey.isBlank()) throw IllegalStateException("Google Cloud Translation API Key 为空")
        // Cloud Translation Basic v2 documents API-key authentication through the
        // "key" query parameter. Do not send this key to v3/Vertex AI endpoints or rely on
        // an authentication header that may not be accepted by this REST method.
        val endpointWithKey = endpoint + "?key=" +
            java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
        val body = post(
            endpointWithKey,
            emptyMap(),
            requestPayload(texts, srcLang, tgtLang),
        )
        val arr = JSONObject(body).getJSONObject("data").getJSONArray("translations")
        if (arr.length() != texts.size) {
            throw IllegalStateException("Google 翻译返回数量不匹配：请求 ${texts.size} 条，返回 ${arr.length()} 条")
        }
        return List(arr.length()) { index ->
            arr.getJSONObject(index).optString("translatedText").trim()
        }
    }

    override suspend fun testConnection() {
        val result = translateChunk(listOf("Hello"), "en", "zh-CN")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("Cloud Translation Basic v2 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw IllegalStateException("Google Cloud Translation API Key 为空")

        // 空字幕原样保留，只把有内容的行发送到 API，避免空 q 项影响整批请求。
        val output = MutableList(lines.size) { "" }
        val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
        for (chunk in nonBlank.chunked(64)) {
            val translated = translateChunk(chunk.map { it.value }, srcLang, tgtLang)
            chunk.forEachIndexed { index, item -> output[item.index] = translated[index] }
        }
        return output
    }
}

/** 免费大模型默认配置 */
object FreeModelDefaults {
    const val GEMINI_MODEL = "gemini-2.5-flash"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_GEMINI_KEY = ""

    const val DEEPSEEK_MODEL = "deepseek-chat"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_DEEPSEEK_KEY = ""
}

/** 引擎工厂：从 ApiProfile 或内置服务 ID 构建实例 */
object EngineFactory {
    const val ID_GOOGLE = -1L
    const val ID_GOOGLE_ALT = -2L
    const val ID_FREE_GEMINI = -10L
    const val ID_FREE_DEEPSEEK = -11L

    fun getBuiltinEngine(activeId: Long, context: Context): TranslationEngine? {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return when (activeId) {
            ID_GOOGLE, ID_GOOGLE_ALT -> GoogleHybridEngine(context)
            ID_FREE_GEMINI -> {
                val encKey = prefs.getString("free_gemini_key", "").orEmpty()
                val key = if (encKey.isNotBlank()) Crypto.decrypt(encKey) else FreeModelDefaults.DEFAULT_GEMINI_KEY
                val model = prefs.getString("free_gemini_model", FreeModelDefaults.GEMINI_MODEL) ?: FreeModelDefaults.GEMINI_MODEL
                GeminiEngine(apiKey = key, model = model, baseUrl = FreeModelDefaults.GEMINI_BASE_URL)
            }
            ID_FREE_DEEPSEEK -> {
                val encKey = prefs.getString("free_deepseek_key", "").orEmpty()
                val key = if (encKey.isNotBlank()) Crypto.decrypt(encKey) else FreeModelDefaults.DEFAULT_DEEPSEEK_KEY
                val baseUrl = prefs.getString("free_deepseek_base_url", FreeModelDefaults.DEEPSEEK_BASE_URL) ?: FreeModelDefaults.DEEPSEEK_BASE_URL
                val model = prefs.getString("free_deepseek_model", FreeModelDefaults.DEEPSEEK_MODEL) ?: FreeModelDefaults.DEEPSEEK_MODEL
                OpenAiCompatEngine(baseUrl = baseUrl, apiKey = key, model = model)
            }
            else -> null
        }
    }

    fun fromProfile(p: io.translatetube.local.data.ApiProfile): TranslationEngine {
        val key = Crypto.decrypt(p.apiKey)
        return when (p.type) {
            "OPENAI" -> OpenAiCompatEngine(p.baseUrl, key, p.model)
            // Gemini does not expose a Base URL field in the UI. Ignore stale values from older
            // profiles so a Gemini API key is never sent to an OAuth-only Vertex/custom endpoint.
            "GEMINI" -> GeminiEngine(key, p.model, "https://generativelanguage.googleapis.com")
            "GCLOUD" -> GoogleCloudEngine(key)
            else -> throw IllegalStateException("未知类型 ${p.type}")
        }
    }
}
