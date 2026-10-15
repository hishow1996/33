package io.translatetube.local.player

import java.io.IOException
import java.util.Locale
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** Shared bounded retry policy for remote audio and HLS segment downloads. */
internal object MediaDownloadRetryPolicy {
    private val retryableHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)

    /** Refresh a cached YouTube StreamInfo once when its signed media URL is stale. */
    fun shouldRefreshSignedStream(error: Throwable): Boolean {
        if (hasCancellationCause(error)) return false
        val chain = causeChain(error)
        val messages = chain.mapNotNull { it.message }.joinToString(" ")
        val statusCode = Regex("(?:HTTP\\s*|失败\\s*)[（(]?(\\d{3})")
            .find(messages)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
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

    fun shouldRetry(error: Throwable): Boolean {
        if (hasCancellationCause(error)) return false

        val chain = causeChain(error)
        val messages = chain.mapNotNull { it.message }.joinToString(" ")
        val statusCode = Regex("(?:HTTP\\s*|失败\\s*)[（(]?(\\d{3})")
            .find(messages)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

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
