package io.translatetube.local.translate

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
private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

private fun post(url: String, headers: Map<String, String>, body: String): String {
    val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { resp ->
        val text = resp.body?.string() ?: throw IllegalStateException("空响应")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
        return text
    }
}

private fun postForm(url: String, headers: Map<String, String>, fields: Map<String, String>): String {
    val body = fields.entries.joinToString("&") { (k, v) ->
        java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
    }.toRequestBody(FORM)
    val b = Request.Builder().url(url).post(body)
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { resp ->
        val text = resp.body?.string() ?: throw IllegalStateException("空响应")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
        return text
    }
}
private fun get(url: String, headers: Map<String, String>): String {
    val b = Request.Builder().url(url).get()
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { resp ->
        val text = resp.body?.string() ?: throw IllegalStateException("空响应")
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
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

    suspend fun ensureDownloaded(languageTag: String) {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalStateException("不支持的语言: $languageTag")
        val model = TranslateRemoteModel.Builder(language).build()
        val lock = locks.computeIfAbsent(language) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            val manager = RemoteModelManager.getInstance()
            if (!manager.isModelDownloaded(model).await()) {
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            if (!manager.isModelDownloaded(model).await()) {
                throw IllegalStateException("翻译模型下载完成后仍不可用: $languageTag")
            }
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
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = "You are a professional subtitle translator. Translate each numbered line from $src to $tgt. " +
            "Reply with the exact same `index|translation` format, one per line, nothing else. " +
            "Preserve meaning, tone and speaker style. Keep empty or already-$tgt lines unchanged."
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
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

    private fun parseNumbered(content: String, size: Int): List<String> {
        val out = Array(size) { "" }
        Regex("(?m)^\\s*(\\d+)\\s*\\|(.*)$").findAll(content).forEach { m ->
            val i = m.groupValues[1].toIntOrNull()
            if (i != null && i in 0 until size) out[i] = m.groupValues[2].trim()
        }
        return out.toList()
    }
}

/** Google Gemini */
class GeminiEngine(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : TranslationEngine {
    override val id = "gemini"
    override val displayName = model.ifEmpty { "Gemini" }

    override suspend fun listModels(): List<String> {
        val body = get("$baseUrl/v1beta/models?key=$apiKey", emptyMap())
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
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = "Translate each numbered subtitle line from $src to $tgt. " +
            "Reply with exactly `index|translation` per line, nothing else. Preserve tone."
        val payload = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$numbered"))))
        )
        val body = post(
            "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey",
            emptyMap(),
            payload.toString(),
        )
        val text = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parseNumbered(text, lines.size)
    }

    private fun parseNumbered(content: String, size: Int): List<String> {
        val out = Array(size) { "" }
        Regex("(?m)^\\s*(\\d+)\\s*\\|(.*)$").findAll(content).forEach { m ->
            val i = m.groupValues[1].toIntOrNull()
            if (i != null && i in 0 until size) out[i] = m.groupValues[2].trim()
        }
        return out.toList()
    }
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
/** Bing 网页翻译免密钥接口。需要联网，非官方接口可能随时变更或限流。 */
class MicrosoftWebEngine : TranslationEngine {
    override val id = "ms-web"
    override val displayName = "微软翻译（免费在线）"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val from = if (srcLang.isBlank() || srcLang == "auto") "auto-detect" else srcLang.substringBefore('-')
        val to = when (tgtLang) { "zh", "zh-CN", "zh-Hans" -> "zh-Hans"; "zh-TW", "zh-Hant" -> "zh-Hant"; else -> tgtLang.substringBefore('-') }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) { out += ""; continue }
            val body = postForm(
                "https://www.bing.com/ttranslatev3?isVertical=1&&IG=translator& IID=translator.5028.1".replace(" ", ""),
                mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.bing.com/translator"),
                mapOf("fromLang" to from, "to" to to, "text" to line),
            )
            val arr = JSONArray(body)
            val translated = arr.optJSONObject(0)?.optJSONArray("translations")?.optJSONObject(0)?.optString("text").orEmpty()
            if (translated.isBlank()) throw IllegalStateException("微软网页翻译未返回译文")
            out += translated
        }
        return out
    }
}

/** 百度网页翻译免密钥接口。依赖网页端非公开接口，可能失效或触发访问限制。 */
class BaiduWebEngine : TranslationEngine {
    override val id = "baidu-web"
    override val displayName = "百度翻译（免费在线）"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val from = when {
            srcLang.isBlank() || srcLang == "auto" -> "auto"
            srcLang.startsWith("zh") -> "zh"
            else -> srcLang.substringBefore('-')
        }
        val to = when { tgtLang.startsWith("zh") -> "zh"; tgtLang.startsWith("en") -> "en"; else -> tgtLang.substringBefore('-') }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) { out += ""; continue }
            val body = postForm(
                "https://fanyi.baidu.com/transapi?from=$from&to=$to",
                mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://fanyi.baidu.com/"),
                mapOf("from" to from, "to" to to, "query" to line, "source" to "txt"),
            )
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: throw IllegalStateException("百度网页翻译返回格式异常")
            val translated = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("dst") }.joinToString("")
            if (translated.isBlank()) throw IllegalStateException("百度网页翻译未返回译文")
            out += translated
        }
        return out
    }
}
/** Google Cloud Translation API v2（key 即用，固定引擎） */
class GoogleCloudEngine(private val apiKey: String) : TranslationEngine {
    override val id = "gcloud"
    override val displayName = "Google 翻译 API"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        // v2 支持多 q 参数一次 128 段
        val out = mutableListOf<String>()
        val chunks = lines.chunked(64)
        for (chunk in chunks) {
            val sb = StringBuilder("https://translation.googleapis.com/language/translate/v2?key=$apiKey")
                .append("&target=zh-CN")
            if (!srcLang.startsWith("zh")) sb.append("&source=").append(srcLang.substringBefore('-'))
            for (l in chunk) sb.append("&q=").append(java.net.URLEncoder.encode(l, "UTF-8"))
            val body = get(sb.toString(), emptyMap())
            val arr = JSONObject(body).getJSONObject("data").getJSONArray("translations")
            for (i in 0 until arr.length()) out += arr.getJSONObject(i).optString("translatedText")
        }
        return out
    }
}

