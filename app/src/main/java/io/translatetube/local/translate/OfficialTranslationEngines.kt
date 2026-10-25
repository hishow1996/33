package io.translatetube.local.translate

import android.util.Base64
import io.translatetube.local.data.ApiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Extra credentials are separately encrypted. The primary credential remains in ApiProfile.apiKey
 * so existing OpenAI/Gemini/Google Cloud profiles keep their old on-disk representation.
 */
internal fun readApiProfileCredential(profile: ApiProfile?, key: String): String {
    if (profile == null || profile.credentialsJson.isBlank() || key.isBlank()) return ""
    return runCatching {
        JSONObject(Crypto.decrypt(profile.credentialsJson)).optString(key).takeUnless { it == "null" }.orEmpty()
    }.getOrDefault("")
}

internal fun encodeApiProfileExtraCredential(type: String, value: String): String {
    val key = when (type) {
        "BAIDU_API" -> "appId"
        "TENCENT" -> "secretId"
        "ALIYUN" -> "accessKeyId"
        else -> ""
    }
    if (key.isBlank() || value.isBlank()) return ""
    return Crypto.encrypt(JSONObject().put(key, value.trim()).toString())
}

private val officialTranslationHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

private val providerJsonMedia = "application/json; charset=utf-8".toMediaType()

private suspend fun executeProviderRequest(request: Request): String {
    val ownerJob = currentCoroutineContext()[Job]
    return withBoundedTranslationRetries(ownerJob) {
        io.translatetube.local.yt.executeCancellableHttpCall(
            officialTranslationHttp.newCall(request),
            ownerJob,
        ) { response -> readTranslationResponse(response, ownerJob) }
    }
}

private suspend fun postProviderJson(
    url: String,
    headers: Map<String, String>,
    payload: String,
): String {
    val builder = Request.Builder().url(url).post(payload.toRequestBody(providerJsonMedia))
    headers.forEach { (name, value) -> builder.header(name, value) }
    return executeProviderRequest(builder.build())
}

private fun standardProviderLanguage(code: String): String {
    if (SourceLanguagePolicy.isUndetermined(code)) return "auto"
    return SourceLanguagePolicy.normalizeTag(code).substringBefore('-').lowercase(Locale.ROOT)
}

