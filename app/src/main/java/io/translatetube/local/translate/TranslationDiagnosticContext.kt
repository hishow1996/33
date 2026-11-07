package io.translatetube.local.translate

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One translation operation owns a diagnostic ID and a bounded timeline of transport events.
 * A ThreadLocal context element carries the same operation through the existing suspend engine
 * APIs and their synchronous OkHttp response callbacks without changing translation behavior.
 */
internal class TranslationDiagnosticContext(
    val diagnosticId: String,
) {
    private val events = CopyOnWriteArrayList<String>()

    fun recordHttpResponse(
        method: String,
        route: String,
        statusCode: Int,
        requestId: String?,
        contentType: String?,
        responseBytes: Long,
        retryAfterHeader: String?,
        retryAfterMs: Long?,
        rateLimitHeaders: String,
        bodySummary: String,
    ) {
        events += buildString {
            append("HTTP_RESPONSE")
            append(" method=").append(method.take(12))
            append(" route=").append(route.take(320))
            append(" status=").append(statusCode)
            append(" requestId=").append(requestId.orEmpty().ifBlank { "未提供" }.take(100))
            append(" contentType=").append(contentType.orEmpty().ifBlank { "未提供" }.take(120))
            append(" responseBytes=").append(responseBytes.coerceAtLeast(0L))
            append(" retryAfterHeader=").append(retryAfterHeader.orEmpty().ifBlank { "未提供" }.take(80))
            append(" retryAfterMs=").append(retryAfterMs?.toString() ?: "未提供")
            append(" rateLimitHeaders=").append(rateLimitHeaders.ifBlank { "未提供" }.take(600))
            append(" bodySummary=").append(bodySummary.take(1_000))
        }
        trim()
    }

    fun recordRetry(
        retryNumber: Int,
        waitMs: Long,
        failureType: String,
        failureMessage: String,
    ) {
        events += "TRANSPORT_RETRY #$retryNumber waitMs=${waitMs.coerceAtLeast(0L)} " +
            "failureType=${failureType.take(80)} reason=${failureMessage.take(220)}"
        trim()
    }

    fun recordParserEvent(parser: String, result: String, reason: String = "") {
        events += "RESPONSE_PARSER parser=${parser.take(100)} result=${result.take(80)} reason=${reason.take(240)}"
        trim()
    }

    /**
     * One line per batch-level request made while a single batch was being recovered. Kept apart
     * from [events] because those are trimmed to the most recent ones, and with a long split
     * sequence the early (largest) attempts are exactly what a failure report needs.
     */
    private val recoveryAttempts = CopyOnWriteArrayList<String>()

    fun recordRecoveryAttempt(rows: Int, kind: String, outcome: String) {
        recoveryAttempts += "#${recoveryAttempts.size + 1} ${kind} 行数=$rows 结果=${outcome.take(160)}"
        while (recoveryAttempts.size > 80) recoveryAttempts.removeAt(0)
    }

    /** Row counts in request order plus each outcome; empty when no batch needed recovery. */
    fun recoveryAttemptSummary(): String {
        if (recoveryAttempts.isEmpty()) return "本次没有记录到批次请求尝试"
        val all = recoveryAttempts.toList()
        val sizes = all.joinToString("→") { line ->
            line.substringAfter("行数=").substringBefore(' ')
        }
        return "共记录 ${all.size} 次批次请求；行数顺序：$sizes\n" + all.takeLast(20).joinToString("\n")
    }

    /**
     * Short one-line form of the most recent batch attempts, safe to append to an exception
     * message. Exported reports truncate the long diagnostic sections, but never the root cause
     * message, so the reason each attempt failed has to travel there.
     */
    fun recoveryAttemptBrief(maxAttempts: Int = 3, maxChars: Int = 420): String {
        val recent = recoveryAttempts.toList().takeLast(maxAttempts)
        if (recent.isEmpty()) return ""
        return recent.joinToString("；").take(maxChars)
    }

    /** Source text of every single cue the provider's safety filter refused to translate. */
    private val contentFilteredCues = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun recordContentFilteredCue(sourceText: String) {
        contentFilteredCues += sourceText.trim()
    }

    fun isContentFilteredCue(sourceText: String): Boolean = sourceText.trim() in contentFilteredCues

    fun hasContentFilteredCues(): Boolean = contentFilteredCues.isNotEmpty()

    fun snapshot(maxChars: Int = 6_000): String {
        if (events.isEmpty()) return "本次没有记录到 HTTP 响应或网络重试事件（可能是本地引擎、预检阶段失败，或请求在连接前终止）"
        val limit = maxChars.coerceIn(500, 12_000)
        val all = events.takeLast(24).joinToString("\n")
        return if (all.length <= limit) all else "（传输轨迹已截断，保留最近事件）\n" + all.takeLast(limit)
    }

    private fun trim() {
        while (events.size > 48) events.removeAt(0)
    }
}

internal object TranslationDiagnosticContextHolder {
    private val current = ThreadLocal<TranslationDiagnosticContext?>()

    fun current(): TranslationDiagnosticContext? = current.get()

    suspend fun <T> withDiagnosticContext(
        context: TranslationDiagnosticContext,
        block: suspend () -> T,
    ): T = withContext(current.asContextElement(context)) {
        block()
    }
}
