package io.translatetube.local.player

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** Shared bounded retry policy for remote audio and HLS segment downloads. */
internal object MediaDownloadRetryPolicy {
    /** One initial request plus five bounded retries; DNS failures get a longer recovery window. */
    const val MAX_NETWORK_ATTEMPTS = 6

    private val retryableHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)
    private val httpStatusRegex = Regex(
        """(?i)(?:HTTP(?:/\d+(?:\.\d+)?)?(?:\s+(?:Error|错误))?\s*|失败\s*[（(]?\s*|(?:status(?:\s+code)?|code)\s*[:=]\s*)(\d{3})"""
    )

    /** Wrapper messages may describe an earlier attempt; trust the deepest HTTP failure first. */
    private fun deepestHttpStatus(chain: List<Throwable>): Int? {
        for (cause in chain.asReversed()) {
            val status = httpStatusRegex.findAll(cause.message.orEmpty())
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (status != null) return status
        }
        return null
    }

    /** Refresh a cached YouTube StreamInfo once when its signed media URL is stale. */
    fun shouldRefreshSignedStream(error: Throwable): Boolean {
        if (hasCancellationCause(error)) return false
        val chain = causeChain(error)
        val messages = chain.mapNotNull { it.message }.joinToString(" ")
        val statusCode = deepestHttpStatus(chain)
        if (statusCode != null && statusCode in setOf(401, 403, 404, 410)) return true
        // Protocol/error tokens must not depend on the device's default language.
        val normalized = messages.lowercase(Locale.ROOT)
        return messages.contains("过期") ||
            messages.contains("签名无效") ||
            normalized.contains("signature has expired") ||
            normalized.contains("request has expired") ||
            normalized.contains("invalid signature")
    }

    /** Decide whether a yt-dlp failure may proceed to another network download path. */
    fun shouldFallbackToStreaming(error: Throwable, coroutineActive: Boolean): Boolean =
        coroutineActive && !hasCancellationCause(error)

    /**
     * Back off longer for DNS failures because Android's resolver may need time to recover after
     * a mobile-network or VPN/DNS route change. Each retry creates a fresh OkHttp Call and thus
     * performs a fresh hostname lookup instead of reusing a failed connection.
     */
    fun retryDelayMillis(error: Throwable, failedAttemptNumber: Int): Long {
        val attempt = failedAttemptNumber.coerceIn(1, MAX_NETWORK_ATTEMPTS)
        val isDnsFailure = causeChain(error).any { it is UnknownHostException }
        val baseDelay = if (isDnsFailure) 2_000L else 750L
        val maxDelay = if (isDnsFailure) 20_000L else 8_000L
        val multiplier = 1L shl (attempt - 1).coerceAtMost(4)
        return (baseDelay * multiplier).coerceAtMost(maxDelay)
    }

    /** Keep progress diagnostics specific without exposing URLs, query strings or credentials. */
    fun retryReasonLabel(error: Throwable): String {
        val chain = causeChain(error)
        return when {
            chain.any { it is UnknownHostException } -> "DNS 域名解析暂时失败"
            chain.any { it is SocketTimeoutException } -> "媒体服务器响应超时"
            chain.any { it is ConnectException } -> "媒体服务器连接失败"
            chain.any { it is SSLException } -> "HTTPS 安全连接暂时失败"
            else -> "网络连接暂时不可用"
        }
    }

    fun shouldRetry(error: Throwable): Boolean {
        if (hasCancellationCause(error)) return false

        val chain = causeChain(error)
        val messages = chain.mapNotNull { it.message }.joinToString(" ")
        val statusCode = deepestHttpStatus(chain)

        // A known HTTP status is stronger evidence than the wrapper type: IOException(404) is
        // permanent and should not consume all retries, while IOException(429/503) is transient.
        if (statusCode != null) return statusCode in retryableHttpCodes
        if (chain.any { it is IOException }) return true
        return messages.contains("下载不完整") ||
            messages.contains("响应不完整") ||
            messages.contains("片段不完整")
    }

    private fun hasCancellationCause(error: Throwable): Boolean =
        causeChain(error).any { it is CancellationException }

    /** Throwable causes are normally linear, but hand-built wrappers can form cycles. */
    private fun causeChain(error: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }
}