private fun deeplLanguage(code: String): String {
    val normalized = SourceLanguagePolicy.normalizeTag(code).replace('_', '-')
    val lower = normalized.lowercase(Locale.ROOT)
    return when {
        lower == "zh-tw" || lower == "zh-hk" || lower.startsWith("zh-hant") -> "ZH-HANT"
        lower == "zh" || lower == "zh-cn" || lower == "zh-sg" || lower.startsWith("zh-hans") -> "ZH"
        else -> normalized.uppercase(Locale.ROOT)
    }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

private fun hmacSha256(key: ByteArray, value: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
}

private fun hmacSha1(key: ByteArray, value: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
}

private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

/** DeepL REST API. API Free/Pro endpoint is configured by the saved Base URL. */
class DeepLTranslationEngine(
    private val apiKey: String,
    private val baseUrl: String,
) : TranslationEngine {
    override val id: String = "deepl"
    override val displayName: String = "DeepL API"

    private fun translateEndpoint(): String {
        val configured = baseUrl.trim().trimEnd('/')
            .ifBlank { "https://api-free.deepl.com" }
        return if (configured.endsWith("/v2/translate")) configured else "$configured/v2/translate"
    }

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("DeepL 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw IllegalStateException("DeepL API Key 为空")
        val output = MutableList(lines.size) { "" }
        val pending = lines.withIndex().filter { it.value.isNotBlank() }
        for (chunk in pending.chunked(50)) {
            currentCoroutineContext().ensureActive()
            val payload = JSONObject()
                .put("text", JSONArray().apply { chunk.forEach { put(it.value) } })
                .put("target_lang", deeplLanguage(tgtLang))
                .put("split_sentences", "nonewlines")
                .put("preserve_formatting", true)
            if (!SourceLanguagePolicy.isUndetermined(srcLang)) {
                payload.put("source_lang", deeplLanguage(srcLang))
            }
            val body = postProviderJson(
                translateEndpoint(),
                mapOf(
                    "Authorization" to "DeepL-Auth-Key ${apiKey.trim()}",
                    "Accept" to "application/json",
                ),
                payload.toString(),
            )
            val translations = JSONObject(body).optJSONArray("translations")
                ?: throw IllegalStateException("DeepL 响应缺少 translations 字段")
            if (translations.length() != chunk.size) {
                throw IllegalStateException("DeepL 返回数量不匹配：请求 ${chunk.size} 条，返回 ${translations.length()} 条")
            }
            chunk.forEachIndexed { index, item ->
                output[item.index] = translations.optJSONObject(index)?.optString("text").orEmpty().trim()
            }
        }
        return output
    }
}

/** Baidu Open Platform's official text translation API (APP ID + Secret Key). */
class BaiduOfficialTranslationEngine(
    private val appId: String,
    private val secretKey: String,
) : TranslationEngine {
    override val id: String = "baidu-official"
    override val displayName: String = "百度翻译官方 API"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("百度翻译 API 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (appId.isBlank()) throw IllegalStateException("百度翻译 APP ID 为空")
        if (secretKey.isBlank()) throw IllegalStateException("百度翻译 Secret Key 为空")
        val from = WebLangCodes.baidu(srcLang, target = false)
        val to = WebLangCodes.baidu(tgtLang, target = true)
        val output = MutableList(lines.size) { "" }
        for ((index, line) in lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) continue
            val parts = if (line.toByteArray(StandardCharsets.UTF_8).size <= 4800) {
                listOf(line)
            } else {
                line.chunked(1200)
            }
            val translatedParts = ArrayList<String>()
            for (part in parts) {
                val salt = UUID.randomUUID().toString().replace("-", "")
                val sign = md5Hex(appId + part + salt + secretKey)
                val form = FormBody.Builder()
                    .add("q", part)
                    .add("from", from)
                    .add("to", to)
                    .add("appid", appId)
                    .add("salt", salt)
                    .add("sign", sign)
                    .build()
                val request = Request.Builder()
                    .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
                    .post(form)
                    .header("Accept", "application/json")
                    .build()
                val body = executeProviderRequest(request)
                val root = JSONObject(body)
                val errorCode = root.optString("error_code")
                if (errorCode.isNotBlank() && errorCode != "null") {
                    val message = root.optString("error_msg").ifBlank { errorCode }
                    throw IllegalStateException("百度翻译 API 错误 $errorCode：$message")
                }
                val result = root.optJSONArray("trans_result")
                    ?: throw IllegalStateException("百度翻译 API 未返回 trans_result")
                val pieces = (0 until result.length()).mapNotNull { result.optJSONObject(it)?.optString("dst") }
                if (pieces.isEmpty()) throw IllegalStateException("百度翻译 API 未返回译文")
                translatedParts += pieces.joinToString(if (to in setOf("zh", "cht", "yue", "jp", "kor", "th")) "" else " ")
                delay(40L)
            }
            output[index] = translatedParts.joinToString(if (to in setOf("zh", "cht", "yue", "jp", "kor", "th")) "" else " ")
        }
        return output
    }
}

