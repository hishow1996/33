package io.translatetube.local.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.sync.Mutex
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
private const val CONTEXT_LINES = TranslationContextPolicy.MAX_CONTEXT_ROWS
private const val SLOW_TRANSLATION_REQUEST_WARNING_MS = 8_000L

/**
 * 组装带上一批上下文的用户提示词。上下文仅供模型保持译名/指代连贯，
 * 明确要求不要翻译或输出这些行；无上下文时返回 null 由调用方退回纯编号列表。
 */
internal fun buildContextUserPrompt(
    context: Pair<List<String>, List<String>>?,
    numbered: String,
    lookaheadSource: List<String> = emptyList(),
): String? {
    val previousCues = context?.let { (srcCtx, tgtCtx) ->
        srcCtx.zip(tgtCtx)
            .filter { (source, translated) -> source.isNotBlank() && translated.isNotBlank() }
            .takeLast(CONTEXT_LINES)
            .toMutableList()
    } ?: mutableListOf()
    val followingCues = lookaheadSource.filter { it.isNotBlank() }.take(3).toMutableList()
    fun encodePrevious(): String = JSONArray().apply {
        previousCues.forEach { (source, translated) ->
            put(JSONObject().put("source", source).put("translation", translated))
        }
    }.toString()
    fun encodeFollowing(): String = JSONArray().apply {
        followingCues.forEach { put(it) }
    }.toString()

    // Never truncate a source/translation pair. Drop the oldest whole pair when needed,
    // and omit a single oversized pair rather than sending a clipped or misleading example.
    while (previousCues.isNotEmpty() && encodePrevious().length > TranslationContextPolicy.MAX_CONTEXT_JSON_CHARS) {
        previousCues.removeAt(0)
    }
    while (followingCues.isNotEmpty() && encodeFollowing().length > TranslationContextPolicy.MAX_LOOKAHEAD_JSON_CHARS) {
        followingCues.removeAt(0)
    }
    if (previousCues.isEmpty() && followingCues.isEmpty()) return null

    val ctxText = encodePrevious()
    val followingText = encodeFollowing()
    return buildString {
        if (previousCues.isNotEmpty()) {
            append("Earlier subtitle cues are provided in chronological order as JSON. Each object's source and translation are one aligned pair. Use these completed pairs only as reference for established names, terms, pronouns, references and tone; do not translate or repeat them. Reuse a confirmed Chinese rendering for the same named entity or technical term consistently; do not invent alternate names. If prior rows use conflicting renderings, prefer the current source and clearly supported context instead of copying the conflict. Do not infer a speaker's identity, gender, relationship or an ambiguous referent unless the source and context clearly support it. The current source text is authoritative; never alter its meaning just to match an earlier translation. A sentence may continue across cue boundaries, so use nearby context to understand its grammar while keeping every current cue's meaning within its own index; do not duplicate, omit, summarize or move content between rows.\n")
            append(ctxText)
            append("\n\n")
        }
        if (followingCues.isNotEmpty()) {
            append("Following cues (source only, for understanding sentence continuation; do not translate or return them): ")
            append(followingText)
            append("\n\n")
        }
        append("Translate only the current numbered subtitle cues below. Include each current index exactly once and return no reference cues.\n")
        append(numbered)
    }
}
/**
 * Whether a compatible chat endpoint returned an assistant message without usable final text.
 * This is narrow on purpose: only this response-shape failure may try one alternate request
 * control style; provider, quota, authentication and transport errors must not be duplicated.
 */
internal fun isEmptyAssistantContentResponse(error: Throwable): Boolean {
    val detail = generateSequence(error) { it.cause }
        .take(8)
        .joinToString(" ") { it.message.orEmpty() }
    return detail.contains("API 未返回可用的翻译文本") &&
        (detail.contains("message.content 为空或不是可识别的文本") ||
            detail.contains("message 缺少 content 字段"))
}

/**
 * Recognize only parser/alignment failures that can improve by lowering the LLM request size.
 * Authentication, quota, transport and server errors must keep their normal error path.
 */
internal fun translationBatchFailureKind(
    error: IllegalStateException,
): ApiSubtitleBatchFailureKind? {
    if (isRetryableTranslationFailure(error)) return null
    val detail = generateSequence<Throwable>(error) { it.cause }
        .take(8)
        .joinToString(" ") { it.message.orEmpty() }
    val normalized = detail.lowercase(java.util.Locale.ROOT)

    val outputCapacityFailure = listOf(
        "finish_reason=length", "finish_reason: length", "finish reason length",
        "finishreason=max_tokens", "finish_reason=max_tokens", "maximum output tokens",
        "max output tokens", "output token limit", "incomplete response due to length",
    ).any(normalized::contains)
    if (outputCapacityFailure) return ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY

    val inputCapacityFailure = listOf(
        "http 413", "context length exceeded", "maximum context length",
        "context_length_exceeded", "too many tokens", "maximum number of tokens",
        "input is too long", "prompt is too long", "request too large",
        "request entity too large", "payload too large", "request body too large",
        "maximum context window", "api_input_budget_exceeded",
        "prompt token budget exceeded",
    ).any(normalized::contains)
    if (inputCapacityFailure) return ApiSubtitleBatchFailureKind.INPUT_CAPACITY

    val malformedTranslation = listOf(
        "重复返回字幕编号", "超出本批范围的字幕编号", "混入未编号内容",
        "混用了0起始和1起始边界", "编号不完整", "格式不完整",
        "响应格式不完整", "无法解析为字幕行",
    ).any(detail::contains)
    return if (malformedTranslation) ApiSubtitleBatchFailureKind.RESPONSE_FORMAT else null
}

/**
 * The provider's own safety filter refused this request (Zhipu: HTTP 400, code 1301,
 * `contentFilter` in the body). It is not a transport or alignment problem and retrying the same
 * text never helps; only sending fewer cues can show which ones trigger it.
 */
