package io.translatetube.local.player

/**
 * Restores only the WebView context belonging to the exact persisted webpage -> remote-media task.
 * The raw Cookie header is deliberately excluded; callers re-read it from WebView CookieManager.
 */
internal object PendingMediaResumeContextPolicy {
    fun pageReferrerForResume(
        sourceKey: String?,
        activeKey: String?,
        storedReferrer: String?,
        requestedTaskKey: String,
    ): String? {
        if (sourceKey.isNullOrBlank() || activeKey.isNullOrBlank()) return null
        if (!sourceKey.startsWith("webpage:") || !activeKey.startsWith("remote:")) return null
        if (requestedTaskKey != sourceKey && requestedTaskKey != activeKey) return null

        val referrer = storedReferrer
            ?.takeIf(::isHttpUrl)
            ?: sourceKey.removePrefix("webpage:").takeIf(::isHttpUrl)
        return referrer
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
}
