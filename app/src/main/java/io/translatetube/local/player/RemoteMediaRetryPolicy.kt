package io.translatetube.local.player

/** Decides when a stale page-extracted media URL should be re-resolved from its source page. */
internal object RemoteMediaRetryPolicy {
    fun shouldRefreshFromPage(error: Throwable): Boolean {
        if (error is kotlinx.coroutines.CancellationException) return false
        val detail = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase(java.util.Locale.ROOT)
        return listOf(
            "服务器返回了网页或 json",
            "服务器返回了登录页或错误文本",
            "链接可能过期或需要登录",
            "http 401",
            "http 403",
            "http 404",
            "http 410",
            "request has expired",
            "signature has expired",
            "invalid signature",
            "access denied",
            "unauthorized",
            "forbidden",
            "expired",
        ).any(detail::contains)
    }
}
