package io.translatetube.local.translate

/** HTTP 错误响应，额外带上服务端建议的等待时间（Retry-After 头）。仍是 IllegalStateException，现有 catch 不受影响。 */
internal class TranslationHttpException(
    message: String,
    val retryAfterMs: Long?,
) : IllegalStateException(message)

/**
 * 翻译请求的重试节奏。
 * - 普通网络抖动：短等待重试 2 次（0.4s、0.9s），和以前一样。
 * - 服务端限流/过载（HTTP 429、503）：多等几次（2s、4s、8s、15s），并尊重 Retry-After；
 *   以前 429 只等不到 1.5 秒就整体失败，长视频翻到一半很容易被限流打断。
 * - 服务端要求等待超过 30 秒时不硬等，直接把错误交给用户。
 */
internal object TranslationRetryBackoffPolicy {
    const val MAX_NETWORK_RETRIES = 2
    const val MAX_THROTTLE_RETRIES = 4
    const val MAX_WAIT_MS = 30_000L
    private const val MAX_RETRY_AFTER_SECONDS = 3_600L
    private val networkBackoffMs = longArrayOf(400L, 900L)
    private val throttleBackoffMs = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)
    private val throttleStatusRegex = Regex("""\bHTTP\s+(429|503)\b""", RegexOption.IGNORE_CASE)

    /** Retry-After 头里的整数秒 → 毫秒；空值、日期格式、负数、乱码一律当作没有。 */
    fun parseRetryAfterMs(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        val seconds = trimmed.toLongOrNull() ?: return MAX_RETRY_AFTER_SECONDS * 1_000L
        return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
    }

    fun isThrottle(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(12).any { throttleStatusRegex.containsMatchIn(it.message.orEmpty()) }

    private fun retryAfterMs(error: Throwable): Long? =
        generateSequence(error) { it.cause }.take(12)
            .firstNotNullOfOrNull { (it as? TranslationHttpException)?.retryAfterMs }

    /**
     * 第 [retriesDone] + 1 次重试前要等多久；返回 null 表示不再重试。
     * [allowThrottleRetries] 为 false 时（免密钥网页接口）限流和普通错误一样只短重试 2 次，不去反复撞限流。
     */
    fun nextDelayMs(retriesDone: Int, error: Throwable, allowThrottleRetries: Boolean = true): Long? {
        if (retriesDone < 0 || !isRetryableTranslationFailure(error)) return null
        val throttle = allowThrottleRetries && isThrottle(error)
        val schedule = if (throttle) throttleBackoffMs else networkBackoffMs
        if (retriesDone >= schedule.size) return null
        val hint = retryAfterMs(error)
        if (hint != null && hint > MAX_WAIT_MS) return null
        return maxOf(schedule[retriesDone], hint ?: 0L).coerceAtMost(MAX_WAIT_MS)
    }
}