internal fun isProviderContentFilterRejection(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { cause ->
        val http = cause as? TranslationHttpException
        val code = http?.providerErrorCode.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        val text = cause.message.orEmpty()
        (code == "1301" && (http?.statusCode == null || http.statusCode == 400)) ||
            code.contains("content_filter") || code.contains("content_policy") ||
            text.contains("contentFilter") || text.contains("不安全或敏感内容")
    }

internal fun isClassifiedTranslationBatchFailure(error: IllegalStateException): Boolean =
    translationBatchFailureKind(error) != null

internal fun isTranslationRecoveryAttemptLimit(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .take(8)
        .any { it.message.orEmpty().contains("单批字幕修复已达到请求上限") }

/**
 * Upper bound shared by same-batch and row-level repairs for one original subtitle batch.
 * This prevents malformed output from triggering an unbounded number of paid requests.
 */
internal const val MAX_TRANSLATION_REPAIR_ATTEMPTS_PER_BATCH = 64

/**
 * How many single cues per original batch may still come back unusable (unchanged or blank)
 * after their own retry before the whole batch is treated as a provider failure. A model that
 * stubbornly echoes one short interjection must not abort the entire video, but a model that
 * fails on many cues is broken and should still stop quickly.
 */
internal const val MAX_ISOLATED_CUE_FAILURES_PER_BATCH = 2

internal const val MIN_TRANSLATION_RECOVERY_BATCH_ROWS = 5

internal data class TranslationRecoverySummary(
    // Legacy performance field retained for compatibility; fixed-size recovery never splits.
    val recoveredBySplitting: Boolean,
    val largestSuccessfulBatchRows: Int?,
    val attempts: Int,
    val failureKinds: Set<ApiSubtitleBatchFailureKind> = emptySet(),
    val hadRecoverableFailure: Boolean = false,
)

private class TranslationRecoveryBudget {
    private var attempts = 0
    private var isolatedCueFailures = 0
    private var largestSuccessfulBatchRows: Int? = null
    private val failureKinds = linkedSetOf<ApiSubtitleBatchFailureKind>()

    /** Any response/capacity failure that entered the bounded repair path. */
    var hadRecoverableFailure: Boolean = false

    /** True once a persistently failing batch was divided into smaller requests. */
    var recoveredBySplitting: Boolean = false

    fun recordFailureKind(kind: ApiSubtitleBatchFailureKind) {
        failureKinds += kind
        hadRecoverableFailure = true
    }

    fun recordSuccessfulBatch(rows: Int) {
        // Single-cue quality retries must never lower the model-wide batch ceiling.
        if (rows >= MIN_TRANSLATION_RECOVERY_BATCH_ROWS) {
            largestSuccessfulBatchRows = maxOf(largestSuccessfulBatchRows ?: rows, rows)
        }
    }

    fun summary(): TranslationRecoverySummary = TranslationRecoverySummary(
        recoveredBySplitting = recoveredBySplitting,
        largestSuccessfulBatchRows = largestSuccessfulBatchRows,
        attempts = attempts,
        failureKinds = failureKinds.toSet(),
        hadRecoverableFailure = hadRecoverableFailure,
    )

    /** True while this batch may still hand an unusable single cue back to the caller. */
    fun tolerateIsolatedCueFailure(): Boolean {
        if (isolatedCueFailures >= MAX_ISOLATED_CUE_FAILURES_PER_BATCH) return false
        isolatedCueFailures++
        return true
    }

    fun consumeAttempt() {
        if (attempts >= MAX_TRANSLATION_REPAIR_ATTEMPTS_PER_BATCH) {
            throw IllegalStateException(
                "翻译响应反复异常，单批字幕修复已达到请求上限 " +
                    MAX_TRANSLATION_REPAIR_ATTEMPTS_PER_BATCH +
                    " 次，已停止以避免过多 API 请求；请稍后重试或更换模型"
            )
        }
        attempts++
    }
}

/**
 * Recover quality and formatting failures without changing the configured batch size.
 * Malformed or truncated output may be retried once with the exact same rows; if alignment
 * still cannot be proven, the batch fails instead of being divided into smaller requests.
 * Correctly aligned rejected rows may be returned to the caller's row-only repair loop.
 *
 * API engines pass [splitOnPersistentFormatFailure]: when the same-size retry also fails, the
 * batch is divided in half (recursively, down to single cues) instead of being abandoned. Before
 * the fixed-batch change this was the recovery that let providers which drop the tail of a long
 * reply (observed on Zhipu) still finish. Halves share the original attempt budget and never
 * repeat the same-size retry, so the number of paid requests stays bounded.
 */
internal suspend fun translateBatchWithAdaptiveRecovery(
    lines: List<String>,
    context: Pair<List<String>, List<String>>? = null,
    isAcceptableTranslation: (source: String, translation: String) -> Boolean = { _, translation ->
        translation.isNotBlank()
    },
    retrySingle: (suspend (String, Pair<List<String>, List<String>>?) -> String?)? = null,
    acceptAfterRecovery: ((source: String, translation: String) -> Boolean)? = null,
    deferInvalidRowsToCaller: Boolean = false,
    retryMalformedBatch: (suspend (List<String>, Pair<List<String>, List<String>>?) -> List<String>)? = null,
    onRecoverySummary: ((summary: TranslationRecoverySummary) -> Unit)? = null,
    splitOnPersistentFormatFailure: Boolean = false,
    translate: suspend (List<String>, Pair<List<String>, List<String>>?) -> List<String>,
): List<String> {
    if (lines.isEmpty()) return emptyList()
    val budget = TranslationRecoveryBudget()
    val result = try {
        translateBatchWithAdaptiveRecoveryInternal(
            lines, context, isAcceptableTranslation, retrySingle, acceptAfterRecovery,
            deferInvalidRowsToCaller, retryMalformedBatch, translate, budget,
            splitOnPersistentFormatFailure,
        )
    } catch (error: Exception) {
        onRecoverySummary?.invoke(budget.summary())
        throw error
    }
    onRecoverySummary?.invoke(budget.summary())
    if (!deferInvalidRowsToCaller && budget.hadRecoverableFailure && lines.indices.any { index ->
            val candidate = result.getOrNull(index).orEmpty()
            lines[index].isNotBlank() &&
                !isAcceptableTranslation(lines[index], candidate) &&
                acceptAfterRecovery?.invoke(lines[index], candidate) != true
        }
    ) {
        throw IllegalStateException(
            "翻译服务响应曾出现行数不匹配或编号/格式不完整；同批次重试或单条修复后仍有字幕未通过校验，翻译失败。"
        )
    }
    return result
}

private fun describeRecoveryResponse(requestedRows: Int, response: List<String>): String =
    if (response.size == requestedRows) {
        "返回 $requestedRows 行"
    } else {
        "返回行数不符 ${response.size}/$requestedRows"
    }

private fun describeRecoveryError(error: Exception): String =
    "${TranslationFailureDiagnosticPolicy.classify(error)}：${error.message.orEmpty().replace('\n', ' ')}"

private suspend fun translateBatchWithAdaptiveRecoveryInternal(
    lines: List<String>,
    context: Pair<List<String>, List<String>>?,
    isAcceptableTranslation: (source: String, translation: String) -> Boolean,
    retrySingle: (suspend (String, Pair<List<String>, List<String>>?) -> String?)?,
    acceptAfterRecovery: ((source: String, translation: String) -> Boolean)?,
    deferInvalidRowsToCaller: Boolean,
    retryMalformedBatch: (suspend (List<String>, Pair<List<String>, List<String>>?) -> List<String>)?,
    translate: suspend (List<String>, Pair<List<String>, List<String>>?) -> List<String>,
    budget: TranslationRecoveryBudget,
    splitOnPersistentFormatFailure: Boolean = false,
): List<String> {
    var recoveryReason = "RESPONSE_ROW_COUNT_MISMATCH"
    var response: List<String>? = null
    var shouldRetrySameBatchForFormat = false
    var sameBatchFormatRetryAttempted = false
    var contentFiltered = false
    val diagnostics = TranslationDiagnosticContextHolder.current()
    try {
        budget.consumeAttempt()
        response = translate(lines, context)
        diagnostics?.recordRecoveryAttempt(lines.size, "首次请求", describeRecoveryResponse(lines.size, response))
    } catch (error: IllegalStateException) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        diagnostics?.recordRecoveryAttempt(lines.size, "首次请求", describeRecoveryError(error))
        if (splitOnPersistentFormatFailure && isProviderContentFilterRejection(error)) {
            contentFiltered = true
            budget.hadRecoverableFailure = true
            recoveryReason = "PROVIDER_CONTENT_FILTER"
        } else {
            val failureKind = translationBatchFailureKind(error) ?: throw error
            budget.recordFailureKind(failureKind)
            budget.hadRecoverableFailure = true
            recoveryReason = TranslationFailureDiagnosticPolicy.classify(error)
            shouldRetrySameBatchForFormat = failureKind == ApiSubtitleBatchFailureKind.RESPONSE_FORMAT
        }
    }

    if (response != null && response.size != lines.size) {
        budget.recordFailureKind(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT)
        budget.hadRecoverableFailure = true
        recoveryReason = "RESPONSE_ROW_COUNT_MISMATCH"
        shouldRetrySameBatchForFormat = true
    }

    if (shouldRetrySameBatchForFormat && retryMalformedBatch != null) {
        sameBatchFormatRetryAttempted = true
        runCatching {
            android.util.Log.w(
                "TranslationAPI",
                "Retrying malformed subtitle response once at same batch size: rows=${lines.size}",
            )
        }
        response = try {
            budget.consumeAttempt()
            retryMalformedBatch(lines, context).also {
                diagnostics?.recordRecoveryAttempt(lines.size, "同批重试", describeRecoveryResponse(lines.size, it))
            }
        } catch (error: IllegalStateException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            diagnostics?.recordRecoveryAttempt(lines.size, "同批重试", describeRecoveryError(error))
            if (splitOnPersistentFormatFailure && isProviderContentFilterRejection(error)) {
                contentFiltered = true
                recoveryReason = "PROVIDER_CONTENT_FILTER"
            } else {
                val failureKind = translationBatchFailureKind(error) ?: throw error
                budget.recordFailureKind(failureKind)
                recoveryReason = TranslationFailureDiagnosticPolicy.classify(error)
            }
            null
        }
        if (response != null && response.size != lines.size) {
            budget.recordFailureKind(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT)
            recoveryReason = "RESPONSE_ROW_COUNT_MISMATCH_AFTER_FORMAT_RETRY"
            response = null
        }
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
        if (!complete) recoveryReason = "TRANSLATION_QUALITY_REJECTED"
        if (complete) {
            budget.recordSuccessfulBatch(lines.size)
            return output
        }
        // Return the original rejected text, not an empty placeholder, so the caller can
        // preserve the exact row-level quality reason in diagnostics and retry only that row.
        if (deferInvalidRowsToCaller) {
            return response.mapIndexed { index, value ->
                if (lines[index].isBlank()) "" else value.trim()
            }
        }

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
                val retry = translateBatchWithAdaptiveRecoveryInternal(
                    lines.subList(missingStart, cursor),
                    retryContext,
                    isAcceptableTranslation,
                    retrySingle,
                    acceptAfterRecovery,
                    deferInvalidRowsToCaller,
                    retryMalformedBatch,
                    translate,
                    budget,
                    splitOnPersistentFormatFailure,
                )
                retry.forEachIndexed { offset, value ->
                    output[missingStart + offset] = value
                }
            }
            return output
        }

        // For a small, correctly aligned batch that only failed quality checks, retain the
        // existing cue-specific retry path. This is not batch-capacity splitting.
        if (retrySingle != null && lines.size in 2 until (MIN_TRANSLATION_RECOVERY_BATCH_ROWS * 2)) {
            val recovered = output.toMutableList()
            val priorCues = mutableListOf<Pair<String, String>>()
            val priorSources = context?.first.orEmpty()
            val priorTranslations = context?.second.orEmpty()
            for (index in 0 until minOf(priorSources.size, priorTranslations.size)) {
                val source = priorSources[index]
                val translation = priorTranslations[index]
                if (source.isNotBlank() && translation.isNotBlank()) priorCues += source to translation
            }
            for (index in lines.indices) {
                val source = lines[index]
                if (source.isBlank() || recovered[index].isNotBlank()) continue
                val recent = priorCues.takeLast(CONTEXT_LINES)
                val retryContext = if (recent.isEmpty()) null else {
                    recent.map { it.first } to recent.map { it.second }
                }
                val retryCandidate = try {
                    budget.consumeAttempt()
                    retrySingle(source, retryContext)
                } catch (error: IllegalStateException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!isClassifiedTranslationBatchFailure(error)) throw error
                    null
                }?.trim().orEmpty()
                if (retryCandidate.isNotBlank() &&
                    (isAcceptableTranslation(source, retryCandidate) ||
                        acceptAfterRecovery?.invoke(source, retryCandidate) == true)
                ) {
                    recovered[index] = retryCandidate
                    priorCues += source to retryCandidate
                    budget.recordSuccessfulBatch(1)
                } else {
                    if (!budget.tolerateIsolatedCueFailure()) {
                        val reason = when {
                            retryCandidate.isBlank() -> "单条恢复请求仍未返回可用文本"
                            retryCandidate == source -> "单条恢复请求仍与原文相同"
                            else -> "单条恢复请求仍未通过译文质量校验"
                        }
                        throw IllegalStateException("单条字幕翻译恢复失败：$reason；原文字符数=${source.length}。")
                    }
                    recovered[index] = retryCandidate
                }
            }
            return recovered
        }
    }

    // A single cue the provider's safety filter refuses can never be translated by this provider.
    // Hand it back as unresolved (blank) so every other cue of the batch is kept; the caller's
    // row-level handling then reports exactly which row it is. Never invent a translation.
    if (contentFiltered && lines.size == 1) {
        diagnostics?.recordRecoveryAttempt(1, "内容安全过滤", "安全过滤拒绝翻译这一行，已单独标记为未翻译")
        diagnostics?.recordContentFilteredCue(lines[0])
        return listOf("")
    }

    // A malformed or wholly missing batch cannot be mapped safely to individual input rows.
    // A malformed fixed-size batch is not subdivided; only the same-batch retry is allowed.
    if (lines.size == 1) {
        // A malformed single-row response has no safe value to pass back. Let the caller retry it.
        if (deferInvalidRowsToCaller) return listOf("")
        if (sameBatchFormatRetryAttempted) {
            throw IllegalStateException(
                "单条字幕格式异常；同批次修复重试后仍未得到可用结果，已停止重复请求。"
            )
        }
        // Even an isolated cue gets one retry: source-echo validation happens inside this helper,
        // so otherwise a single echoed cue would fail before the caller can recover it.
        val retryCandidate = try {
            budget.consumeAttempt()
            if (retrySingle != null) {
                retrySingle(lines.single(), context)
            } else {
                translate(lines, context).singleOrNull()
            }
        } catch (error: IllegalStateException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isClassifiedTranslationBatchFailure(error)) throw error
            null
        }?.trim()
        if (lines.single().isBlank()) return listOf("")
        if (!retryCandidate.isNullOrBlank() &&
            (isAcceptableTranslation(lines.single(), retryCandidate) ||
                // A short name/brand/loanword the model still returns unchanged after a forced
                // retry has nothing left to translate; it must not abort the whole batch.
                acceptAfterRecovery?.invoke(lines.single(), retryCandidate) == true)
        ) {
            budget.recordSuccessfulBatch(1)
            return listOf(retryCandidate)
        }
        if (budget.tolerateIsolatedCueFailure()) {
            // Return the unusable result as an unresolved row. The caller retries it
            // in the active batch and fails the translation stage if it remains unusable.
            return listOf(retryCandidate.orEmpty())
        }
        val failureReason = when {
            retryCandidate.isNullOrBlank() -> "单条恢复请求仍未返回可用文本"
            else -> "单条恢复请求仍与原文相同或未通过译文质量校验"
        }
        throw IllegalStateException(
            "单条字幕翻译恢复失败：$failureReason；原文字符数=${lines.single().length}。" +
                "已保留同批次其他有效译文，请检查模型输出或更换模型。"
        )
    }

    // Batch sizing stays fixed for the next batch. Without splitting enabled, a malformed or
    // truncated response is retried once above and then fails with the alignment evidence intact;
    // with splitting enabled (API engines) only this batch is subdivided, below.
    runCatching {
        android.util.Log.w(
            "TranslationAPI",
            "Fixed-size subtitle batch recovery stopped: rows=${lines.size}, " +
                "sameBatchRetryAttempted=$sameBatchFormatRetryAttempted, reason=$recoveryReason",
        )
    }
    if (splitOnPersistentFormatFailure && lines.size > 1) {
        // Malformed output cannot be mapped to rows, so smaller requests are the only safe way
        // forward: every half is answered on its own and merged in the original order.
        val midpoint = lines.size / 2
        val leftSource = lines.subList(0, midpoint)
        val rightSource = lines.subList(midpoint, lines.size)
        budget.recoveredBySplitting = true
        diagnostics?.recordRecoveryAttempt(
            lines.size, "拆分", "拆成 ${leftSource.size}+${rightSource.size} 条分别请求",
        )
        val leftTranslation = translateBatchWithAdaptiveRecoveryInternal(
            leftSource, context, isAcceptableTranslation, retrySingle, acceptAfterRecovery,
            deferInvalidRowsToCaller, null, translate, budget, true,
        )
        val mergedSource = (context?.first.orEmpty() + leftSource).takeLast(CONTEXT_LINES)
        val mergedTarget = (context?.second.orEmpty() + leftTranslation).takeLast(CONTEXT_LINES)
        val rightContext = if (mergedSource.isEmpty()) null else mergedSource to mergedTarget
        val rightTranslation = translateBatchWithAdaptiveRecoveryInternal(
            rightSource, rightContext, isAcceptableTranslation, retrySingle, acceptAfterRecovery,
            deferInvalidRowsToCaller, null, translate, budget, true,
        )
        return leftTranslation + rightTranslation
    }

    val attemptBrief = diagnostics?.recoveryAttemptBrief().orEmpty()
    throw IllegalStateException(
        "翻译服务响应格式不完整；固定批次 ${lines.size} 条在同批重试后仍无法可靠对齐，" +
            "已停止，不会自动缩小后续批次。请检查模型输出或更换模型。" +
            (if (attemptBrief.isNotBlank()) "尝试记录：$attemptBrief" else "")
    )

}

/**
 * Per-cue acceptance used by the model engines' batch recovery. It must agree with
 * [TranslationBatchResult] so a cue is neither retried forever nor rejected here only to be
 * accepted later (or the other way around):
 * - a pure interjection/vocalization ("Mm-hmm", "Aaaah", "La la la") is accepted as soon as the
 *   model returned anything, because [TranslationBatchResult] replaces it with a fixed Chinese
 *   interjection; spending retries (and the batch's isolated-failure budget) on it is wasteful;
 * - everything else must be speakable, not an echo of the source, and in the target language.
 */
internal fun subtitleTranslationAcceptor(
    srcLang: String,
    tgtLang: String,
): (source: String, translation: String) -> Boolean = { original, candidate ->
    when {
        candidate.isBlank() -> false
        SubtitleInterjectionPolicy.chineseFor(original, srcLang, tgtLang) != null -> true
        else -> SubtitleTranslationQualityPolicy.evaluateTranslation(
            sourceText = original,
            translatedText = candidate,
            sourceLanguage = srcLang,
            targetLanguage = tgtLang,
        ).accepted
    }
}

/** Accept a short name/brand/loanword that stays unchanged even after the forced single-cue retry. */
internal fun subtitleShortUntranslatedAcceptor(
    srcLang: String,
    tgtLang: String,
): (source: String, translation: String) -> Boolean = { original, candidate ->
    SubtitleTranslationQualityPolicy.evaluateTranslation(
        sourceText = original,
        translatedText = candidate,
        sourceLanguage = srcLang,
        targetLanguage = tgtLang,
        acceptShortUnchanged = true,
    ).shortUntranslatedAccepted
}

