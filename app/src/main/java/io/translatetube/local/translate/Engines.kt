package io.translatetube.local.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.translatetube.local.subs.MlKitSourceLanguageIdentifier

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

/** 批间衔接时携带的上批尾部行数（原文+译文对照）。 */
private const val CONTEXT_LINES = 3
private const val SLOW_TRANSLATION_REQUEST_WARNING_MS = 8_000L

/**
 * 组装带上一批上下文的用户提示词。上下文仅供模型保持译名/指代连贯，
 * 明确要求不要翻译或输出这些行；无上下文时返回 null 由调用方退回纯编号列表。
 */
internal fun buildContextUserPrompt(
    context: Pair<List<String>, List<String>>?,
    numbered: String,
): String? {
    val (srcCtx, tgtCtx) = context ?: return null
    if (srcCtx.isEmpty()) return null
    val ctxText = srcCtx.mapIndexed { j, l ->
        val t = tgtCtx.getOrNull(j).orEmpty()
        "$l => $t"
    }.joinToString("\n")
    return "Previous cues for continuity (context only — do not translate or output these):\n" +
        "$ctxText\n\nNow translate:\n$numbered"
}

/**
 * Recognize only parser/alignment failures that can improve by lowering the LLM request size.
 * Authentication, quota, transport and server errors must keep their normal error path.
 */
internal fun isRecoverableTranslationFormatError(error: IllegalStateException): Boolean {
    val message = error.message.orEmpty()
    val normalized = message.lowercase(java.util.Locale.ROOT)
    val malformedTranslation = message.contains("重复返回字幕编号") ||
        message.contains("超出本批范围的字幕编号") ||
        message.contains("编号不完整") ||
        message.contains("格式不完整")
    if (malformedTranslation) return true

    // A batch that exceeds context/request limits may succeed when split. Do not include generic
    // token/max-token errors, authentication failures, rate limits, or temporary server errors.
    val requestTooLarge = normalized.contains("http 413") ||
        listOf(
            "context length exceeded",
            "maximum context length",
            "context_length_exceeded",
            "too many tokens",
            "maximum number of tokens",
            "input is too long",
            "prompt is too long",
            "request too large",
            "request entity too large",
            "payload too large",
            "request body too large",
            "maximum context window",
        ).any(normalized::contains)
    return requestTooLarge
}

/**
 * Retry malformed or truncated model output with smaller cue groups. Successfully translated
 * halves feed their last few cues into the next half so splitting does not discard continuity.
 */
internal suspend fun translateBatchWithAdaptiveRecovery(
    lines: List<String>,
    context: Pair<List<String>, List<String>>? = null,
    isAcceptableTranslation: (source: String, translation: String) -> Boolean = { _, translation ->
        translation.isNotBlank()
    },
    translate: suspend (List<String>, Pair<List<String>, List<String>>?) -> List<String>,
): List<String> {
    if (lines.isEmpty()) return emptyList()

    val response = try {
        translate(lines, context)
    } catch (error: IllegalStateException) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!isRecoverableTranslationFormatError(error)) throw error
        null
    }

    if (response != null && response.size == lines.size) {
        // Keep valid results and retry only blank, source-echo, or otherwise rejected rows.
        val output = response.mapIndexed { index, value ->
            val candidate = value.trim()
            if (lines[index].isBlank() || !isAcceptableTranslation(lines[index], candidate)) {
                ""
            } else {
                candidate
            }
        }.toMutableList()
        val complete = lines.indices.all { index ->
            lines[index].isBlank() || output[index].isNotBlank()
        }
        if (complete) return output

        val hasUsableResult = lines.indices.any { index ->
            lines[index].isNotBlank() && output[index].isNotBlank()
        }
        if (hasUsableResult) {
            var cursor = 0
            while (cursor < lines.size) {
                if (lines[cursor].isBlank() || output[cursor].isNotBlank()) {
                    cursor++
                    continue
                }

                val missingStart = cursor
                while (cursor < lines.size &&
                    lines[cursor].isNotBlank() &&
                    output[cursor].isBlank()
                ) {
                    cursor++
                }
                val priorCues = mutableListOf<Pair<String, String>>()
                val priorSource = context?.first.orEmpty()
                val priorTarget = context?.second.orEmpty()
                for (index in 0 until minOf(priorSource.size, priorTarget.size)) {
                    if (priorSource[index].isNotBlank() && priorTarget[index].isNotBlank()) {
                        priorCues += priorSource[index] to priorTarget[index]
                    }
                }
                for (index in 0 until missingStart) {
                    if (lines[index].isNotBlank() && output[index].isNotBlank()) {
                        priorCues += lines[index] to output[index]
                    }
                }
                val recentCues = priorCues.takeLast(CONTEXT_LINES)
                val retryContext = if (recentCues.isEmpty()) null else {
                    recentCues.map { it.first } to recentCues.map { it.second }
                }
                val retry = translateBatchWithAdaptiveRecovery(
                    lines.subList(missingStart, cursor),
                    retryContext,
                    isAcceptableTranslation,
                    translate,
                )
                retry.forEachIndexed { offset, value ->
                    output[missingStart + offset] = value
                }
            }
            return output
        }
    }

    // A malformed or wholly missing batch cannot be mapped safely to individual input rows.
    // Split it in half so every retry makes progress and never shifts one cue onto another.
    if (lines.size == 1) {
        // Even an isolated cue gets one retry: source-echo validation happens inside this helper,
        // so otherwise a single echoed cue would fail before the caller can recover it.
        val retry = try {
            translate(lines, context)
        } catch (error: IllegalStateException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isRecoverableTranslationFormatError(error)) throw error
            null
        }
        val candidate = retry?.singleOrNull()?.trim()
        if (lines.single().isBlank()) return listOf("")
        if (!candidate.isNullOrBlank() &&
            isAcceptableTranslation(lines.single(), candidate)
        ) {
            return listOf(candidate)
        }
        throw IllegalStateException("翻译服务重试后仍未能完整返回单条字幕的有效译文，请重试或更换翻译模型")
    }

    val midpoint = lines.size / 2
    val leftSource = lines.subList(0, midpoint)
    val rightSource = lines.subList(midpoint, lines.size)
    val leftTranslation = translateBatchWithAdaptiveRecovery(
        leftSource, context, isAcceptableTranslation, translate,
    )
    val mergedSource = (context?.first.orEmpty() + leftSource).takeLast(CONTEXT_LINES)
    val mergedTarget = (context?.second.orEmpty() + leftTranslation).takeLast(CONTEXT_LINES)
    val rightContext = if (mergedSource.isEmpty()) null else mergedSource to mergedTarget
    val rightTranslation = translateBatchWithAdaptiveRecovery(
        rightSource, rightContext, isAcceptableTranslation, translate,
    )
    return leftTranslation + rightTranslation
}