/** Microsoft Translator Text API v3.0 (paid/official subscription-key endpoint). */
class AzureTranslatorEngine(
    private val apiKey: String,
    private val region: String,
    private val baseUrl: String,
) : TranslationEngine {
    override val id: String = "microsoft-translator-api"
    override val displayName: String = "Microsoft Translator API"

    private fun translateEndpoint(): String {
        val configured = baseUrl.trim().trimEnd('/')
        if (configured.isBlank() || configured == "https://api.cognitive.microsofttranslator.com") {
            return "https://api.cognitive.microsofttranslator.com/translate"
        }
        if (configured.endsWith("/translate")) return configured
        return if (configured.contains("cognitiveservices.azure.com", ignoreCase = true)) {
            "$configured/translator/text/v3.0/translate"
        } else {
            "$configured/translate"
        }
    }

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("Microsoft Translator 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw IllegalStateException("Microsoft Translator 订阅密钥为空")
        val to = WebLangCodes.microsoft(tgtLang, target = true)
        val from = WebLangCodes.microsoft(srcLang, target = false)
        val output = MutableList(lines.size) { "" }
        val pending = lines.withIndex().filter { it.value.isNotBlank() }
        val regionValue = region.trim()
        for (chunk in pending.chunked(25)) {
            currentCoroutineContext().ensureActive()
            val url = translateEndpoint().toHttpUrl().newBuilder()
                .addQueryParameter("api-version", "3.0")
                .addQueryParameter("to", to)
                .apply { if (from.isNotBlank()) addQueryParameter("from", from) }
                .build()
            val payload = JSONArray().apply {
                chunk.forEach { put(JSONObject().put("Text", it.value)) }
            }.toString()
            val headers = linkedMapOf(
                "Ocp-Apim-Subscription-Key" to apiKey.trim(),
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
            )
            if (regionValue.isNotBlank() && !regionValue.equals("global", ignoreCase = true)) {
                headers["Ocp-Apim-Subscription-Region"] = regionValue
            }
            val response = postProviderJson(url.toString(), headers, payload)
            val translated = parseMicrosoftTranslations(response, chunk.size)
            chunk.forEachIndexed { pos, cue -> output[cue.index] = translated[pos] }
        }
        return output
    }
}

/** LibreTranslate-compatible REST API. API key is optional for self-hosted instances. */
class LibreTranslateEngine(
    private val baseUrl: String,
    private val apiKey: String,
) : TranslationEngine {
    override val id: String = "libretranslate"
    override val displayName: String = "LibreTranslate"

    private fun endpoint(): String {
        val configured = baseUrl.trim().trimEnd('/').ifBlank { "https://libretranslate.com" }
        return if (configured.endsWith("/translate")) configured else "$configured/translate"
    }

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("LibreTranslate 已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val output = MutableList(lines.size) { "" }
        val source = standardProviderLanguage(srcLang)
        val target = standardProviderLanguage(tgtLang)
        for ((index, line) in lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) continue
            val payload = JSONObject()
                .put("q", line)
                .put("source", source)
                .put("target", target)
                .put("format", "text")
            if (apiKey.isNotBlank()) payload.put("api_key", apiKey.trim())
            val body = postProviderJson(
                endpoint(),
                mapOf("Accept" to "application/json"),
                payload.toString(),
            )
            val root = JSONObject(body)
            val translated = root.optString("translatedText").trim()
            if (translated.isBlank()) {
                val message = root.optString("error").ifBlank { "服务端没有返回 translatedText" }
                throw IllegalStateException("LibreTranslate 翻译失败：$message")
            }
            output[index] = translated
        }
        return output
    }
}