private val JSON = "application/json; charset=utf-8".toMediaType()
private val translationHeaderRegex = Regex(
    """(?i)^\s*(?:here\s+(?:are|is)\s+(?:(?:the|your)\s+)?translations?|below\s+are\s+(?:the\s+)?translations?|following\s+are\s+(?:the\s+)?translations?|(?:the\s+)?translations?(?:\s+results?)?|以下(?:是|为)?(?:各行|字幕)?(?:翻译|译文)(?:如下)?|当然[,，]?\s*(?:以下|下面)(?:是|为)?(?:字幕)?(?:翻译|译文)(?:如下)?|译文(?:结果)?(?:如下)?|翻译(?:结果)?(?:如下)?)[:：]?\s*$""",
)
private val translationPrefixRegex = Regex(
    """(?is)^\s*(?:here\s+(?:are|is)\s+(?:(?:the|your)\s+)?translations?|below\s+are\s+(?:the\s+)?translations?|following\s+are\s+(?:the\s+)?translations?|(?:the\s+)?translations?(?:\s+results?)?|the\s+translation\s+is|以下(?:是|为)?(?:各行|字幕)?(?:翻译|译文)(?:如下)?|当然[,，]?\s*(?:以下|下面)(?:是|为)?(?:字幕)?(?:翻译|译文)(?:如下)?|译文(?:结果)?(?:如下)?|翻译(?:结果)?(?:如下)?)\s*[:：]\s*(?:\r?\n\s*)?""",
)

private fun stripTranslationPrefix(value: String): String {
    val withoutHeaderLines = value.lines()
        .dropWhile { it.isBlank() || translationHeaderRegex.matches(it.trim()) }
        .joinToString("\n")
        .trim()
    return translationPrefixRegex.replaceFirst(withoutHeaderLines, "").trim()
}

/**
 * Be tolerant of common model formatting variations while preserving subtitle row alignment.
 * Providers sometimes ignore the requested index|text format and return numbered Markdown or
 * plain one-translation-per-line output; treating that as an all-empty batch caused a false
 * "status placeholder" error even though the model returned translations.
 */
internal fun parseModelTranslations(content: String, size: Int): List<String> {
    if (size <= 0) return emptyList()
    fun recordParser(result: String, reason: String = "") {
        TranslationDiagnosticContextHolder.current()?.recordParserEvent(
            parser = "numbered-subtitle-output",
            result = result,
            reason = reason,
        )
    }

    val normalized = content
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .replace(Regex("(?m)^\\s*```[\\w-]*\\s*$"), "")
        .trim()
    if (normalized.isEmpty()) {
        recordParser("EMPTY_OUTPUT", "expectedRows=$size; rawChars=${content.length}")
        return List(size) { "" }
    }

    // Accept JSON arrays and common object-wrapped arrays. Keep org.json calls inside
    // runCatching because Android's local-test stub may throw from length().
    val jsonPayload = runCatching {
        fun readStringArray(array: JSONArray?): List<String>? {
            if (array == null || array.length() != size) return null
            val values = ArrayList<String>(size)
            for (index in 0 until size) {
                val value = array.opt(index) as? String ?: return null
                values += value.trim()
            }
            return values
        }

        val directArray = runCatching { JSONArray(normalized) }.getOrNull()
        if (directArray != null) {
            readStringArray(directArray)?.let { "PARSED_JSON_ARRAY" to it }
        } else {
            val root = JSONObject(normalized)
            val arrayKeys = listOf(
                "translations", "translated_texts", "results", "items", "lines", "data", "output",
            )
            arrayKeys.firstNotNullOfOrNull { key ->
                readStringArray(root.optJSONArray(key))?.let { "PARSED_JSON_OBJECT_ARRAY" to it }
            }
        }
    }.getOrNull()
    val jsonTranslations = jsonPayload?.second
    if (jsonTranslations != null) {
        recordParser(
            jsonPayload.first,
            "expectedRows=$size; outputRows=${jsonTranslations.size}",
        )
        return jsonTranslations
    }

    if (jsonTranslations != null) {
        recordParser("PARSED_JSON_ARRAY", "expectedRows=$size; outputRows=${jsonTranslations.size}")
        return jsonTranslations
    }

    // Accepted examples: 0|text, 1 | text, 1. text, 1) text, 1：text, and Markdown bullets.
    val indexedLine = Regex("""^\s*(?:[-*•]\s*)?(?:\*\*)?(\d+)(?:\*\*)?\s*(?:[|｜:：.、)]\s*|-\s+)(.*?)\s*$""")
    val indexed = linkedMapOf<Int, String>()
    var duplicateIndex = false
    var outOfRangeIndex: String? = null
    val unexpectedLines = mutableListOf<String>()
    normalized.lines().forEach { raw ->
        val line = raw.trim().trim('`').trim()
        if (line.isEmpty() || translationHeaderRegex.matches(line)) return@forEach
        val match = indexedLine.matchEntire(line)
        if (match == null) {
            unexpectedLines += line
            return@forEach
        }
        val indexToken = match.groupValues[1]
        val index = indexToken.toIntOrNull()
        if (index == null || index !in 0..size) {
            // Keep even excessively large numeric indexes: ignoring one could make a response
            // with extra rows look complete and silently misalign subtitle cues.
            if (outOfRangeIndex == null) outOfRangeIndex = indexToken
            return@forEach
        }
        val value = match.groupValues[2].trim().removePrefix("**").removeSuffix("**").trim()
        if (indexed.containsKey(index)) {
            duplicateIndex = true
        } else {
            // Empty numbered rows are valid for empty subtitle cues; keep their index.
            indexed[index] = value
        }
    }
    if (outOfRangeIndex != null) {
        recordParser("REJECTED_OUT_OF_RANGE_INDEX", "index=$outOfRangeIndex; expectedRows=$size; indexedRows=${indexed.size}")
        throw IllegalStateException(
            "翻译服务返回了超出本批范围的字幕编号 " + outOfRangeIndex + "，已拒绝可能错位的译文"
        )
    }
    if (indexed.isNotEmpty()) {
        if (unexpectedLines.isNotEmpty()) {
            recordParser("REJECTED_UNNUMBERED_CONTENT", "expectedRows=$size; indexedRows=${indexed.size}; unnumberedRows=${unexpectedLines.size}")
            throw IllegalStateException(
                "翻译服务在编号译文中混入未编号内容（${unexpectedLines.size} 行未带编号，" +
                    "已解析 ${indexed.size}/$size 条），已拒绝可能错位的译文"
            )
        }
        if (duplicateIndex) {
            recordParser("REJECTED_DUPLICATE_INDEX", "expectedRows=$size; uniqueIndexes=${indexed.size}")
            throw IllegalStateException("翻译服务重复返回字幕编号，已拒绝错位译文")
        }
        // If numbering begins at 1 and no zero row exists, accept the common one-based format.
        // A response containing both boundary markers (0 and size) is ambiguous: never shift it
        // automatically because that could bind a valid translation to the wrong subtitle row.
        val hasZeroBasedStart = 0 in indexed.keys
        val hasOneBasedEnd = size in indexed.keys
        if (hasZeroBasedStart && hasOneBasedEnd) {
            recordParser(
                "REJECTED_MIXED_INDEX_BASE",
                "expectedRows=$size; containsZeroBasedStart=true; containsOneBasedEnd=true; indexedRows=${indexed.size}",
            )
            throw IllegalStateException("翻译服务返回的字幕编号混用了0起始和1起始边界，已拒绝可能错位的译文")
        }
        val base = if (!hasZeroBasedStart && indexed.keys.minOrNull() == 1) 1 else 0
        if (base == 0 && hasOneBasedEnd) {
            recordParser("REJECTED_INDEX_BOUNDARY", "expectedRows=$size; lastIndex=$size; base=$base")
            throw IllegalStateException("翻译服务返回了超出本批范围的字幕编号")
        }
        if ((0 until size).any { (it + base) !in indexed.keys }) {
            val missing = (0 until size).map { it + base }.filterNot(indexed::containsKey)
            // The prompt asks for zero-based numbers. When the reply uses them (index 0 present),
            // contains no unnumbered text, duplicates or out-of-range numbers, every returned row
            // is provably bound to its own cue. Keep those rows and leave only the omitted cues
            // blank: blanks are never accepted as translations, and the caller re-requests just
            // those cues. One-based replies stay rejected because a zero-based reply that merely
            // lost row 0 looks identical and would shift every translation by one.
            if (hasZeroBasedStart && base == 0) {
                recordParser(
                    "PARTIAL_INDEXED_LINES",
                    "expectedRows=$size; indexedRows=${indexed.size}; missingIndexes=${missing.joinToString(",")}",
                )
                return List(size) { i -> indexed[i].orEmpty() }
            }
            recordParser("REJECTED_MISSING_INDEX", "expectedRows=$size; missingIndexes=${missing.joinToString(",")}")
            throw IllegalStateException(
                "翻译服务返回的字幕编号不完整（期望 $size 条，缺少编号 " +
                    missing.take(12).joinToString(",") +
                    (if (missing.size > 12) " 等共 ${missing.size} 个" else "") +
                    "），已拒绝可能错位的译文"
            )
        }
        val parsed = List(size) { i -> indexed.getValue(i + base) }
        recordParser("PARSED_INDEXED_LINES", "expectedRows=$size; outputRows=${parsed.size}; indexBase=$base")
        return parsed
    }

    // Fall back to positional output only when each input row has exactly one output row.
    val plainLines = normalized.lines()
        .map {
            val cleaned = it.trim().removePrefix("- ").removePrefix("* ").trim()
                .removeSurrounding("**").trim()
            stripTranslationPrefix(cleaned)
        }
        .filter { it.isNotBlank() }
        // The response can contain many rows; share this pattern instead of compiling it per row.
        .filterNot { translationHeaderRegex.matches(it) }
    if (plainLines.size == size) {
        recordParser("PARSED_POSITIONAL_LINES", "expectedRows=$size; outputRows=${plainLines.size}")
        return plainLines
    }

    // Single-row probes occasionally return a sentence without any index or wrapper.
    if (size == 1) {
        val single = stripTranslationPrefix(normalized)
        if (single.isNotBlank()) {
            recordParser("PARSED_SINGLE_TEXT", "expectedRows=1; outputChars=${single.length}")
            return listOf(single)
        }
    }

    // Do not disguise an unparseable multi-row response as an aligned list of blanks.
    // The fixed-batch repair path records this format error and retries the same rows once.
    recordParser(
        "UNPARSEABLE_CONTENT",
        "expectedRows=$size; rawChars=${content.length}; normalizedChars=${normalized.length}; lineCount=${normalized.lines().size}; indexedRows=${indexed.size}; unnumberedRows=${unexpectedLines.size}",
    )
    throw IllegalStateException("翻译服务返回内容无法解析为字幕行，已拒绝可能错位的译文")
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
        val url = current.request.url
        val defaultPort = if (url.isHttps) 443 else 80
        val portSuffix = if (url.port == defaultPort) "" else ":" + url.port
        val authority = url.host + portSuffix
        // Keep endpoint path for API diagnosis; omit user-info, all query values and fragments.
        hops += "${current.request.method} ${url.scheme}://$authority${url.encodedPath}"
        current = current.priorResponse
    }
    return hops.asReversed().joinToString(" -> ")
}

/** Only retry transient transport failures and temporary provider throttling/server errors. */
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
 * Retry on temporary network/provider failures with a cancellable backoff (see
 * [TranslationRetryBackoffPolicy]); permanent failures such as invalid API keys surface immediately.
 */
internal suspend fun <T> withBoundedTranslationRetries(
    ownerJob: Job?,
    allowThrottleRetries: Boolean = true,
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
            val waitMs = TranslationRetryBackoffPolicy.nextDelayMs(retries, error, allowThrottleRetries)
                ?: throw error
            TranslationDiagnosticContextHolder.current()?.recordRetry(
                retryNumber = retries + 1,
                waitMs = waitMs,
                failureType = TranslationFailureDiagnosticPolicy.classify(error),
                failureMessage = TranslationFailureDiagnosticPolicy.causeChain(error, maxDepth = 4),
            )
            retries += 1
            kotlinx.coroutines.delay(waitMs)
        }
    }
}

private const val MAX_TRANSLATION_RESPONSE_BYTES = 16L * 1024L * 1024L