private val JSON = "application/json; charset=utf-8".toMediaType()
private val translationHeaderRegex = Regex(
    "(?i)^(here are (the )?translations?:?|translations?:?|translation results?:?|译文如下[:：]?)$",
)

/**
 * Be tolerant of common model formatting variations while preserving subtitle row alignment.
 * Providers sometimes ignore the requested index|text format and return numbered Markdown or
 * plain one-translation-per-line output; treating that as an all-empty batch caused a false
 * "status placeholder" error even though the model returned translations.
 */
internal fun parseModelTranslations(content: String, size: Int): List<String> {
    if (size <= 0) return emptyList()

    val normalized = content
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("(?m)^\\s*```[\\w-]*\\s*$"), "")
        .trim()
    if (normalized.isEmpty()) return List(size) { "" }

    // Accept JSON arrays when a model disregards the requested text format. Keep every
    // org.json call inside runCatching because Android's local-test stub may throw from length().
    val jsonTranslations = runCatching {
        JSONArray(normalized).takeIf { it.length() == size }?.let { array ->
            List(size) { i -> array.optString(i).trim() }
        }
    }.getOrNull()
    if (jsonTranslations != null) return jsonTranslations

    // Accepted examples: 0|text, 1 | text, 1. text, 1) text, 1：text, and Markdown bullets.
    val indexedLine = Regex("""^\s*(?:[-*•]\s*)?(?:\*\*)?(\d{1,4})(?:\*\*)?\s*(?:[|｜:：.、)]\s*|-\s+)(.*?)\s*$""")
    val indexed = linkedMapOf<Int, String>()
    var duplicateIndex = false
    normalized.lines().forEach { raw ->
        val line = raw.trim().trim('`').trim()
        val match = indexedLine.matchEntire(line) ?: return@forEach
        val index = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (index in 0..size) {
            val value = match.groupValues[2].trim().removePrefix("**").removeSuffix("**").trim()
            if (indexed.containsKey(index)) {
                duplicateIndex = true
            } else {
                // Empty numbered rows are valid for empty subtitle cues; keep their index.
                indexed[index] = value
            }
        }
    }
    if (indexed.isNotEmpty()) {
        if (duplicateIndex) {
            throw IllegalStateException("翻译服务重复返回字幕编号，已拒绝错位译文")
        }
        // If numbering begins at 1 and no zero row exists, accept the common one-based format.
        val base = if (0 !in indexed.keys && indexed.keys.minOrNull() == 1) 1 else 0
        if (base == 0 && size in indexed.keys) {
            throw IllegalStateException("翻译服务返回了超出本批范围的字幕编号")
        }
        if ((0 until size).any { (it + base) !in indexed.keys }) {
            throw IllegalStateException("翻译服务返回的字幕编号不完整，已拒绝可能错位的译文")
        }
        return List(size) { i -> indexed.getValue(i + base) }
    }

    // Fall back to positional output only when each input row has exactly one output row.
    val plainLines = normalized.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").trim().removeSurrounding("**").trim() }
        .filter { it.isNotBlank() }
        // The response can contain many rows; share this pattern instead of compiling it per row.
        .filterNot { translationHeaderRegex.matches(it) }
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
internal fun safeDiagnosticUrl(url: okhttp3.HttpUrl): String {
    val builder = url.newBuilder()
    url.queryParameterNames
        .filter(::isSensitiveDiagnosticParameter)
        .forEach { name -> builder.setQueryParameter(name, "[redacted]") }
    return builder.build().toString()
}

/** Query-parameter aliases vary between providers; redact credentials before showing request routes. */
private fun isSensitiveDiagnosticParameter(name: String): Boolean {
    val normalized = name.trim().lowercase(java.util.Locale.ROOT).replace('-', '_')
    return normalized in setOf(
        "key", "api_key", "apikey", "token", "access_token", "auth", "authorization",
        "password", "secret", "client_secret", "signature", "sig",
    ) || normalized.endsWith("_key") ||
        normalized.endsWith("_token") ||
        normalized.endsWith("_secret") ||
        normalized.endsWith("_password")
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

/** Only retry transient transport failures and temporary provider throttling/server errors. */
private const val MAX_TRANSLATION_REQUEST_RETRIES = 2
private val retryableTranslationHttpStatusRegex =
    Regex("""\bHTTP\s+(408|425|429|500|502|503|504)\b""", RegexOption.IGNORE_CASE)

internal fun isRetryableTranslationFailure(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    // Cancellation must never be converted into another network attempt.
    if (chain.any { it is CancellationException }) return false
    if (chain.any { it is IOException }) return true
    return chain.any { retryableTranslationHttpStatusRegex.containsMatchIn(it.message.orEmpty()) }
}

/**
 * Retry a small, fixed number of times on temporary network/provider failures. The backoff is
 * cancellable and permanent failures such as invalid API keys are surfaced immediately.
 */
internal suspend fun <T> withBoundedTranslationRetries(
    ownerJob: Job?,
    request: () -> T,
): T {
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        ownerJob?.ensureActive()
        try {
            return request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (retries >= MAX_TRANSLATION_REQUEST_RETRIES ||
                !isRetryableTranslationFailure(error)
            ) {
                throw error
            }
            retries += 1
            kotlinx.coroutines.delay(if (retries == 1) 400L else 900L)
        }
    }
}