/** Microsoft Azure Translator（key + region，固定引擎） */
class AzureEngine(private val apiKey: String, private val region: String) : TranslationEngine {
    override val id = "azure"
    override val displayName = "微软翻译 API"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val out = mutableListOf<String>()
        for (chunk in lines.chunked(50)) {
            val arr = JSONArray()
            chunk.forEach { arr.put(JSONObject().put("Text", it)) }
            val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans" +
                if (!srcLang.startsWith("zh")) "&from=${srcLang.substringBefore('-')}" else ""
            val body = post(
                url,
                mapOf("Ocp-Apim-Subscription-Key" to apiKey, "Ocp-Apim-Subscription-Region" to region),
                arr.toString(),
            )
            val resArr = JSONArray(body)
            for (i in 0 until resArr.length()) {
                out += resArr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text")
            }
        }
        return out
    }
}

/** 百度翻译开放平台（appid+密钥，MD5 签名；q 内换行天然支持批量） */
class BaiduEngine(private val appid: String, private val secret: String) : TranslationEngine {
    override val id = "baidu"
    override val displayName = "百度翻译"

    override suspend fun listModels(): List<String>? = null

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val from = if (srcLang.isBlank() || srcLang == "auto") "auto" else srcLang.substringBefore('-')
        val to = "zh"
        val out = mutableListOf<String>()
        // 单次 q 上限 6000 字节：按 20 行/5500 字节分批
        var i = 0
        while (i < lines.size) {
            val batch = mutableListOf<String>()
            var bytes = 0
            while (i < lines.size && batch.size < 20) {
                val b = lines[i].toByteArray(Charsets.UTF_8).size + 1
                if (bytes + b > 5500 && batch.isNotEmpty()) break
                batch += lines[i]; bytes += b; i++
            }
            val q = batch.joinToString("\n")
            val salt = (System.currentTimeMillis() / 1000).toString()
            val sign = md5(appid + q + salt + secret)
            val form = "q=${java.net.URLEncoder.encode(q, "UTF-8")}&from=$from&to=$to&appid=$appid&salt=$salt&sign=$sign"
            val body = post(
                "https://fanyi-api.baidu.com/api/trans/vip/translate",
                emptyMap(),
                form,
            )
            val res = JSONObject(body)
            res.optString("error_code").takeIf { it.isNotEmpty() }?.let {
                throw IllegalStateException("百度 API 错误 $it: ${res.optString("error_msg")}")
            }
            val arr = res.getJSONArray("trans_result")
            for (j in 0 until arr.length()) {
                out += arr.getJSONObject(j).optString("dst").replace("\n", " ")
            }
        }
        return out
    }

    private fun md5(s: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}

/** 引擎工厂：从 ApiProfile 构建实例 */
object EngineFactory {
    fun fromProfile(p: io.translatetube.local.data.ApiProfile): TranslationEngine {
        val key = Crypto.decrypt(p.apiKey)
        return when (p.type) {
            "OPENAI" -> OpenAiCompatEngine(p.baseUrl, key, p.model)
            "GEMINI" -> GeminiEngine(key, p.model, p.baseUrl.ifEmpty { "https://generativelanguage.googleapis.com" })
            "GCLOUD" -> GoogleCloudEngine(key)
            "AZURE" -> AzureEngine(key, p.region)
            "BAIDU" -> BaiduEngine(p.model.ifEmpty { "" }, key)  // model 字段存 AppID
            else -> throw IllegalStateException("未知类型 ${p.type}")
        }
    }
}