internal fun readTranslationResponse(
    response: okhttp3.Response,
    ownerJob: Job?,
): String {
    val body = response.body ?: throw IllegalStateException("空响应")
    val declaredLength = body.contentLength()
    if (declaredLength > MAX_TRANSLATION_RESPONSE_BYTES) {
        TranslationDiagnosticContextHolder.current()?.recordParserEvent(
            parser = "http-response-body-reader",
            result = "REJECTED_SIZE_LIMIT",
            reason = "declaredBytes=$declaredLength; limitBytes=$MAX_TRANSLATION_RESPONSE_BYTES",
        )
        throw IllegalStateException("翻译服务响应超过 16 MB 安全上限，已停止读取以保护设备内存")
    }
    val bytes = try {
        body.byteStream().use { input ->
            io.translatetube.local.yt.BoundedTextPayload.readBytes(
                input,
                maxBytes = MAX_TRANSLATION_RESPONSE_BYTES,
                resourceLabel = "翻译服务响应",
                checkActive = { ownerJob?.ensureActive() },
            )
        }
    } catch (failure: Exception) {
        TranslationDiagnosticContextHolder.current()?.recordParserEvent(
            parser = "http-response-body-reader",
            result = "READ_FAILED",
            reason = "${failure.javaClass.simpleName}: ${TranslationFailureDiagnosticPolicy.safeDiagnosticText(failure.message, 180)}; declaredBytes=$declaredLength",
        )
        throw failure
    }
    val text = String(bytes, Charsets.UTF_8)
    val retryAfterHeader = response.header("Retry-After")
    val retryAfterMs = TranslationRetryBackoffPolicy.parseRetryAfterMs(retryAfterHeader)
    val requestId = listOf(
        "x-request-id",
        "request-id",
        "anthropic-request-id",
        "x-goog-request-id",
        "x-ms-request-id",
        "apim-request-id",
        "x-correlation-id",
    ).firstNotNullOfOrNull { name -> response.header(name)?.takeIf { it.isNotBlank() } }
        ?.let { TranslationFailureDiagnosticPolicy.safeDiagnosticText(it, 100) }
        ?: TranslationFailureDiagnosticPolicy.responseRequestId(text)
    val rateLimitHeaders = listOf(
        "x-ratelimit-limit",
        "x-ratelimit-remaining",
        "x-ratelimit-reset",
        "x-ratelimit-resource",
        "x-ratelimit-bucket",
        "ratelimit-limit",
        "ratelimit-remaining",
    ).mapNotNull { name -> response.header(name)?.takeIf { it.isNotBlank() }?.let { "$name=${it.take(100)}" } }
        .joinToString(",")
    val bodySummary = TranslationFailureDiagnosticPolicy.responseBodySummary(text, includeErrorPreview = !response.isSuccessful)
    val route = responseRoute(response)
    TranslationDiagnosticContextHolder.current()?.recordHttpResponse(
        method = response.request.method,
        route = route,
        statusCode = response.code,
        requestId = requestId,
        contentType = body.contentType()?.toString() ?: response.header("Content-Type"),
        responseBytes = bytes.size.toLong(),
        retryAfterHeader = retryAfterHeader?.let { TranslationFailureDiagnosticPolicy.safeDiagnosticText(it, 80) },
        retryAfterMs = retryAfterMs,
        rateLimitHeaders = rateLimitHeaders,
        bodySummary = bodySummary,
    )
    if (!response.isSuccessful) {
        val providerCode = Regex("""(?i)(?:providerErrorCode=)([^;, ]+)""")
            .find(bodySummary)?.groupValues?.getOrNull(1)
        val providerType = Regex("""(?i)(?:providerErrorType=)([^;, ]+)""")
            .find(bodySummary)?.groupValues?.getOrNull(1)
        val providerMessage = Regex("""(?i)(?:providerErrorMessage=)(.*)$""")
            .find(bodySummary)?.groupValues?.getOrNull(1)
        throw TranslationHttpException(
            "HTTP ${response.code} [${route}]: ${bodySummary}",
            retryAfterMs = retryAfterMs,
            statusCode = response.code,
            requestId = requestId,
            contentType = body.contentType()?.toString() ?: response.header("Content-Type"),
            responseBytes = bytes.size.toLong(),
            retryAfterHeader = retryAfterHeader,
            rateLimitHeaders = rateLimitHeaders,
            providerErrorCode = providerCode,
            providerErrorType = providerType,
            providerErrorMessage = providerMessage,
            responseSummary = bodySummary,
        )
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
        val lock = locks.computeIfAbsent(language) { Mutex() }
        lock.withLock {
            if (!RemoteModelManager.getInstance().isModelDownloaded(model).await()) {
                throw IllegalStateException("离线翻译包 $languageTag 尚未下载，请到 API 管理 → 本地翻译下载对应语言包，或切换其他翻译引擎。")
            }
        }
    }

    suspend fun isDownloaded(languageTag: String): Boolean {
        val language = TranslateLanguage.fromLanguageTag(languageTag) ?: return false
        val model = TranslateRemoteModel.Builder(language).build()
        // Read-only status checks stay non-blocking so the settings page can reflect active SDK downloads.
        return RemoteModelManager.getInstance().isModelDownloaded(model).await()
    }

    suspend fun deleteLanguage(languageTag: String) {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalStateException("不支持的离线翻译语言: $languageTag")
        val model = TranslateRemoteModel.Builder(language).build()
        val lock = locks.computeIfAbsent(language) { Mutex() }
        lock.withLock { RemoteModelManager.getInstance().deleteDownloadedModel(model).await() }
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
    // A translator must not be closed/replaced while a subtitle batch is using it.
    private val translationMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // The interface is synchronous, but ML Kit operations are suspend-based. Defer closing
        // until the current batch is complete instead of invalidating an in-flight translator.
        closeScope.launch {
            translationMutex.withLock {
                synchronized(this@MlKitEngine) {
                    activeTranslator?.close()
                    activeTranslator = null
                    activePair = null
                }
            }
        }
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        return translationMutex.withLock {
            val source = validateConfiguration(srcLang, tgtLang, lines)
            val target = LangMap.mlkit(tgtLang)
            val translator = translatorFor(source, target)
            lines.mapIndexed { index, line ->
                currentCoroutineContext().ensureActive()
                if (line.isBlank()) {
                    ""
                } else {
                    try {
                        translator.translate(line).await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (lineError: Exception) {
                        // One malformed/untranslatable cue should not discard good rows in the same
                        // batch. Return an empty row so PlaybackService can retry it independently.
                        android.util.Log.w(
                            "MlKitEngine",
                            "ML Kit translation failed for subtitle row ${index + 1} (chars=${line.length}); raw text omitted",
                            lineError,
                        )
                        ""
                    }
                }
            }
        }
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
    ZHIPU,
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
    if (ChatCompletionParamsPolicy.isThinkingOnlyModel(normalizedModel)) {
        return listOf(ThinkingControlStyle.PLAIN)
    }

    val preferred = when {
        normalizedBase.contains("openrouter.ai") -> ThinkingControlStyle.OPENROUTER
        normalizedBase.contains("open.bigmodel.cn") || normalizedBase.contains("api.z.ai") ->
            ThinkingControlStyle.ZHIPU
        normalizedBase.contains("api.deepseek.com") || normalizedModel.contains("deepseek") ->
            ThinkingControlStyle.DEEPSEEK
        normalizedBase.contains("dashscope") ||
            normalizedBase.contains("qwencloud") ||
            normalizedModel.contains("qwen") ->
            ThinkingControlStyle.QWEN
        normalizedModel.contains("glm") || normalizedModel.contains("zhipu") ->
            ThinkingControlStyle.ZHIPU
        normalizedModel.contains("kimi") ||
            normalizedModel.contains("reasoner") ||
            normalizedModel.contains("thinking") ||
            normalizedModel.contains("qwq") ||
            Regex("""(^|[-_/])(r1|r2|o1|o3|o4)([-_/]|$)""").containsMatchIn(normalizedModel) ||
            normalizedModel.contains("gpt-5") || normalizedModel.contains("gpt5") ||
            normalizedModel.contains("gpt-6") ->
            ThinkingControlStyle.REASONING_EFFORT
        else -> ThinkingControlStyle.PLAIN
    }

    val fallback = when (preferred) {
        ThinkingControlStyle.ZHIPU -> ThinkingControlStyle.PLAIN
        ThinkingControlStyle.DEEPSEEK,
        ThinkingControlStyle.QWEN,
        ThinkingControlStyle.OPENROUTER,
        ThinkingControlStyle.PLAIN -> ThinkingControlStyle.REASONING_EFFORT
        ThinkingControlStyle.REASONING_EFFORT -> ThinkingControlStyle.PLAIN
    }
    return listOf(preferred, fallback).distinct()
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
    // Only retry explicit client-side parameter rejections. Never turn rate limits, auth,
    // network timeout, missing routes, or upstream failures into a duplicate paid request.
    val httpStatus = Regex("""\bhttp\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
        .find(detail)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (httpStatus != null && httpStatus !in setOf(400, 422)) return false

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
private fun extractTextFromContentValue(value: Any?, depth: Int = 0): String {
    // Treat provider JSON as untrusted input: bound recursion across nested content wrappers.
    if (depth > 32) return ""
    return when (value) {
        is String -> value
        is JSONArray -> {
            val parts = mutableListOf<String>()
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                if (item is JSONObject) {
                    val type = item.optString("type", "").lowercase(java.util.Locale.ROOT)
                    if (type.isNotBlank() && isReasoningFieldName(type)) continue
                }
                val text = extractTextFromContentValue(item, depth + 1)
                if (text.isNotBlank()) parts += text
            }
            parts.joinToString("\n")
        }
        is JSONObject -> {
            val type = value.optString("type", "").lowercase(java.util.Locale.ROOT)
            if (type.isNotBlank() && isReasoningFieldName(type)) {
                ""
            } else {
                val directText = listOf(
                    "text", "output_text", "final_answer", "answer", "translated_text",
                    "translation", "content", "value",
                ).firstNotNullOfOrNull { key ->
                    (value.opt(key) as? String)?.takeIf { it.isNotBlank() }
                }
                if (directText != null) {
                    directText
                } else {
                    // Some gateways nest the actual text one level deeper (e.g. content.value,
                    // message.content, or result.parts) instead of using a standard text field.
                    listOf("parts", "content", "output", "data", "result", "message")
                        .firstNotNullOfOrNull { key ->
                            when (val nested = value.opt(key)) {
                                is JSONObject, is JSONArray ->
                                    extractTextFromContentValue(nested, depth + 1)
                                        .takeIf { it.isNotBlank() }
                                is String -> nested.takeIf { it.isNotBlank() }
                                else -> null
                            }
                        }.orEmpty()
                }
            }
        }
        else -> ""
    }
}

/** Read common final-text variants used by Chat Completions and Responses-style gateways. */
private fun extractAlternativeResponseText(root: JSONObject): String {
    // OpenAI-style and several compatible gateways put final text directly on the response.
    val topLevel = listOf(
        "output_text", "final_answer", "final", "answer", "translated_text", "translation", "text",
    ).firstNotNullOfOrNull { field ->
        (root.opt(field) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (!topLevel.isNullOrBlank()) return topLevel

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

private fun hasMeaningfulResponseValue(value: Any?): Boolean = when (value) {
    is String -> value.isNotBlank()
    is JSONObject -> value.length() > 0
    is JSONArray -> value.length() > 0
    null, JSONObject.NULL -> false
    else -> true
}

/**
 * Find provider-specific reasoning fields anywhere in a bounded-depth JSON tree.
 * This includes Responses-style output items whose type is "reasoning", even when choices is absent.
 */
private fun findReasoningFields(root: Any?): List<String> {
    val found = linkedSetOf<String>()
    val pending = java.util.ArrayDeque<Any>()
    if (root is JSONObject || root is JSONArray) pending.add(root)
    var visited = 0
    while (pending.isNotEmpty() && visited < 20_000) {
        visited++
        when (val node = pending.removeLast()) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (isReasoningFieldName(key) && hasMeaningfulResponseValue(value)) {
                        found += key
                    }
                    if (key.equals("type", ignoreCase = true) &&
                        value is String && isReasoningFieldName(value)
                    ) {
                        found += value
                    }
                    when (value) {
                        is JSONObject, is JSONArray -> pending.add(value)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    when (val value = node.opt(index)) {
                        is JSONObject, is JSONArray -> pending.add(value)
                    }
                }
            }
        }
        if (found.isNotEmpty()) return found.toList()
    }
    return found.toList()
}

/**
 * Return safe, content-free diagnostics for an OpenAI-compatible response.
 * Never log subtitle text, API keys, or authorization headers.
 */
internal fun openAiCompatResponseSummary(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull()
        ?: return "json=invalid"
    val choices = root.optJSONArray("choices")
    val firstChoice = choices?.optJSONObject(0)
    val message = firstChoice?.optJSONObject("message")
    val rawContent = message?.opt("content")
    val contentKind = when (rawContent) {
        is String -> "string"
        is JSONArray -> "array"
        is JSONObject -> "object"
        null, JSONObject.NULL -> "missing"
        else -> rawContent.javaClass.simpleName
    }
    val contentChars = if (rawContent == null || rawContent == JSONObject.NULL) {
        0
    } else {
        runCatching { extractTextFromContentValue(rawContent).length }.getOrDefault(-1)
    }
    val messageFields = message?.let { value ->
        val fields = mutableListOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) fields += iterator.next()
        fields.sorted().joinToString(",")
    }.orEmpty()
    val reasoningFields = findReasoningFields(root).joinToString(",")
    val errorObject = root.optJSONObject("error")
    val errorCode = errorObject?.opt("code")?.toString()?.take(80).orEmpty()
    return buildString {
        append("object=").append(root.optString("object", "unknown"))
        append(", choices=").append(choices?.length() ?: 0)
        append(", finish_reason=").append(firstChoice?.optString("finish_reason", "missing") ?: "missing")
        append(", message_fields=[").append(messageFields).append(']')
        append(", content_type=").append(contentKind)
        append(", content_chars=").append(contentChars)
        append(", reasoning_fields=[").append(reasoningFields).append(']')
        if (errorCode.isNotBlank() && errorCode != "null") {
            append(", provider_error_code=").append(errorCode)
        }
    }
}

/** Extract text from a non-streaming OpenAI-compatible chat-completion response. */
internal fun extractOpenAiCompatContent(body: String): String {
    val trace = TranslationDiagnosticContextHolder.current()
    return try {
        extractOpenAiCompatContentInternal(body).also { output ->
            trace?.recordParserEvent(
                parser = "openai-compatible-final-text",
                result = "PARSED",
                reason = "outputChars=${output.length}; responseShape=${openAiCompatResponseSummary(body)}",
            )
        }
    } catch (failure: Exception) {
        val result = when (failure) {
            is ReasoningOnlyResponseException -> "REASONING_WITHOUT_FINAL_TEXT"
            else -> "FAILED"
        }
        trace?.recordParserEvent(
            parser = "openai-compatible-final-text",
            result = result,
            reason = "${failure.javaClass.simpleName}: ${TranslationFailureDiagnosticPolicy.safeDiagnosticText(failure.message, 220)}",
        )
        throw failure
    }
}

private fun extractOpenAiCompatContentInternal(body: String): String {
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
            // Some gateways encode error codes as numbers and expose useful context via type/param.
            val code = rawError.opt("code")
                ?.takeIf { it != JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() && it != "null" }
            val type = (rawError.opt("type") as? String)
                ?.takeIf { it.isNotBlank() }
            val param = (rawError.opt("param") as? String)
                ?.takeIf { it.isNotBlank() }
            val errorContext = listOfNotNull(
                type?.let { "type=$it" },
                param?.let { "param=$it" },
            ).joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
            throw IllegalStateException(
                "API 返回错误" + (code?.let { " [$it]" } ?: "") +
                    errorContext + "：" + message.take(400)
            )
        }
        is String -> if (rawError.isNotBlank()) {
            throw IllegalStateException("API 返回错误：" + rawError.take(400))
        }
    }

    val alternativeResponseText = extractAlternativeResponseText(root)
    if (alternativeResponseText.isNotBlank()) return alternativeResponseText

    val choices = root.optJSONArray("choices")
        ?: run {
            val reasoningFields = findReasoningFields(root)
            if (reasoningFields.isNotEmpty()) {
                throw ReasoningOnlyResponseException(
                    listOf("response"),
                    reasoningFields,
                    root.optString("status", "未知"),
                )
            }
            throw IllegalStateException(
                "API 响应格式不兼容：既没有 choices，也没有可识别的 output_text/output 内容。" +
                    "object=${root.optString("object", "未知")}；请检查中转站接口类型。"
            )
        }
    if (choices.length() == 0) {
        val reasoningFields = findReasoningFields(root)
        if (reasoningFields.isNotEmpty()) {
            throw ReasoningOnlyResponseException(
                listOf("response"),
                reasoningFields,
                root.optString("status", "未知"),
            )
        }
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
    val reasoningFields = findReasoningFields(message)
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

/**
 * Build an OpenAI-compatible endpoint from a Base URL.
 *
 * Supports ordinary service roots and providers with an API version in the base path
 * (for example, Zhipu's /api/paas/v4). If a full /chat/completions or /models endpoint
 * was pasted into the Base URL field, remove that resource suffix before building a route.
 * Existing query parameters stay after the endpoint path.
 */
internal fun openAiCompatModelCatalogEndpoints(baseUrl: String): List<String> {
    val primary = openAiCompatEndpoint(baseUrl, "/models")
    val uri = java.net.URI(baseUrl.trim().trimEnd('/'))
    var basePath = uri.rawPath.orEmpty().trimEnd('/')
    basePath = when {
        basePath.endsWith("/chat/completions", ignoreCase = true) ->
            basePath.dropLast("/chat/completions".length)
        basePath.endsWith("/models", ignoreCase = true) ->
            basePath.dropLast("/models".length)
        else -> basePath
    }.trimEnd('/')
    val unversionedPath = basePath.replace(Regex("/v[0-9]+$", RegexOption.IGNORE_CASE), "")
    val fallbackPath = (unversionedPath + "/models")
        .replace(Regex("/{2,}"), "/")
        .let { if (it.startsWith('/')) it else "/$it" }
    val fallback = buildString {
        append(uri.scheme)
        append("://")
        append(uri.rawAuthority)
        append(fallbackPath)
        uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append("?$it") }
    }
    return listOf(primary, fallback).distinct()
}

private fun isModelCatalogRouteMissing(error: Throwable): Boolean {
    val detail = generateSequence(error) { it.cause }
        .take(8)
        .joinToString(" ") { it.message.orEmpty() }
    return Regex("""\bHTTP\s+(404|405)\b""", RegexOption.IGNORE_CASE)
        .containsMatchIn(detail) ||
        Regex("""\b(404|405)\s+(Not Found|Method Not Allowed)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(detail)
}

internal fun openAiCompatEndpoint(baseUrl: String, path: String): String {
    val rawBase = baseUrl.trim().trimEnd('/')
    require(rawBase.isNotEmpty()) { "API Base URL 不能为空" }

    val uri = try {
        java.net.URI(rawBase)
    } catch (_: Exception) {
        throw IllegalArgumentException("API Base URL 格式无效，请填写 http:// 或 https:// 开头的地址")
    }
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    require(uri.isAbsolute && scheme in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()) {
        "API Base URL 格式无效，请填写 http:// 或 https:// 开头的地址"
    }

    var basePath = uri.rawPath.orEmpty().trimEnd('/')
    basePath = when {
        basePath.endsWith("/chat/completions", ignoreCase = true) ->
            basePath.dropLast("/chat/completions".length)
        basePath.endsWith("/models", ignoreCase = true) ->
            basePath.dropLast("/models".length)
        else -> basePath
    }.trimEnd('/')

    val finalPathSegment = basePath.substringAfterLast('/')
    val hasExplicitVersion = Regex("""v[0-9]+""", RegexOption.IGNORE_CASE)
        .matches(finalPathSegment)
    val apiRootPath = if (hasExplicitVersion) basePath else "$basePath/v1"
    val endpointPath = "${apiRootPath}/${path.trimStart('/')}"
        .replace(Regex("/{2,}"), "/")
        .let { if (it.startsWith('/')) it else "/$it" }

    return buildString {
        append(scheme)
        append("://")
        append(uri.rawAuthority)
        append(endpointPath)
        uri.rawQuery?.let {
            if (it.isNotEmpty()) {
                append('?')
                append(it)
            }
        }
    }
}

private data class TranslationApiUsage(
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val finishReason: String? = null,
)

private data class OpenAiTranslationResponse(
    val content: String,
    val usage: TranslationApiUsage,
    val elapsedMs: Long,
)

private data class ApiSubtitlePrompts(val system: String, val user: String)

private fun buildOpenAiSubtitlePrompts(
    lines: List<String>,
    srcLang: String,
    tgtLang: String,
    context: Pair<List<String>, List<String>>?,
    lookaheadSource: List<String>,
    videoTitle: String?,
    forceTranslate: Boolean,
    jsonArrayResponse: Boolean = false,
): ApiSubtitlePrompts {
        val src = LangMap.display(srcLang)
        val tgt = SubtitleTranslationPromptPolicy.targetLanguageLabel(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = buildString {
            append("You are a professional subtitle translator. These numbered lines are consecutive subtitle cues from one video. ")
            append("Translate each numbered line from $src to $tgt. ")
            if (jsonArrayResponse) {
                append("For this format-repair request, reply with exactly one valid JSON array of strings in the same order as the input lines. ")
                append("The array must contain exactly ${lines.size} string items. Do not include indexes, Markdown fences, explanations or any text outside the JSON array. ")
            } else {
                append("Reply with the exact same `index|translation` format, one per line, nothing else. ")
                append("Include every input index exactly once; never return progress/status text. ")
            }
            append("Do not copy ordinary source-language words or sentences unchanged. If wording is ambiguous, provide your best natural $tgt translation instead of echoing the source. ")
            append("Preserve unchanged only content that naturally remains the same, such as proper names, brand names, acronyms, URLs, email addresses and standalone numbers. ")
            if (forceTranslate) {
                append("This is a recovery request for a cue that was previously empty or echoed from the source. Translate its ordinary words into $tgt now; do not repeat the source-language wording. ")
            }
            append("Translate as coherent dialogue: keep pronouns, references and tone consistent across lines. ")
            append("A sentence may be split across adjacent subtitle cues; use the reference cues to understand fragments, but translate only the current cue assigned to each index. ")
            append("Do not omit, summarize, invent facts or move content between subtitle rows. ")
            append("Render the same person, place or recurring term identically every time it appears. ")
            append("Prefer natural spoken $tgt that fits a subtitle's length; avoid stiff word-for-word translation. ")
            append("The source text may come from automatic speech recognition: it can contain misrecognized words, missing punctuation and wrong sentence breaks. Infer the intended wording from context and translate the intended meaning; never translate an obvious misrecognition literally, and never add content that the speech does not support. ")
            videoTitle?.trim()?.take(120)?.takeIf { it.isNotBlank() }?.let { title ->
                append("Video title (for topic and terminology only): $title. ")
            }
            append(SubtitleTranslationPromptPolicy.guidelinesForTarget(tgtLang))
            append("Preserve meaning, tone and speaker style. Keep empty or already-$tgt lines unchanged.")
        }
        val user = buildContextUserPrompt(context, numbered, lookaheadSource)
            ?: numbered
    return ApiSubtitlePrompts(sys, user)
}

/**
 * Read common model-catalog envelopes used by OpenAI-compatible gateways and relays.
 * Official OpenAI exposes IDs only; DeepSeek and OpenRouter additionally publish machine-readable
 * context/output limits. Unknown fields remain optional and never make a model unavailable.
 */
private fun parseOpenAiCompatibleModelCatalog(body: String): List<JSONObject> {
    fun convertArray(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val models = mutableListOf<JSONObject>()
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> models += item
                is String -> if (item.isNotBlank()) {
                    models += JSONObject().put("id", item)
                }
            }
        }
        return models
    }

    val rootObject = runCatching { JSONObject(body) }.getOrNull()
    if (rootObject == null) {
        val rootArray = runCatching { JSONArray(body) }.getOrNull()
        return convertArray(rootArray)
    }

    val data = rootObject.optJSONObject("data")
    val models = rootObject.optJSONObject("models")
    val result = rootObject.optJSONObject("result")
    val arrays = listOfNotNull(
        rootObject.optJSONArray("data"),
        rootObject.optJSONArray("models"),
        rootObject.optJSONArray("items"),
        rootObject.optJSONArray("result"),
        rootObject.optJSONArray("model_list"),
        data?.optJSONArray("models"),
        data?.optJSONArray("data"),
        models?.optJSONArray("data"),
        models?.optJSONArray("items"),
        result?.optJSONArray("data"),
        result?.optJSONArray("models"),
        result?.optJSONArray("items"),
    )
    return arrays.firstOrNull { it.length() > 0 }?.let(::convertArray)
        ?: arrays.firstOrNull()?.let(::convertArray)
        ?: emptyList()
}

private fun JSONObject.firstPositiveInt(vararg keys: String): Int? {
    for (key in keys) {
        val raw = opt(key)
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: continue
        if (value in 1..Int.MAX_VALUE.toLong()) return value.toInt()
    }
    return null
}

private data class ApiCatalogModelLimits(
    val contextWindowTokens: Int?,
    val maxInputTokens: Int?,
    val maxOutputTokens: Int?,
)

private fun readApiCatalogModelLimits(model: JSONObject): ApiCatalogModelLimits {
    val nested = listOfNotNull(
        model.optJSONObject("limits"),
        model.optJSONObject("top_provider"),
        model.optJSONObject("architecture"),
        model.optJSONObject("token_limits"),
        model.optJSONObject("model_limits"),
        model.optJSONObject("capabilities"),
        model.optJSONObject("metadata"),
    )
    val sources = listOf(model) + nested
    fun read(vararg keys: String): Int? =
        sources.firstNotNullOfOrNull { source -> source.firstPositiveInt(*keys) }

    return ApiCatalogModelLimits(
        contextWindowTokens = read(
            "context_window",
            "context_length",
            "context_window_tokens",
            "max_context_length",
            "max_context_tokens",
            "max_model_len",
            "num_ctx",
            "context",
        ),
        maxInputTokens = read(
            "max_input_tokens",
            "input_token_limit",
            "max_prompt_tokens",
            "max_input_token_length",
            "max_input_length",
            "input_limit",
            "max_input",
            "input",
        ),
        maxOutputTokens = read(
            "max_output_tokens",
            "max_completion_tokens",
            "output_token_limit",
            "max_output_length",
            "completion_limit",
            "output_limit",
            "max_tokens",
            "output",
        ),
    )
}

private fun JSONObject.readApiCatalogModelId(): String? =
    listOf("id", "model", "model_id", "slug", "name")
        .firstNotNullOfOrNull { key ->
            optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
        }

/** OpenAI 兼容引擎（OpenAI / DeepSeek / 智谱 / Kimi / Ollama / 任意兼容中转） */
class OpenAiCompatEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val batchLearningStore: ApiSubtitleBatchLearningStore? = null,
) : TranslationEngine {
    override val id = "openai"
    override val displayName = model.ifEmpty { "OpenAI 兼容" }
    private val batchLearningByLanguage =
        java.util.concurrent.ConcurrentHashMap<String, ApiSubtitleBatchLearningState>()

    private fun batchLearningFor(srcLang: String, tgtLang: String): ApiSubtitleBatchLearningState {
        val scope = "${srcLang.trim().lowercase(java.util.Locale.ROOT)}->" +
            tgtLang.trim().lowercase(java.util.Locale.ROOT)
        return batchLearningByLanguage.computeIfAbsent(scope) {
            ApiSubtitleBatchLearningState(model, baseUrl, batchLearningStore, scope)
        }
    }

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
            ThinkingControlStyle.DEEPSEEK,
            ThinkingControlStyle.ZHIPU ->
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

    private suspend fun requestTranslationContent(
        payload: JSONObject,
        outputBudgetRaised: Boolean = false,
    ): OpenAiTranslationResponse {
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
                android.util.Log.d(
                    "TranslationAPI",
                    "Response metadata: model=${model.take(100)}, thinkingControl=$style, " +
                        "elapsedMs=$elapsedMs, ${openAiCompatResponseSummary(responseBody)}",
                )
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
                val root = JSONObject(responseBody)
                val usage = root.optJSONObject("usage")
                val finishReason = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optString("finish_reason")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                return OpenAiTranslationResponse(
                    content = content,
                    usage = TranslationApiUsage(
                        promptTokens = usage?.optInt("prompt_tokens")?.takeIf { it > 0 },
                        outputTokens = usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                        finishReason = finishReason,
                    ),
                    elapsedMs = elapsedMs,
                )
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
                            "thinkingControl=$style, elapsedMs=${elapsedMs}",
                    )
                } else if (styles.indexOf(style) == 0 &&
                    styles.size > 1 &&
                    isEmptyAssistantContentResponse(error)
                ) {
                    // Some gateways intermittently omit message.content. Try the second request
                    // shape once for this batch; do not recursively split a response that has no
                    // usable text, which would multiply calls without a reliable alignment gain.
                    android.util.Log.w(
                        "TranslationAPI",
                        "Assistant content missing; retrying once with alternate request control: " +
                            "model=${model.take(100)}, thinkingControl=$style, elapsedMs=${elapsedMs}",
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

        // The model spent its whole output budget on reasoning (finish_reason=length): the same
        // request will fail again, so give it more room once before giving up.
        if (!outputBudgetRaised &&
            lastReasoningOnly != null &&
            ChatCompletionParamsPolicy.isLengthTruncation(lastReasoningOnly.finishReason)
        ) {
            val raised = JSONObject(payload.toString())
            val budgetKey = if (raised.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
            val modelOutputCeiling = ApiSubtitleBatchPolicy.capabilityForModel(model, baseUrl).maxOutputTokens
            val reasoningRetryBudget = minOf(
                ChatCompletionParamsPolicy.REASONING_MAX_TOKENS,
                modelOutputCeiling,
            )
            if (raised.optInt(budgetKey, 0) < reasoningRetryBudget) {
                raised.put(budgetKey, reasoningRetryBudget)
                android.util.Log.w(
                    "TranslationAPI",
                    "Reasoning used the whole output budget; retrying once with $budgetKey=" +
                        "${reasoningRetryBudget}: model=${model.take(100)}",
                )
                return requestTranslationContent(raised, outputBudgetRaised = true)
            }
        }

        throw IllegalStateException(
            "API 返回了思考内容但没有最终翻译文本，或服务商不接受常见的关闭思考模式参数。" +
                "已尝试多种兼容格式仍未取得译文；该模型可能不支持关闭思考模式，或中转站未透传相关参数。" +
                "请检查中转站的模型能力和思考模式设置。",
            lastReasoningOnly ?: lastRejectedControl,
        )
    }

    private fun endpoint(path: String): String =
        openAiCompatEndpoint(baseUrl, path)

    override suspend fun listModels(): List<String> {
        val ownerJob = currentCoroutineContext()[Job]
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val endpoints = openAiCompatModelCatalogEndpoints(baseUrl)
        var catalogBody: String? = null
        for ((index, url) in endpoints.withIndex()) {
            try {
                catalogBody = get(url, headers, ownerJob)
                break
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (index == endpoints.lastIndex || !isModelCatalogRouteMissing(error)) throw error
                android.util.Log.i(
                    "TranslationAPI",
                    "Model catalog route unavailable; trying compatible fallback path",
                )
            }
        }
        val body = catalogBody
            ?: throw IllegalStateException("API 未返回模型目录")
        return parseOpenAiCompatibleModelCatalog(body).mapNotNull { item ->
            val id = item.readApiCatalogModelId() ?: return@mapNotNull null
            val limits = readApiCatalogModelLimits(item)
            if (limits.contextWindowTokens != null ||
                limits.maxInputTokens != null ||
                limits.maxOutputTokens != null
            ) {
                val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
                    model = id,
                    contextWindowTokens = limits.contextWindowTokens,
                    maxOutputTokens = limits.maxOutputTokens,
                    maxInputTokens = limits.maxInputTokens,
                    providerKey = baseUrl,
                )
                batchLearningStore?.saveCapability(id, capability)
            }
            id
        }.distinct().sorted()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> =
        translateWithContext(lines, srcLang, tgtLang, null, emptyList(), null)

    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        initialContext: Pair<List<String>, List<String>>?,
        lookaheadSource: List<String>,
        videoTitle: String?,
    ): List<String> {
        val out = mutableListOf<String>()
        val batchLearning = batchLearningFor(srcLang, tgtLang)
        val usableContextRow = subtitleTranslationAcceptor(srcLang, tgtLang)
        val usableRecoveredNameRow = subtitleShortUntranslatedAcceptor(srcLang, tgtLang)
        val coroutineContext = currentCoroutineContext()
        var i = 0
        while (i < lines.size) {
            val learningSnapshot = batchLearning.snapshot()
            fun contextForBatch(candidateLines: List<String>): Pair<List<String>, List<String>>? =
                TranslationContextPolicy.contextForBatchWithTermContinuity(
                    initialContext = initialContext,
                    completedSources = lines.subList(0, i),
                    completedTranslations = out,
                    currentSourceCues = candidateLines,
                    sourceLanguage = srcLang,
                    checkActive = { coroutineContext.ensureActive() },
                    isUsable = { source, translation ->
                        usableContextRow(source, translation) ||
                            usableRecoveredNameRow(source, translation)
                    },
                )
            // Build bounded history once per outer batch; preflight and send reuse it.
            val contextForSelection = contextForBatch(
                lines.subList(i, minOf(lines.size, i + FIXED_API_TRANSLATION_BATCH_ROWS))
            )
            val batchSelection = ApiSubtitleBatchPolicy.selectBatch(
                lines = lines,
                startIndex = i,
                model = model,
                targetLanguage = tgtLang,
                learning = learningSnapshot,
                providerKey = baseUrl,
                inputTokenEstimator = { candidateLines ->
                    val prompt = buildOpenAiSubtitlePrompts(
                        candidateLines, srcLang, tgtLang, contextForSelection,
                        lookaheadSource, videoTitle, forceTranslate = false,
                    )
                    ApiSubtitleBatchPolicy.estimatePromptTokens(
                        prompt.system, prompt.user, learningSnapshot.inputTokenMultiplier,
                    )
                },
            )
            val batch = lines.subList(i, batchSelection.endExclusive)
            android.util.Log.d(
                "TranslationAPI",
                "Subtitle batch selected: model=${model.take(100)}, startRow=${i + 1}, " +
                    "rows=${batch.size}, rowCap=$FIXED_API_TRANSLATION_BATCH_ROWS, " +
                    "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                    "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}, " +
                    "estimatedInputTokens=${batchSelection.estimatedInputTokens}, " +
                    "estimatedOutputTokens=${batchSelection.estimatedOutputTokens}, " +
                    "maxOutputTokens=${batchSelection.maxOutputTokens}",
            )
            if (batchSelection.batchExceedsInputBudget ||
                batchSelection.batchExceedsOutputBudget
            ) {
                val budgetDetail = when {
                    batchSelection.batchExceedsInputBudget && batchSelection.batchExceedsOutputBudget ->
                        "输入和输出 Token 预算均不足"
                    batchSelection.batchExceedsInputBudget -> "输入 Token 预算不足"
                    else -> "输出 Token 预算不足"
                }
                throw IllegalStateException(
                    "固定 ${FIXED_API_TRANSLATION_BATCH_ROWS} 条批次超出当前模型 Token 预算（$budgetDetail），未发送 API 请求；不会自动减少批次条数。" +
                        " batchRows=${batch.size}, startRow=${i + 1}, " +
                        "estimatedPromptTokens=${batchSelection.estimatedInputTokens}, " +
                        "estimatedOutputTokens=${batchSelection.estimatedOutputTokens}, " +
                        "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                        "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}"
                )
            }
            val batchContext = contextForSelection
            var recoverySummary = TranslationRecoverySummary(false, null, 0)
            val translated = try {
                translateBatchWithAdaptiveRecovery(
                    lines = batch,
                    context = batchContext,
                    isAcceptableTranslation = subtitleTranslationAcceptor(srcLang, tgtLang),
                    acceptAfterRecovery = subtitleShortUntranslatedAcceptor(srcLang, tgtLang),
                    deferInvalidRowsToCaller = true,
                    retryMalformedBatch = { rows, cueContext ->
                        translateBatch(
                            rows, srcLang, tgtLang, cueContext,
                            lookaheadSource = lookaheadSource,
                            videoTitle = videoTitle,
                            jsonArrayResponse = true,
                        )
                    },
                    onRecoverySummary = { recoverySummary = it },
                    splitOnPersistentFormatFailure = true,
                ) { rows, cueContext ->
                    translateBatch(
                        rows, srcLang, tgtLang, cueContext,
                        lookaheadSource = lookaheadSource,
                        videoTitle = videoTitle,
                    )
                }
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    val adaptiveFailure = (error as? IllegalStateException)?.let { failure ->
                        translationBatchFailureKind(failure) != null ||
                            isClassifiedTranslationBatchFailure(failure) ||
                            isTranslationRecoveryAttemptLimit(failure)
                    } == true
                    val failureKinds = if (adaptiveFailure) {
                        recoverySummary.failureKinds +
                            listOfNotNull((error as? IllegalStateException)?.let(::translationBatchFailureKind))
                    } else {
                        emptySet()
                    }
                    batchLearning.recordOriginalBatchFailure(
                        limitingReasons = batchSelection.limitingReasons,
                        failureKinds = failureKinds,
                    )
                    val performance = batchLearning.performanceSnapshot()
                    android.util.Log.w(
                        "TranslationAPI",
                        "Original subtitle batch failed: model=${model.take(100)}, " +
                            "rows=${batch.size}, rowCap=$FIXED_API_TRANSLATION_BATCH_ROWS, " +
                            "boundedRepair=$adaptiveFailure, limitingReasons=${batchSelection.limitingReasons}, " +
                            "failureKinds=$failureKinds, " +
                            "largestSuccessfulChunkRows=${recoverySummary.largestSuccessfulBatchRows ?: -1}, " +
                            "recoveryAttempts=${recoverySummary.attempts}, " +
                            "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                            "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}, " +
                            "failureType=${TranslationFailureDiagnosticPolicy.classify(error)}, " +
                            "httpStatus=${TranslationFailureDiagnosticPolicy.httpStatus(error)}, " +
                            "batchSuccessRate=${"%.1f".format(java.util.Locale.ROOT, performance.successRatePercent)}%, " +
                            "successfulBatches=${performance.successfulBatchCount}/${performance.sampledBatchCount}, " +
                            "splitRecoveries=${performance.splitRecoveryCount}, " +
                            "failedBatches=${performance.failedBatchCount}",
                    )
                }
                throw error
            }
            val isAccepted = subtitleTranslationAcceptor(srcLang, tgtLang)
            val isAcceptedAfterRecovery = subtitleShortUntranslatedAcceptor(srcLang, tgtLang)
            val allRowsAccepted = batch.indices.all { index ->
                val translatedRow = translated.getOrNull(index).orEmpty()
                batch[index].isBlank() ||
                    isAccepted(batch[index], translatedRow) ||
                    isAcceptedAfterRecovery(batch[index], translatedRow)
            }
            batchLearning.recordOriginalBatchSuccess(
                limitingReasons = batchSelection.limitingReasons,
                hadRecoverableFailure = recoverySummary.hadRecoverableFailure,
                recoveredBySplitting = recoverySummary.recoveredBySplitting,
                failureKinds = recoverySummary.failureKinds,
                allRowsAccepted = allRowsAccepted,
            )
            out += translated
            i = batchSelection.endExclusive
        }
        return out
    }

    private suspend fun translateBatch(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>? = null,
        forceTranslate: Boolean = false,
        lookaheadSource: List<String> = emptyList(),
        videoTitle: String? = null,
        jsonArrayResponse: Boolean = false,
    ): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API Key 为空，请在 API 管理中配置私有密钥")
        }
        val batchLearning = batchLearningFor(srcLang, tgtLang)
        val prompts = buildOpenAiSubtitlePrompts(
            lines, srcLang, tgtLang, context, lookaheadSource, videoTitle, forceTranslate,
            jsonArrayResponse,
        )
        val sys = prompts.system
        val user = prompts.user
        val learningSnapshot = batchLearning.snapshot()
        val estimatedInputTokens = ApiSubtitleBatchPolicy.estimatePromptTokens(
            sys, user, learningSnapshot.inputTokenMultiplier,
        )
        if (estimatedInputTokens > learningSnapshot.inputBudgetTokens) {
            throw IllegalStateException(
                "API_INPUT_BUDGET_EXCEEDED: estimatedPromptTokens=$estimatedInputTokens, " +
                    "budgetTokens=${learningSnapshot.inputBudgetTokens}"
            )
        }
        val estimatedOutputTokens = batchLearning.adjustedOutputEstimate(lines, tgtLang)
        val params = ChatCompletionParamsPolicy.forModel(
            baseUrl, model, estimatedOutputTokens, learningSnapshot.outputBudgetTokens,
        )
        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        params.temperature?.let { payload.put("temperature", it) }
        payload.put(if (params.useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", params.maxTokens)
        val apiResponse = requestTranslationContent(payload)
        batchLearning.recordUsage(
            estimatedInputTokens = estimatedInputTokens,
            estimatedOutputTokens = estimatedOutputTokens,
            actualInputTokens = apiResponse.usage.promptTokens,
            actualOutputTokens = apiResponse.usage.outputTokens,
            latencyMs = apiResponse.elapsedMs,
            batchRows = lines.size,
        )
        val performance = batchLearning.performanceSnapshot()
        if (ChatCompletionParamsPolicy.isLengthTruncation(apiResponse.usage.finishReason)) {
            throw IllegalStateException(
                "finish_reason=length；模型输出达到本批 token 上限，固定批次达到模型输出 Token 上限；不会自动改变后续批次条数"
            )
        }
        val content = apiResponse.content
        val translated = try {
            parseNumbered(content, lines.size)
        } catch (error: Exception) {
            android.util.Log.w(
                "TranslationAPI",
                "Subtitle response parse failed: model=${model.take(100)}, expectedRows=${lines.size}, " +
                    "contentChars=${content.length}, contentLines=${content.lines().size}, " +
                    "errorType=${error.javaClass.simpleName}, detail=${error.message.orEmpty().take(180)}",
            )
            throw error
        }
        val nonBlankRows = translated.count { it.isNotBlank() }
        val echoedRows = translated.indices.count { index ->
            translated[index].isNotBlank() &&
                SubtitleTranslationQualityPolicy.isSourceEcho(lines[index], translated[index], srcLang)
        }
        android.util.Log.d(
            "TranslationAPI",
            "Subtitle response parsed: model=${model.take(100)}, expectedRows=${lines.size}, " +
                "parsedRows=${translated.size}, nonBlankRows=$nonBlankRows, sourceEchoRows=$echoedRows",
        )
        android.util.Log.d(
            "TranslationAPI",
            "Batch usage: model=${model.take(100)}, rows=${lines.size}, " +
                "estimatedPromptTokens=$estimatedInputTokens, actualPromptTokens=${apiResponse.usage.promptTokens ?: -1}, " +
                "estimatedOutputTokens=$estimatedOutputTokens, actualOutputTokens=${apiResponse.usage.outputTokens ?: -1}, " +
                "maxOutputTokens=${params.maxTokens}, latencyMs=${apiResponse.elapsedMs}, " +
                    "rollingAverageLatencyMs=${performance.averageLatencyMs}, " +
                    "p90LatencyMs=${performance.p90LatencyMs}, " +
                    "averageRowsPerMinute=${performance.averageRowsPerMinute.toLong()}, " +
                    "batchSuccessRate=${"%.1f".format(java.util.Locale.ROOT, performance.successRatePercent)}%, " +
                    "successfulBatches=${performance.successfulBatchCount}/${performance.sampledBatchCount}, " +
                    "splitRecoveries=${performance.splitRecoveryCount}, " +
                    "failedBatches=${performance.failedBatchCount}",
        )
        return translated
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

private fun buildGeminiSubtitlePrompts(
    lines: List<String>,
    srcLang: String,
    tgtLang: String,
    context: Pair<List<String>, List<String>>?,
    lookaheadSource: List<String>,
    videoTitle: String?,
    forceTranslate: Boolean,
): ApiSubtitlePrompts {
        val src = LangMap.display(srcLang)
        val tgt = SubtitleTranslationPromptPolicy.targetLanguageLabel(tgtLang)
        val numbered = lines.mapIndexed { i, l -> "$i|$l" }.joinToString("\n")
        val sys = buildString {
            append("Translate each numbered subtitle line from $src to $tgt. ")
            append("Reply with exactly `index|translation` per line, nothing else. Include every input index exactly once; never return progress/status text. ")
            append("Do not copy ordinary source-language words or sentences unchanged. If wording is ambiguous, provide your best natural $tgt translation instead of echoing the source. ")
            append("Preserve unchanged only content that naturally remains the same, such as proper names, brand names, acronyms, URLs, email addresses and standalone numbers. ")
            if (forceTranslate) {
                append("This is a recovery request for a cue that was previously empty or echoed from the source. Translate its ordinary words into $tgt now; do not repeat the source-language wording. ")
            }
            append("These cues are consecutive dialogue from one video: keep pronouns, references and tone coherent across lines. ")
            append("A sentence may be split across adjacent subtitle cues; use the reference cues to understand fragments, but translate only the current cue assigned to each index. ")
            append("Do not omit, summarize, invent facts or move content between subtitle rows. ")
            append("Render the same person, place or recurring term identically every time it appears. ")
            append("The source text may come from automatic speech recognition: it can contain misrecognized words, missing punctuation and wrong sentence breaks. Infer the intended wording from context and translate the intended meaning; never translate an obvious misrecognition literally, and never add content that the speech does not support. ")
            videoTitle?.trim()?.take(120)?.takeIf { it.isNotBlank() }?.let { title ->
                append("Video title (for topic and terminology only): $title. ")
            }
            append(SubtitleTranslationPromptPolicy.guidelinesForTarget(tgtLang))
            append("Prefer natural spoken $tgt that fits a subtitle's length. Preserve tone.")
        }
        val user = buildContextUserPrompt(context, numbered, lookaheadSource)
            ?: numbered
    return ApiSubtitlePrompts(sys, user)
}

/** Google Gemini */
class GeminiEngine(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val batchLearningStore: ApiSubtitleBatchLearningStore? = null,
) : TranslationEngine {
    private val endpointBase = baseUrl.trimEnd('/')
    override val id = "gemini"
    override val displayName = model.ifEmpty { "Gemini" }
    private val batchLearningByLanguage =
        java.util.concurrent.ConcurrentHashMap<String, ApiSubtitleBatchLearningState>()

    private fun batchLearningFor(srcLang: String, tgtLang: String): ApiSubtitleBatchLearningState {
        val scope = "${srcLang.trim().lowercase(java.util.Locale.ROOT)}->" +
            tgtLang.trim().lowercase(java.util.Locale.ROOT)
        return batchLearningByLanguage.computeIfAbsent(scope) {
            ApiSubtitleBatchLearningState(model, endpointBase, batchLearningStore, scope)
        }
    }

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
            if (ok) {
                val modelId = m.optString("name").removePrefix("models/")
                val inputLimit = m.optInt("inputTokenLimit").takeIf { it > 0 }
                val outputLimit = m.optInt("outputTokenLimit").takeIf { it > 0 }
                if (inputLimit != null || outputLimit != null) {
                    val capability = ApiSubtitleBatchPolicy.registerProviderLimits(
                        model = modelId,
                        contextWindowTokens = null,
                        maxOutputTokens = outputLimit,
                        maxInputTokens = inputLimit,
                        providerKey = endpointBase,
                    )
                    batchLearningStore?.saveCapability(modelId, capability)
                }
                out += modelId
            }
        }
        return out.sorted()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> =
        translateWithContext(lines, srcLang, tgtLang, null, emptyList(), null)

    override suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        initialContext: Pair<List<String>, List<String>>?,
        lookaheadSource: List<String>,
        videoTitle: String?,
    ): List<String> {
        val out = mutableListOf<String>()
        val batchLearning = batchLearningFor(srcLang, tgtLang)
        val usableContextRow = subtitleTranslationAcceptor(srcLang, tgtLang)
        val usableRecoveredNameRow = subtitleShortUntranslatedAcceptor(srcLang, tgtLang)
        val coroutineContext = currentCoroutineContext()
        var i = 0
        while (i < lines.size) {
            val learningSnapshot = batchLearning.snapshot()
            fun contextForBatch(candidateLines: List<String>): Pair<List<String>, List<String>>? =
                TranslationContextPolicy.contextForBatchWithTermContinuity(
                    initialContext = initialContext,
                    completedSources = lines.subList(0, i),
                    completedTranslations = out,
                    currentSourceCues = candidateLines,
                    sourceLanguage = srcLang,
                    checkActive = { coroutineContext.ensureActive() },
                    isUsable = { source, translation ->
                        usableContextRow(source, translation) ||
                            usableRecoveredNameRow(source, translation)
                    },
                )
            // Build bounded history once per outer batch; preflight and send reuse it.
            val contextForSelection = contextForBatch(
                lines.subList(i, minOf(lines.size, i + FIXED_API_TRANSLATION_BATCH_ROWS))
            )
            val batchSelection = ApiSubtitleBatchPolicy.selectBatch(
                lines = lines,
                startIndex = i,
                model = model,
                targetLanguage = tgtLang,
                learning = learningSnapshot,
                providerKey = endpointBase,
                inputTokenEstimator = { candidateLines ->
                    val prompt = buildGeminiSubtitlePrompts(
                        candidateLines, srcLang, tgtLang, contextForSelection,
                        lookaheadSource, videoTitle, forceTranslate = false,
                    )
                    ApiSubtitleBatchPolicy.estimatePromptTokens(
                        prompt.system, prompt.user, learningSnapshot.inputTokenMultiplier,
                    )
                },
            )
            val batch = lines.subList(i, batchSelection.endExclusive)
            android.util.Log.d(
                "GeminiEngine",
                "Subtitle batch selected: model=${model.take(100)}, startRow=${i + 1}, " +
                    "rows=${batch.size}, rowCap=$FIXED_API_TRANSLATION_BATCH_ROWS, " +
                    "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                    "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}, " +
                    "estimatedInputTokens=${batchSelection.estimatedInputTokens}, " +
                    "estimatedOutputTokens=${batchSelection.estimatedOutputTokens}, " +
                    "maxOutputTokens=${batchSelection.maxOutputTokens}",
            )
            if (batchSelection.batchExceedsInputBudget ||
                batchSelection.batchExceedsOutputBudget
            ) {
                val budgetDetail = when {
                    batchSelection.batchExceedsInputBudget && batchSelection.batchExceedsOutputBudget ->
                        "输入和输出 Token 预算均不足"
                    batchSelection.batchExceedsInputBudget -> "输入 Token 预算不足"
                    else -> "输出 Token 预算不足"
                }
                throw IllegalStateException(
                    "固定 ${FIXED_API_TRANSLATION_BATCH_ROWS} 条批次超出当前模型 Token 预算（$budgetDetail），未发送 API 请求；不会自动减少批次条数。" +
                        " batchRows=${batch.size}, startRow=${i + 1}, " +
                        "estimatedPromptTokens=${batchSelection.estimatedInputTokens}, " +
                        "estimatedOutputTokens=${batchSelection.estimatedOutputTokens}, " +
                        "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                        "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}"
                )
            }
            val batchContext = contextForSelection
            var recoverySummary = TranslationRecoverySummary(false, null, 0)
            val translated = try {
                translateBatchWithAdaptiveRecovery(
                    lines = batch,
                    context = batchContext,
                    isAcceptableTranslation = subtitleTranslationAcceptor(srcLang, tgtLang),
                    acceptAfterRecovery = subtitleShortUntranslatedAcceptor(srcLang, tgtLang),
                    deferInvalidRowsToCaller = true,
                    retryMalformedBatch = { rows, cueContext ->
                        translateBatch(
                            rows, srcLang, tgtLang, cueContext,
                            forceTranslate = true,
                            lookaheadSource = lookaheadSource,
                            videoTitle = videoTitle,
                        )
                    },
                    onRecoverySummary = { recoverySummary = it },
                    splitOnPersistentFormatFailure = true,
                ) { rows, cueContext ->
                    translateBatch(
                        rows, srcLang, tgtLang, cueContext,
                        lookaheadSource = lookaheadSource,
                        videoTitle = videoTitle,
                    )
                }
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    val adaptiveFailure = (error as? IllegalStateException)?.let { failure ->
                        translationBatchFailureKind(failure) != null ||
                            isClassifiedTranslationBatchFailure(failure) ||
                            isTranslationRecoveryAttemptLimit(failure)
                    } == true
                    val failureKinds = if (adaptiveFailure) {
                        recoverySummary.failureKinds +
                            listOfNotNull((error as? IllegalStateException)?.let(::translationBatchFailureKind))
                    } else {
                        emptySet()
                    }
                    batchLearning.recordOriginalBatchFailure(
                        limitingReasons = batchSelection.limitingReasons,
                        failureKinds = failureKinds,
                    )
                    val performance = batchLearning.performanceSnapshot()
                    android.util.Log.w(
                        "GeminiEngine",
                        "Original subtitle batch failed: model=${model.take(100)}, " +
                            "rows=${batch.size}, rowCap=$FIXED_API_TRANSLATION_BATCH_ROWS, " +
                            "boundedRepair=$adaptiveFailure, limitingReasons=${batchSelection.limitingReasons}, " +
                            "failureKinds=$failureKinds, " +
                            "largestSuccessfulChunkRows=${recoverySummary.largestSuccessfulBatchRows ?: -1}, " +
                            "recoveryAttempts=${recoverySummary.attempts}, " +
                            "inputBudgetTokens=${learningSnapshot.inputBudgetTokens}, " +
                            "outputBudgetTokens=${learningSnapshot.outputBudgetTokens}, " +
                            "failureType=${TranslationFailureDiagnosticPolicy.classify(error)}, " +
                            "httpStatus=${TranslationFailureDiagnosticPolicy.httpStatus(error)}, " +
                            "batchSuccessRate=${"%.1f".format(java.util.Locale.ROOT, performance.successRatePercent)}%, " +
                            "successfulBatches=${performance.successfulBatchCount}/${performance.sampledBatchCount}, " +
                            "splitRecoveries=${performance.splitRecoveryCount}, " +
                            "failedBatches=${performance.failedBatchCount}",
                    )
                }
                throw error
            }
            val isAccepted = subtitleTranslationAcceptor(srcLang, tgtLang)
            val isAcceptedAfterRecovery = subtitleShortUntranslatedAcceptor(srcLang, tgtLang)
            val allRowsAccepted = batch.indices.all { index ->
                val translatedRow = translated.getOrNull(index).orEmpty()
                batch[index].isBlank() ||
                    isAccepted(batch[index], translatedRow) ||
                    isAcceptedAfterRecovery(batch[index], translatedRow)
            }
            batchLearning.recordOriginalBatchSuccess(
                limitingReasons = batchSelection.limitingReasons,
                hadRecoverableFailure = recoverySummary.hadRecoverableFailure,
                recoveredBySplitting = recoverySummary.recoveredBySplitting,
                failureKinds = recoverySummary.failureKinds,
                allRowsAccepted = allRowsAccepted,
            )
            out += translated
            i = batchSelection.endExclusive
        }
        return out
    }

    private suspend fun translateBatch(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>? = null,
        forceTranslate: Boolean = false,
        lookaheadSource: List<String> = emptyList(),
        videoTitle: String? = null,
    ): List<String> {
        if (normalizedApiKey.isBlank()) {
            throw IllegalStateException("Gemini API Key 为空，请在卡片右侧「•••」填入您的 API Key（可前往 Google AI Studio 免费获取）")
        }
        val batchLearning = batchLearningFor(srcLang, tgtLang)
        val prompts = buildGeminiSubtitlePrompts(
            lines, srcLang, tgtLang, context, lookaheadSource, videoTitle, forceTranslate,
        )
        val sys = prompts.system
        val user = prompts.user
        val configuredModel = model.trim().removePrefix("models/").ifBlank { FreeModelDefaults.GEMINI_MODEL }
        val payload = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$user"))))
        )
        val learningSnapshot = batchLearning.snapshot()
        val estimatedInputTokens = ApiSubtitleBatchPolicy.estimatePromptTokens(
            sys, user, learningSnapshot.inputTokenMultiplier,
        )
        if (estimatedInputTokens > learningSnapshot.inputBudgetTokens) {
            throw IllegalStateException(
                "API_INPUT_BUDGET_EXCEEDED: estimatedPromptTokens=$estimatedInputTokens, " +
                    "budgetTokens=${learningSnapshot.inputBudgetTokens}"
            )
        }
        val estimatedOutputTokens = batchLearning.adjustedOutputEstimate(lines, tgtLang)
        val requestStartedAtNanos = System.nanoTime()
        applyGeminiTranslationThinkingConfig(payload, configuredModel)
        applyGeminiTranslationOutputLimit(
            payload, configuredModel, estimatedOutputTokens, learningSnapshot.outputBudgetTokens,
        )
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
                applyGeminiTranslationOutputLimit(
                    it, fallbackModel, estimatedOutputTokens, learningSnapshot.outputBudgetTokens,
                )
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
        val finishReason = candidate.optString("finishReason")
        val usage = response.optJSONObject("usageMetadata")
        val elapsedMs = ((System.nanoTime() - requestStartedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        batchLearning.recordUsage(
            estimatedInputTokens = estimatedInputTokens,
            estimatedOutputTokens = estimatedOutputTokens,
            actualInputTokens = usage?.optInt("promptTokenCount")?.takeIf { it > 0 },
            actualOutputTokens = usage?.optInt("candidatesTokenCount")?.takeIf { it > 0 },
            latencyMs = elapsedMs,
            batchRows = lines.size,
        )
        val performance = batchLearning.performanceSnapshot()
        if (finishReason.equals("MAX_TOKENS", ignoreCase = true)) {
            throw IllegalStateException(
                "finish_reason=length；Gemini 输出达到本批 token 上限，固定批次达到模型输出 Token 上限；不会自动改变后续批次条数"
            )
        }
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
        val translated = parseNumbered(text, lines.size)
        android.util.Log.d(
            "GeminiEngine",
            "Batch usage: model=${configuredModel.take(100)}, rows=${lines.size}, " +
                "estimatedPromptTokens=$estimatedInputTokens, actualPromptTokens=${usage?.optInt("promptTokenCount") ?: -1}, " +
                "estimatedOutputTokens=$estimatedOutputTokens, actualOutputTokens=${usage?.optInt("candidatesTokenCount") ?: -1}, " +
                "maxOutputTokens=${ApiSubtitleBatchPolicy.outputLimitForModel(configuredModel, estimatedOutputTokens, endpointBase, learningSnapshot.outputBudgetTokens)}, " +
                "latencyMs=$elapsedMs, " +
                    "rollingAverageLatencyMs=${performance.averageLatencyMs}, " +
                    "p90LatencyMs=${performance.p90LatencyMs}, " +
                    "averageRowsPerMinute=${performance.averageRowsPerMinute.toLong()}, " +
                    "batchSuccessRate=${"%.1f".format(java.util.Locale.ROOT, performance.successRatePercent)}%, " +
                    "successfulBatches=${performance.successfulBatchCount}/${performance.sampledBatchCount}, " +
                    "splitRecoveries=${performance.splitRecoveryCount}, " +
                    "failedBatches=${performance.failedBatchCount}",
        )
        return translated
    }

    /**
     * Use each Gemini generation family's native thinking controls for subtitle translation.
     * Gemini 2.5 Flash/Lite can disable thinking via budget 0; 2.5 Pro cannot, so use its
     * minimum supported budget. Gemini 3+ uses thinkingLevel rather than thinkingBudget.
     */
    private fun applyGeminiTranslationOutputLimit(
        payload: JSONObject,
        modelId: String,
        estimatedOutputTokens: Int,
        outputBudgetTokens: Int,
    ) {
        val config = payload.optJSONObject("generationConfig") ?: JSONObject()
        config.put(
            "maxOutputTokens",
            ApiSubtitleBatchPolicy.outputLimitForModel(
                modelId, estimatedOutputTokens, endpointBase, outputBudgetTokens,
            ),
        )
        payload.put("generationConfig", config)
    }

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

    // Reuse one ML Kit translator over all batches when the online route is unavailable.
    private var offlineFallbackEngine: MlKitEngine? = null

    private fun offlineEngineForTask(): MlKitEngine = synchronized(this) {
        offlineFallbackEngine ?: MlKitEngine().also { offlineFallbackEngine = it }
    }

    override fun close() {
        val engine = synchronized(this) {
            offlineFallbackEngine.also { offlineFallbackEngine = null }
        }
        engine?.close()
    }

    override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
        if (!hasValidatedNetwork()) {
            return try {
                offlineEngineForTask().translate(lines, srcLang, tgtLang)
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
                offlineEngineForTask().translate(lines, srcLang, tgtLang)
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
    const val ID_LOCAL_HYMT = -24L

    /** All downloaded/on-device translation models are first-class translation engines. */
    fun isLocalTranslationEngine(activeId: Long): Boolean =
        activeId == ID_LOCAL_MLKIT ||
            activeId == ID_LOCAL_OPUS ||
            activeId == ID_LOCAL_SMALL100 ||
            activeId == ID_LOCAL_SINGLE_PAIR ||
            activeId == ID_LOCAL_HYMT

    fun localTranslationEngineName(activeId: Long): String? = when (activeId) {
        ID_LOCAL_MLKIT -> "ML Kit"
        ID_LOCAL_OPUS -> "OPUS-MT"
        ID_LOCAL_SMALL100 -> "SMaLL-100"
        ID_LOCAL_SINGLE_PAIR -> "单语言对翻译模型"
        ID_LOCAL_HYMT -> "HY-MT1.5 2-bit"
        else -> null
    }

    fun getBuiltinEngine(activeId: Long, context: Context): TranslationEngine? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return when (activeId) {
            ID_GOOGLE, ID_GOOGLE_ALT -> GoogleHybridEngine(context)
            ID_LOCAL_MLKIT -> MlKitEngine()
            ID_LOCAL_OPUS -> OpusMtEngine(context)
            ID_LOCAL_SMALL100 -> Small100Engine(context)
            ID_LOCAL_SINGLE_PAIR -> SinglePairTranslationEngine(context)
            ID_LOCAL_HYMT -> HyMtEngine(context)
            ID_MICROSOFT -> MicrosoftWebEngine()
            ID_BAIDU -> BaiduWebEngine()
            ID_DEEPL -> DeepLWebEngine()
            ID_FREE_GEMINI -> {
                val encKey = prefs.getString("free_gemini_key", "").orEmpty()
                val key = if (encKey.isNotBlank()) Crypto.decrypt(encKey) else FreeModelDefaults.DEFAULT_GEMINI_KEY
                val model = prefs.getString("free_gemini_model", FreeModelDefaults.GEMINI_MODEL) ?: FreeModelDefaults.GEMINI_MODEL
                GeminiEngine(
                    apiKey = key,
                    model = model,
                    baseUrl = FreeModelDefaults.GEMINI_BASE_URL,
                    batchLearningStore = ApiSubtitleBatchLearningStore(
                        appContext,
                        FreeModelDefaults.GEMINI_BASE_URL,
                    ),
                )
            }
            ID_FREE_DEEPSEEK -> {
                val encKey = prefs.getString("free_deepseek_key", "").orEmpty()
                val key = if (encKey.isNotBlank()) Crypto.decrypt(encKey) else FreeModelDefaults.DEFAULT_DEEPSEEK_KEY
                val baseUrl = prefs.getString("free_deepseek_base_url", FreeModelDefaults.DEEPSEEK_BASE_URL) ?: FreeModelDefaults.DEEPSEEK_BASE_URL
                val model = prefs.getString("free_deepseek_model", FreeModelDefaults.DEEPSEEK_MODEL) ?: FreeModelDefaults.DEEPSEEK_MODEL
                OpenAiCompatEngine(
                    baseUrl = baseUrl,
                    apiKey = key,
                    model = model,
                    batchLearningStore = ApiSubtitleBatchLearningStore(appContext, baseUrl),
                )
            }
            else -> null
        }
    }

    fun fromProfile(
        p: io.translatetube.local.data.ApiProfile,
        context: Context? = null,
    ): TranslationEngine {
        val key = Crypto.decrypt(p.apiKey)
        val appContext = context?.applicationContext
        return when (p.type) {
            "OPENAI" -> OpenAiCompatEngine(
                p.baseUrl,
                key,
                p.model,
                appContext?.let { ApiSubtitleBatchLearningStore(it, p.baseUrl) },
            )
            // Gemini does not expose a Base URL field in the UI. Ignore stale values from older
            // profiles so a Gemini API key is never sent to an OAuth-only Vertex/custom endpoint.
            "GEMINI" -> GeminiEngine(
                key,
                p.model,
                "https://generativelanguage.googleapis.com",
                appContext?.let {
                    ApiSubtitleBatchLearningStore(it, "https://generativelanguage.googleapis.com")
                },
            )
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