private const val MAX_TRANSLATION_RESPONSE_BYTES = 16L * 1024L * 1024L

internal fun readTranslationResponse(
    response: okhttp3.Response,
    ownerJob: Job?,
): String {
    val body = response.body ?: throw IllegalStateException("空响应")
    if (body.contentLength() > MAX_TRANSLATION_RESPONSE_BYTES) {
        throw IllegalStateException("翻译服务响应超过 16 MB 安全上限，已停止读取以保护设备内存")
    }
    val bytes = body.byteStream().use { input ->
        io.translatetube.local.yt.BoundedTextPayload.readBytes(
            input,
            maxBytes = MAX_TRANSLATION_RESPONSE_BYTES,
            resourceLabel = "翻译服务响应",
            checkActive = { ownerJob?.ensureActive() },
        )
    }
    val text = String(bytes, Charsets.UTF_8)
    if (!response.isSuccessful) {
        throw IllegalStateException("HTTP ${response.code} [${responseRoute(response)}]: ${text.take(500)}")
    }
    return text
}

private suspend fun post(
    url: String,
    headers: Map<String, String>,
    body: String,
    ownerJob: Job?,
): String {
    val b = Request.Builder().url(url).post(body.toRequestBody(JSON))
    headers.forEach { (k, v) -> b.header(k, v) }
    val request = b.build()
    return withBoundedTranslationRetries(ownerJob) {
        io.translatetube.local.yt.executeCancellableHttpCall(
            http.newCall(request), ownerJob,
        ) { response -> readTranslationResponse(response, ownerJob) }
    }
}

private suspend fun get(
    url: String,
    headers: Map<String, String>,
    ownerJob: Job?,
): String {
    val b = Request.Builder().url(url).get()
    headers.forEach { (k, v) -> b.header(k, v) }
    val request = b.build()
    return withBoundedTranslationRetries(ownerJob) {
        io.translatetube.local.yt.executeCancellableHttpCall(
            http.newCall(request), ownerJob,
        ) { response -> readTranslationResponse(response, ownerJob) }
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
            throw IllegalStateException("离线翻译包 $languageTag 尚未下载，请到 API 管理 → 本地翻译下载对应语言包，或切换其他翻译引擎。")
        }
    }

    suspend fun isDownloaded(languageTag: String): Boolean {
        val language = TranslateLanguage.fromLanguageTag(languageTag) ?: return false
        val model = TranslateRemoteModel.Builder(language).build()
        return RemoteModelManager.getInstance().isModelDownloaded(model).await()
    }

    suspend fun deleteLanguage(languageTag: String) {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalStateException("不支持的离线翻译语言: $languageTag")
        val model = TranslateRemoteModel.Builder(language).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
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
    // Preserve the original engine ID because legacy playback paths use "local" for offline translation.
    override val id = "local"
    override val displayName = "ML Kit 本地翻译"

    private var activePair: String? = null
    private var activeTranslator: Translator? = null

    private suspend fun ensureModel(lang: String) {
        MlKitModelCoordinator.ensureDownloaded(lang)
    }

    /** Check source support and both downloaded packs before translating subtitle batches. */
    suspend fun validateConfiguration(
        srcLang: String,
        tgtLang: String,
        sampleLines: List<String> = emptyList(),
    ): String {
        val resolvedSource = if (SourceLanguagePolicy.isUndetermined(srcLang)) {
            MlKitSourceLanguageIdentifier.detect(sampleLines)
        } else srcLang
        if (SourceLanguagePolicy.isUndetermined(resolvedSource)) {
            throw IllegalStateException("无法可靠识别字幕源语言。请在“API 管理 → 本地翻译”中手动指定源语言后重试。")
        }
        if (!LangMap.isMlkitSupported(resolvedSource)) {
            throw IllegalStateException("ML Kit 本地翻译暂不支持源语言“$resolvedSource”。请选择已支持的语言或切换其他引擎。")
        }
        val source = LangMap.mlkitSource(resolvedSource)
        val target = LangMap.mlkit(tgtLang)
        if (!LangMap.isMlkitSupported(target)) {
            throw IllegalStateException("ML Kit 本地翻译暂不支持目标语言“$tgtLang”。")
        }
        ensureModel(source)
        ensureModel(target)
        return source
    }

    /** Reuse one translator over the playback service's 10-cue batches. */
    private suspend fun translatorFor(source: String, target: String): Translator {
        val pair = "$source->$target"
        synchronized(this) {
            if (activePair == pair) activeTranslator?.let { return it }
        }
        ensureModel(source)
        ensureModel(target)
        val created = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        )
        return synchronized(this) {
            val current = if (activePair == pair) activeTranslator else null
            if (current != null) {
                created.close()
                current
            } else {
                activeTranslator?.close()
                activeTranslator = created
                activePair = pair
                created
            }
        }
    }

    override fun close() {
        synchronized(this) {
            activeTranslator?.close()
            activeTranslator = null
            activePair = null
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val source = validateConfiguration(srcLang, tgtLang, lines)
        val target = LangMap.mlkit(tgtLang)
        val translator = translatorFor(source, target)
        return lines.map { if (it.isBlank()) "" else translator.translate(it).await() }
    }
}

