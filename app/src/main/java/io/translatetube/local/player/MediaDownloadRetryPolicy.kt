package io.translatetube.local.player

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Shared bounded retry policy for remote audio and HLS segment downloads. */
internal object MediaDownloadRetryPolicy {
    private val retryableHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)

    /** Refresh a cached YouTube StreamInfo once when its signed media URL is stale. */
    fun shouldRefreshSignedStream(error: Throwable): Boolean {
        if (error is CancellationException) return false
        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val statusCode = Regex("(?:HTTP\\s*|失败\\s*)[（(]?(\\d{3})")
            .find(messages)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (statusCode in setOf(401, 403, 404, 410)) return true
        val normalized = messages.lowercase()
        return messages.contains("过期") ||
            messages.contains("签名无效") ||
            normalized.contains("signature has expired") ||
            normalized.contains("request has expired") ||
            normalized.contains("invalid signature")
    }

    fun shouldRetry(error: Throwable): Boolean {
        if (error is CancellationException) return false
        if (error is IOException) return true

        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val statusCode = Regex("(?:HTTP\\s*|失败\\s*)[（(]?(\\d{3})")
            .find(messages)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (statusCode != null && statusCode in retryableHttpCodes) return true
        return messages.contains("下载不完整") ||
            messages.contains("响应不完整") ||
            messages.contains("片段不完整")
    }
}