/** Tencent Cloud TMT TextTranslate, authenticated with the official TC3-HMAC-SHA256 scheme. */
class TencentCloudTranslationEngine(
    private val secretId: String,
    private val secretKey: String,
    private val region: String,
) : TranslationEngine {
    override val id: String = "tencent-tmt"
    override val displayName: String = "腾讯云机器翻译"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("腾讯云机器翻译已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (secretId.isBlank()) throw IllegalStateException("腾讯云 SecretId 为空")
        if (secretKey.isBlank()) throw IllegalStateException("腾讯云 SecretKey 为空")
        if (region.isBlank()) throw IllegalStateException("腾讯云地域为空")
        val source = standardProviderLanguage(srcLang)
        val target = standardProviderLanguage(tgtLang)
        val output = MutableList(lines.size) { "" }
        for ((index, line) in lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) continue
            output[index] = translateLine(line, source, target)
            delay(50L)
        }
        return output
    }

    private suspend fun translateLine(text: String, source: String, target: String): String {
        val host = "tmt.tencentcloudapi.com"
        val contentType = "application/json; charset=utf-8"
        val timestamp = System.currentTimeMillis() / 1000L
        val date = java.time.Instant.ofEpochSecond(timestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val payload = JSONObject()
            .put("SourceText", text)
            .put("Source", source)
            .put("Target", target)
            .put("ProjectId", 0)
            .toString()
        val signedHeaders = "content-type;host"
        val canonicalHeaders = "content-type:$contentType\nhost:$host\n"
        val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n${sha256Hex(payload)}"
        val credentialScope = "$date/tmt/tc3_request"
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n${sha256Hex(canonicalRequest)}"
        val secretDate = hmacSha256(("TC3" + secretKey).toByteArray(StandardCharsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, "tmt")
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256(secretSigning, stringToSign)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val authorization = "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
        val response = postProviderJson(
            "https://$host/",
            mapOf(
                "Content-Type" to contentType,
                "Host" to host,
                "X-TC-Action" to "TextTranslate",
                "X-TC-Version" to "2018-03-21",
                "X-TC-Region" to region.trim(),
                "X-TC-Timestamp" to timestamp.toString(),
                "Authorization" to authorization,
            ),
            payload,
        )
        val root = JSONObject(response)
        val result = root.optJSONObject("Response") ?: root
        val error = result.optJSONObject("Error")
        if (error != null) {
            throw IllegalStateException("腾讯云翻译错误 ${error.optString("Code")}：${error.optString("Message")}")
        }
        val translated = result.optString("TargetText").trim()
        if (translated.isBlank()) throw IllegalStateException("腾讯云机器翻译响应缺少 TargetText")
        return translated
    }
}

/** Alibaba Cloud Machine Translation Universal Edition, signed with HMAC-SHA1/ROA headers. */
class AliyunMachineTranslationEngine(
    private val accessKeyId: String,
    private val accessKeySecret: String,
) : TranslationEngine {
    override val id: String = "aliyun-machine-translation"
    override val displayName: String = "阿里云机器翻译"

    override suspend fun testConnection() {
        val result = translate(listOf("Hello"), "en", "zh")
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("阿里云机器翻译已响应，但没有返回有效译文")
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (accessKeyId.isBlank()) throw IllegalStateException("阿里云 AccessKey ID 为空")
        if (accessKeySecret.isBlank()) throw IllegalStateException("阿里云 AccessKey Secret 为空")
        val source = standardProviderLanguage(srcLang)
        val target = standardProviderLanguage(tgtLang)
        val output = MutableList(lines.size) { "" }
        for ((index, line) in lines.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) continue
            output[index] = translateLine(line, source, target)
            delay(50L)
        }
        return output
    }

    private suspend fun translateLine(text: String, source: String, target: String): String {
        val host = "mt.cn-hangzhou.aliyuncs.com"
        val path = "/api/translate/web/general"
        val contentType = "application/json;charset=utf-8"
        val payload = JSONObject()
            .put("FormatType", "text")
            .put("SourceLanguage", source)
            .put("TargetLanguage", target)
            .put("SourceText", text)
            .put("Scene", "general")
            .toString()
        val md5 = Base64.encodeToString(
            MessageDigest.getInstance("MD5").digest(payload.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())
        val nonce = UUID.randomUUID().toString()
        val stringToSign = "POST\napplication/json\n$md5\n$contentType\n$date\nx-acs-signature-method:HMAC-SHA1\nx-acs-signature-nonce:$nonce\nx-acs-version:2019-01-02\n$path"
        val signature = Base64.encodeToString(
            hmacSha1(accessKeySecret.toByteArray(StandardCharsets.UTF_8), stringToSign),
            Base64.NO_WRAP,
        )
        val response = postProviderJson(
            "https://$host$path",
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to contentType,
                "Content-MD5" to md5,
                "Date" to date,
                "Host" to host,
                "x-acs-signature-method" to "HMAC-SHA1",
                "x-acs-signature-nonce" to nonce,
                "x-acs-version" to "2019-01-02",
                "Authorization" to "acs $accessKeyId:$signature",
            ),
            payload,
        )
        val root = JSONObject(response)
        val wrapper = root.optJSONObject("TranslateGeneralResponse") ?: root
        val code = wrapper.optString("Code")
        if (code.isNotBlank() && code != "200" && code != "null") {
            throw IllegalStateException("阿里云翻译错误 ${code}：${wrapper.optString("Message")}")
        }
        val data = wrapper.optJSONObject("Data")
            ?: throw IllegalStateException("阿里云翻译响应缺少 Data 字段：${wrapper.optString("Message")}")
        val translated = data.optString("Translated").trim()
        if (translated.isBlank()) throw IllegalStateException("阿里云机器翻译未返回译文")
        return translated
    }
}