/**
 * Signals that a response contains reasoning/thinking fields but no final answer text, allowing
 * the caller to retry using another provider-specific thinking-control parameter.
 */
private class ReasoningOnlyResponseException(
    val messageFields: List<String>,
    val reasoningFields: List<String>,
    val finishReason: String,
) : IllegalStateException(
    "模型只返回了推理字段 ${reasoningFields.joinToString(",")}，没有最终翻译 content；" +
        "message 字段=${messageFields.joinToString(",")}；finish_reason=$finishReason"
)

internal enum class ThinkingControlStyle {
    DEEPSEEK,
    QWEN,
    OPENROUTER,
    REASONING_EFFORT,
    PLAIN,
}

/**
 * Select at most two request formats. Retrying every vendor's speculative controls can multiply
 * latency and token charges for every subtitle batch; use one provider/model-specific format and
 * one fallback only if the response shows reasoning without final text or the parameter is rejected.
 */
internal fun fallbackAfterThinkingControlRejection(
    current: ThinkingControlStyle,
    configuredFallback: ThinkingControlStyle,
): ThinkingControlStyle =
    if (current == ThinkingControlStyle.PLAIN) configuredFallback else ThinkingControlStyle.PLAIN

internal fun thinkingControlOrder(baseUrl: String, model: String): List<ThinkingControlStyle> {
    val normalizedBase = baseUrl.lowercase(java.util.Locale.ROOT)
    val normalizedModel = model.lowercase(java.util.Locale.ROOT)

    // Thinking-only families cannot disable reasoning. Do not spend another paid request trying
    // unsupported flags; ask for the final answer through their normal request shape.
    val thinkingOnlyModel =
        normalizedModel.contains("qwq") ||
            normalizedModel.contains("deepseek-r1") ||
            normalizedModel.contains("deepseek-reasoner") ||
            Regex("""(^|[-_/])thinking($|[-_/])""").containsMatchIn(normalizedModel)
    if (thinkingOnlyModel) return listOf(ThinkingControlStyle.PLAIN)

    val preferred = when {
        normalizedBase.contains("openrouter.ai") -> ThinkingControlStyle.OPENROUTER
        normalizedBase.contains("api.deepseek.com") || normalizedModel.contains("deepseek") ->
            ThinkingControlStyle.DEEPSEEK
        normalizedBase.contains("dashscope") ||
            normalizedBase.contains("qwencloud") ||
            normalizedModel.contains("qwen") ->
            ThinkingControlStyle.QWEN
        normalizedModel.contains("kimi") ||
            normalizedModel.contains("glm") ||
            normalizedModel.contains("zhipu") ||
            normalizedModel.contains("reasoner") ||
            normalizedModel.contains("thinking") ||
            normalizedModel.contains("qwq") ||
            Regex("""(^|[-_/])(r1|r2|o1|o3|o4)([-_/]|$)""").containsMatchIn(normalizedModel) ||
            normalizedModel.contains("gpt-5") || normalizedModel.contains("gpt5") ->
            ThinkingControlStyle.REASONING_EFFORT
        else -> ThinkingControlStyle.PLAIN
    }

    val fallback = when (preferred) {
        ThinkingControlStyle.DEEPSEEK,
        ThinkingControlStyle.QWEN,
        ThinkingControlStyle.OPENROUTER,
        ThinkingControlStyle.PLAIN -> ThinkingControlStyle.REASONING_EFFORT
        ThinkingControlStyle.REASONING_EFFORT -> ThinkingControlStyle.PLAIN
    }
    return listOf(preferred, fallback).distinct()
}


    DEEPSEEK,
    QWEN,
    OPENROUTER,
    REASONING_EFFORT,
    PLAIN,
}

/**
 * Detect provider errors caused by a thinking-control parameter that the provider/model does not
 * support. Other HTTP errors must not trigger a series of repeated translation requests.
 */
internal fun isThinkingControlRejected(error: Throwable): Boolean {
    val detail = generateSequence(error) { it.cause }
        .take(8)
        .joinToString(" ") { it.message.orEmpty() }
        .lowercase(java.util.Locale.ROOT)
    val parameterName = listOf(
        "thinking", "enable_thinking", "reasoning", "reasoning_effort", "effort",
    ).any { detail.contains(it) }
    val rejected = listOf(
        "unknown parameter", "unsupported parameter", "unrecognized parameter",
        "unknown field", "unsupported field", "unrecognized field",
        "not supported", "does not support", "not allowed", "invalid parameter",
        "unexpected keyword", "unknown key", "extra inputs are not permitted",
        "additional properties are not allowed",
    ).any { detail.contains(it) }
    return rejected && (parameterName || detail.contains("extra inputs") ||
        detail.contains("additional properties"))
}

internal fun isReasoningFieldName(name: String): Boolean {
    val normalized = name.trim().lowercase(java.util.Locale.ROOT)
    return listOf("reason", "think", "analysis", "thought").any(normalized::contains)
}

/** Read text from string, array-of-content-blocks, or object-style provider content. */
private fun extractTextFromContentValue(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> {
        val parts = mutableListOf<String>()
        for (index in 0 until value.length()) {
            val item = value.opt(index)
            if (item is JSONObject) {
                val type = item.optString("type", "").lowercase(java.util.Locale.ROOT)
                if (type.isNotBlank() && isReasoningFieldName(type)) continue
            }
            val text = extractTextFromContentValue(item)
            if (text.isNotBlank()) parts += text
        }
        parts.joinToString("\n")
    }
    is JSONObject -> {
        val type = value.optString("type", "").lowercase(java.util.Locale.ROOT)
        if (type.isNotBlank() && isReasoningFieldName(type)) {
            ""
        } else {
            val directText = listOf("text", "output_text", "final_answer", "answer")
                .firstNotNullOfOrNull { key ->
                    (value.opt(key) as? String)?.takeIf { it.isNotBlank() }
                }
            if (directText != null) directText else {
                val parts = value.opt("parts")
                if (parts is JSONArray) extractTextFromContentValue(parts) else ""
            }
        }
    }
    else -> ""
}

/** Read common final-text variants used by Chat Completions and Responses-style gateways. */
private fun extractAlternativeResponseText(root: JSONObject): String {
    val topLevel = (root.opt("output_text") as? String).orEmpty().trim()
    if (topLevel.isNotEmpty()) return topLevel

    val output = root.optJSONArray("output") ?: return ""
    val parts = mutableListOf<String>()
    for (index in 0 until output.length()) {
        val item = output.optJSONObject(index) ?: continue
        val itemType = item.optString("type", "").lowercase(java.util.Locale.ROOT)
        if (itemType.isNotBlank() && isReasoningFieldName(itemType)) continue
        val content = item.optJSONArray("content")
        if (content != null) {
            val text = extractTextFromContentValue(content)
            if (text.isNotBlank()) parts += text
        } else {
            val text = (item.opt("text") as? String).orEmpty()
            if (text.isNotBlank()) parts += text
        }
    }
    return parts.joinToString("\n").trim()
}

/** Extract text from a non-streaming OpenAI-compatible chat-completion response. */
private fun extractOpenAiCompatContent(body: String): String {
    val root = try {
        JSONObject(body)
    } catch (error: Exception) {
        throw IllegalStateException("API 响应不是有效的 JSON，无法读取翻译结果", error)
    }

    when (val rawError = root.opt("error")) {
        is JSONObject -> {
            val message = (rawError.opt("message") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: "服务商未提供错误详情"
            val code = (rawError.opt("code") as? String)
                ?.takeIf { it.isNotBlank() && it != "null" }
            throw IllegalStateException(
                "API 返回错误" + (code?.let { " [$it]" } ?: "") + "：" + message.take(400)
            )
        }
        is String -> if (rawError.isNotBlank()) {
            throw IllegalStateException("API 返回错误：" + rawError.take(400))
        }
    }

    val alternativeResponseText = extractAlternativeResponseText(root)
    if (alternativeResponseText.isNotBlank()) return alternativeResponseText

    val choices = root.optJSONArray("choices")
        ?: throw IllegalStateException(
            "API 响应格式不兼容：既没有 choices，也没有可识别的 output_text/output 内容。" +
                "object=${root.optString("object", "未知")}；请检查中转站接口类型。"
        )
    if (choices.length() == 0) {
        throw IllegalStateException("API 返回了空 choices，未生成翻译结果")
    }

    val choice = choices.optJSONObject(0)
        ?: throw IllegalStateException("API 响应格式异常：choices[0] 不是 JSON 对象")
    val message = choice.optJSONObject("message")
        ?: (choice.opt("text") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        ?: throw IllegalStateException(
            "API 响应格式不兼容：choices[0] 缺少 message/text 字段（finish_reason=" +
                choice.optString("finish_reason", "未知") + "）"
        )
    val finishReason = choice.optString("finish_reason", "未知")
    val rawContent = message.opt("content")

    val text = extractTextFromContentValue(rawContent).trim()

    if (text.isNotEmpty()) return text

    val alternateMessageText = listOf(
        "output_text", "final_answer", "final", "answer", "translated_text", "text",
    ).firstNotNullOfOrNull { field ->
        (message.opt(field) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (!alternateMessageText.isNullOrBlank()) return alternateMessageText

    val messageFields = mutableListOf<String>()
    val keys = message.keys()
    while (keys.hasNext()) messageFields += keys.next()
    val reasoningFields = messageFields.filter { field ->
        isReasoningFieldName(field) && when (val value = message.opt(field)) {
            is String -> value.isNotBlank()
            is JSONObject -> value.length() > 0
            is JSONArray -> value.length() > 0
            else -> false
        }
    }
    val hasToolCalls = (message.optJSONArray("tool_calls")?.length() ?: 0) > 0
    if (reasoningFields.isNotEmpty()) {
        throw ReasoningOnlyResponseException(messageFields.sorted(), reasoningFields, finishReason)
    }
    val refusal = (message.opt("refusal") as? String).orEmpty().takeIf { it.isNotBlank() }
    val detail = when {
        hasToolCalls ->
            "模型返回了 tool_calls 工具调用，而不是字幕翻译文本"
        refusal != null ->
            "模型拒绝了本次请求：${refusal.take(240)}"
        message.has("content") ->
            "message.content 为空或不是可识别的文本"
        else ->
            "message 缺少 content 字段"
    }
    throw IllegalStateException(
        "API 未返回可用的翻译文本：$detail；message 字段=${messageFields.sorted().joinToString(",")}；" +
            "finish_reason=$finishReason。请检查中转站响应格式和模型输出设置。"
    )
}

/** OpenAI 兼容引擎（OpenAI / DeepSeek / 智谱 / Kimi / Ollama / 任意兼容中转） */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TranslationEngine {
    override val id = "openai"
    override val displayName = model.ifEmpty { "OpenAI 兼容" }

    private fun payloadWithThinkingControl(
        original: JSONObject,
        style: ThinkingControlStyle,
    ): JSONObject {
        val payload = JSONObject(original.toString())
        payload.remove("thinking")
        payload.remove("enable_thinking")
        payload.remove("reasoning")
        payload.remove("reasoning_effort")
        when (style) {
            ThinkingControlStyle.DEEPSEEK ->
                payload.put("thinking", JSONObject().put("type", "disabled"))
            ThinkingControlStyle.QWEN ->
                payload.put("enable_thinking", false)
            ThinkingControlStyle.OPENROUTER ->
                payload.put("reasoning", JSONObject().put("enabled", false))
            ThinkingControlStyle.REASONING_EFFORT ->
                payload.put("reasoning_effort", "none")
            ThinkingControlStyle.PLAIN -> Unit
        }
        return payload
    }

    private suspend fun requestTranslationContent(payload: JSONObject): String {
        var lastReasoningOnly: ReasoningOnlyResponseException? = null
        var lastRejectedControl: Throwable? = null
        val ownerJob = currentCoroutineContext()[Job]

        val styles = thinkingControlOrder(baseUrl, model).toMutableList()
        for (style in styles) {
            currentCoroutineContext().ensureActive()
            val requestPayload = payloadWithThinkingControl(payload, style)
            val startedAtNanos = System.nanoTime()
            try {
                val responseBody = post(
                    endpoint("/chat/completions"),
                    mapOf("Authorization" to "Bearer $apiKey"),
                    requestPayload.toString(),
                    ownerJob,
                )
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                if (elapsedMs >= SLOW_TRANSLATION_REQUEST_WARNING_MS) {
                    android.util.Log.w(
                        "TranslationAPI",
                        "Slow model response: model=${model.take(100)}, thinkingControl=$style, elapsedMs=$elapsedMs",
                    )
                }
                val content = try {
                    extractOpenAiCompatContent(responseBody)
                } catch (reasoningOnly: ReasoningOnlyResponseException) {
                    lastReasoningOnly = reasoningOnly
                    android.util.Log.w(
                        "TranslationAPI",
                        "Response had reasoning but no final text: model=${model.take(100)}, " +
                            "thinkingControl=$style, elapsedMs=$elapsedMs",
                    )
                    continue
                }
                return content
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                if (style != ThinkingControlStyle.PLAIN && isThinkingControlRejected(error)) {
                    lastRejectedControl = error
                    val styleIndex = styles.indexOf(style)
                    if (styleIndex == 0 && styles.size > 1) {
                        styles[1] = fallbackAfterThinkingControlRejection(style, styles[1])
                    }
                    android.util.Log.w(
                        "TranslationAPI",
                        "Provider rejected thinking control: model=${model.take(100)}, " +
                            "thinkingControl=$style, elapsedMs=$elapsedMs",
                    )
                } else {
                    if (elapsedMs >= SLOW_TRANSLATION_REQUEST_WARNING_MS) {
                        android.util.Log.w(
                            "TranslationAPI",
                            "Translation request failed after ${elapsedMs}ms: " +
                                "model=${model.take(100)}, thinkingControl=$style, " +
                                "errorType=${error.javaClass.simpleName}",
                        )
                    }
                    throw error
                }
            }
        }

        throw IllegalStateException(
            "API 返回了思考内容但没有最终翻译文本，或服务商不接受常见的关闭思考模式参数。" +
                "已尝试多种兼容格式仍未取得译文；该模型可能不支持关闭思考模式，或中转站未透传相关参数。" +
                "请检查中转站的模型能力和思考模式设置。",
            lastReasoningOnly ?: lastRejectedControl,
        )
    }

    private fun endpoint(path: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/v1")) b + path else "$b/v1$path"
    }

    override suspend fun listModels(): List<String> {
        val body = get(endpoint("/models"), mapOf("Authorization" to "Bearer $apiKey"), currentCoroutineContext()[Job])
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.sorted()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> =
        translateWithContext(lines, srcLang, tgtLang, null)

    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        initialContext: Pair<List<String>, List<String>>?,
    ): List<String> {
        val out = mutableListOf<String>()
        var context = initialContext
        var i = 0
        while (i < lines.size) {
            val batch = lines.subList(i, (i + 30).coerceAtMost(lines.size))
            val translated = translateBatchWithAdaptiveRecovery(
                lines = batch,
                context = context,
                isAcceptableTranslation = { original, candidate ->
                    !SubtitleTranslationQualityPolicy.isSourceEcho(original, candidate, srcLang)
                },
            ) { rows, cueContext ->
                translateBatch(rows, srcLang, tgtLang, cueContext)
            }
            out += translated
            val from = (batch.size - CONTEXT_LINES).coerceAtLeast(0)
            context = batch.subList(from, batch.size) to translated.subList(from, translated.size)
            i += 30
        }
        return out
    }

    private suspend fun translateBatch(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>? = null,
    ): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API Key 为空，请在 API 管理中配置私有密钥")
        }
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = buildString {
            append("You are a professional subtitle translator. These numbered lines are consecutive subtitle cues from one video. ")
            append("Translate each numbered line from $src to $tgt. ")
            append("Reply with the exact same `index|translation` format, one per line, nothing else. ")
            append("Include every input index exactly once; never return progress/status text. If unsure, keep the original line after the separator. ")
            append("Translate as coherent dialogue: keep pronouns, references and tone consistent across lines. ")
            append("Render the same person, place or recurring term identically every time it appears. ")
            append("Prefer natural spoken $tgt that fits a subtitle's length; avoid stiff word-for-word translation. ")
            append("Preserve meaning, tone and speaker style. Keep empty or already-$tgt lines unchanged.")
        }
        val user = buildContextUserPrompt(context, numbered)
            ?: numbered
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 2048)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val content = requestTranslationContent(payload)
        return parseNumbered(content, lines.size)
    }

    private fun parseNumbered(content: String, size: Int): List<String> =
        parseModelTranslations(content, size)
}

/** Retry retired Gemini model IDs with the current stable Flash model, but never hide auth/network errors. */
internal fun shouldFallbackToCurrentGeminiModel(configuredModel: String, error: Throwable): Boolean {
    if (configuredModel.trim().removePrefix("models/") == FreeModelDefaults.GEMINI_MODEL) return false
    val detail = generateSequence(error) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
    val isNotFound = detail.contains("HTTP 404", ignoreCase = true) ||
        detail.contains("NOT_FOUND", ignoreCase = true)
    val isRetiredModel = detail.contains("no longer available", ignoreCase = true) ||
        detail.contains("not available to new users", ignoreCase = true) ||
        detail.contains("is not found for api version", ignoreCase = true) ||
        detail.contains("not supported for generatecontent", ignoreCase = true) ||
        detail.contains("model is not found", ignoreCase = true) ||
        detail.contains("model not found", ignoreCase = true)
    return isNotFound && isRetiredModel
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

    // AQ.-format Gemini authorization keys are still passed as API keys via x-goog-api-key.
    // Trim pasted whitespace/newlines so a copied key is not accidentally sent with extra characters.
    private val normalizedApiKey = apiKey.trim()

    override suspend fun listModels(): List<String> {
        val body = get("$endpointBase/v1beta/models", mapOf("x-goog-api-key" to normalizedApiKey), currentCoroutineContext()[Job])
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

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> =
        translateWithContext(lines, srcLang, tgtLang, null)

    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        initialContext: Pair<List<String>, List<String>>?,
    ): List<String> {
        val out = mutableListOf<String>()
        var context = initialContext
        var i = 0
        while (i < lines.size) {
            val batch = lines.subList(i, (i + 30).coerceAtMost(lines.size))
            val translated = translateBatchWithAdaptiveRecovery(
                lines = batch,
                context = context,
                isAcceptableTranslation = { original, candidate ->
                    !SubtitleTranslationQualityPolicy.isSourceEcho(original, candidate, srcLang)
                },
            ) { rows, cueContext ->
                translateBatch(rows, srcLang, tgtLang, cueContext)
            }
            out += translated
            val from = (batch.size - CONTEXT_LINES).coerceAtLeast(0)
            context = batch.subList(from, batch.size) to translated.subList(from, translated.size)
            i += 30
        }
        return out
    }

    private suspend fun translateBatch(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>? = null,
    ): List<String> {
        if (normalizedApiKey.isBlank()) {
            throw IllegalStateException("Gemini API Key 为空，请在卡片右侧「•••」填入您的 API Key（可前往 Google AI Studio 免费获取）")
        }
        val src = LangMap.display(srcLang)
        val tgt = LangMap.display(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = buildString {
            append("Translate each numbered subtitle line from $src to $tgt. ")
            append("Reply with exactly `index|translation` per line, nothing else. Include every input index exactly once; never return progress/status text. ")
            append("These cues are consecutive dialogue from one video: keep pronouns, references and tone coherent across lines, ")
            append("and render the same person, place or recurring term identically every time it appears. ")
            append("Prefer natural spoken $tgt that fits a subtitle's length. Preserve tone.")
        }
        val user = buildContextUserPrompt(context, numbered)
            ?: numbered
        val configuredModel = model.trim().removePrefix("models/").ifBlank { FreeModelDefaults.GEMINI_MODEL }
        val payload = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$user"))))
        )
        applyGeminiTranslationThinkingConfig(payload, configuredModel)
        val body = try {
            generateContent(configuredModel, payload.toString())
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val configured = configuredModel
            if (!shouldFallbackToCurrentGeminiModel(configured, error)) throw error
            android.util.Log.w(
                "GeminiEngine",
                "Configured Gemini model $configured is no longer available; retrying with ${FreeModelDefaults.GEMINI_MODEL}",
                error,
            )
            val fallbackModel = FreeModelDefaults.GEMINI_MODEL
            val fallbackPayload = JSONObject(payload.toString()).also {
                it.remove("generationConfig")
                applyGeminiTranslationThinkingConfig(it, fallbackModel)
            }
            generateContent(fallbackModel, fallbackPayload.toString())
        }
        val response = JSONObject(body)
        val candidates = response.optJSONArray("candidates")
            ?: throw IllegalStateException(
                "Gemini 未返回候选译文" +
                    response.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotBlank() && it != "null" }?.let { "（请求被拦截：$it）" }.orEmpty()
            )
        val candidate = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini 未返回可用候选译文（候选数：${candidates.length()}）")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(
                "Gemini 候选结果不包含文本内容（结束原因：${candidate.optString("finishReason", "未知")}）"
            )
        val text = (0 until parts.length()).mapNotNull { index ->
            val part = parts.optJSONObject(index) ?: return@mapNotNull null
            if (part.optBoolean("thought", false)) return@mapNotNull null
            part.optString("text").takeIf { it.isNotBlank() && it != "null" }
        }.joinToString("\n")
        if (text.isBlank()) {
            throw IllegalStateException(
                "Gemini 已响应，但没有返回可用文本（结束原因：${candidate.optString("finishReason", "未知")}）"
            )
        }
        return parseNumbered(text, lines.size)
    }

    /**
     * Use each Gemini generation family's native thinking controls for subtitle translation.
     * Gemini 2.5 Flash/Lite can disable thinking via budget 0; 2.5 Pro cannot, so use its
     * minimum supported budget. Gemini 3+ uses thinkingLevel rather than thinkingBudget.
     */
    private fun applyGeminiTranslationThinkingConfig(payload: JSONObject, modelId: String) {
        val normalized = modelId.lowercase(java.util.Locale.ROOT)
        val thinkingConfig = when {
            normalized.startsWith("gemini-2.5") &&
                normalized.contains("pro") -> JSONObject().put("thinkingBudget", 128)
            normalized.startsWith("gemini-2.5") -> JSONObject().put("thinkingBudget", 0)
            normalized.startsWith("gemini-3") ||
                normalized.startsWith("gemini-4") -> JSONObject().put("thinkingLevel", "low")
            else -> null
        } ?: return
        payload.put("generationConfig", JSONObject().put("thinkingConfig", thinkingConfig))
    }

    private suspend fun generateContent(modelId: String, payload: String): String =
        post(
            "$endpointBase/v1beta/models/$modelId:generateContent",
            mapOf("x-goog-api-key" to normalizedApiKey),
            payload,
            currentCoroutineContext()[Job],
        )

    private fun parseNumbered(content: String, size: Int): List<String> =
        parseModelTranslations(content, size)
}

/** Google Translate 网页端免密钥接口。需要联网；属于非正式接口，可能受服务方限流或调整。 */
class GoogleWebEngine : TranslationEngine {
    override val id = "google-web"
    override val displayName = "Google 翻译（免费在线）"

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        val source = LangMap.google(srcLang)
        val target = LangMap.google(tgtLang, target = true)
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) { out += ""; continue }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=" + java.net.URLEncoder.encode(source, "UTF-8") +
                "&tl=" + java.net.URLEncoder.encode(target, "UTF-8") +
                "&dt=t&q=" + java.net.URLEncoder.encode(line, "UTF-8")
            val root = JSONArray(get(url, mapOf("User-Agent" to "Mozilla/5.0"), currentCoroutineContext()[Job]))
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

    private fun targetCode(code: String): String = LangMap.google(code, target = true)

    private fun requestPayload(texts: List<String>, srcLang: String, tgtLang: String): String {
        val payload = JSONObject()
            .put("q", JSONArray().apply { texts.forEach { put(it) } })
            .put("target", targetCode(tgtLang))
            .put("format", "text")
        val source = srcLang.trim()
        if (source.isNotBlank() && !source.equals("auto", ignoreCase = true)) {
            payload.put("source", LangMap.google(source))
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
            currentCoroutineContext()[Job],
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
    const val GEMINI_MODEL = "gemini-3.8-flash"
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
    /** 免密钥在线引擎：微软翻译（Edge 接口）、百度翻译（网页接口）、DeepL（iOS 客户端接口）。 */
    const val ID_MICROSOFT = -3L
    const val ID_BAIDU = -4L
    const val ID_DEEPL = -5L
    const val ID_FREE_GEMINI = -10L
    const val ID_FREE_DEEPSEEK = -11L
    const val ID_LOCAL_MLKIT = -20L
    const val ID_LOCAL_OPUS = -21L
    const val ID_LOCAL_SMALL100 = -22L
    const val ID_LOCAL_SINGLE_PAIR = -23L

    /** All downloaded/on-device translation models are first-class translation engines. */
    fun isLocalTranslationEngine(activeId: Long): Boolean =
        activeId == ID_LOCAL_MLKIT ||
            activeId == ID_LOCAL_OPUS ||
            activeId == ID_LOCAL_SMALL100 ||
            activeId == ID_LOCAL_SINGLE_PAIR

    fun localTranslationEngineName(activeId: Long): String? = when (activeId) {
        ID_LOCAL_MLKIT -> "ML Kit"
        ID_LOCAL_OPUS -> "OPUS-MT"
        ID_LOCAL_SMALL100 -> "SMaLL-100"
        ID_LOCAL_SINGLE_PAIR -> "单语言对翻译模型"
        else -> null
    }

    fun getBuiltinEngine(activeId: Long, context: Context): TranslationEngine? {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return when (activeId) {
            ID_GOOGLE, ID_GOOGLE_ALT -> GoogleHybridEngine(context)
            ID_LOCAL_MLKIT -> MlKitEngine()
            ID_LOCAL_OPUS -> OpusMtEngine(context)
            ID_LOCAL_SMALL100 -> Small100Engine(context)
            ID_LOCAL_SINGLE_PAIR -> SinglePairTranslationEngine(context)
            ID_MICROSOFT -> MicrosoftWebEngine()
            ID_BAIDU -> BaiduWebEngine()
            ID_DEEPL -> DeepLWebEngine()
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
            "DEEPL" -> DeepLTranslationEngine(key, p.baseUrl)
            "BAIDU_API" -> BaiduOfficialTranslationEngine(readApiProfileCredential(p, "appId"), key)
            "AZURE" -> AzureTranslatorEngine(key, p.region, p.baseUrl)
            "TENCENT" -> TencentCloudTranslationEngine(readApiProfileCredential(p, "secretId"), key, p.region)
            "ALIYUN" -> AliyunMachineTranslationEngine(readApiProfileCredential(p, "accessKeyId"), key)
            "LIBRETRANSLATE" -> LibreTranslateEngine(p.baseUrl, key)
            else -> throw IllegalStateException("未知类型 ${p.type}")
        }
    }
}
