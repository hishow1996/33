package io.translatetube.local.player

/**
 * Restores only the WebView context belonging to the exact persisted webpage -> remote-media task.
 * The raw Cookie header is deliberately excluded; callers re-read it from WebView CookieManager.
 */
internal object PendingMediaResumeContextPolicy {
    /** Failed sessions must be restarted, not merely reattached to their stale ERROR UI. */
    fun shouldReconnectToLiveTask(
        livePhase: Phase,
        liveKey: String,
        requestedTaskKey: String,
        savedSourceKey: String?,
        savedActiveKey: String?,
    ): Boolean {
        if (livePhase == Phase.IDLE || livePhase == Phase.ERROR || liveKey.isBlank()) return false
        val savedTaskMatchesLive = !savedSourceKey.isNullOrBlank() &&
            !savedActiveKey.isNullOrBlank() &&
            liveKey == savedActiveKey &&
            (requestedTaskKey == savedSourceKey || requestedTaskKey == savedActiveKey)
        return liveKey == requestedTaskKey || savedTaskMatchesLive
    }

    /** Failed sessions must be restarted, not merely reattached to their stale ERROR UI. */
    fun shouldReconnectToLiveTask(
        livePhase: Phase,
        liveKey: String,
        requestedTaskKey: String,
        savedSourceKey: String?,
        savedActiveKey: String?,
    ): Boolean {
        if (livePhase == Phase.IDLE || livePhase == Phase.ERROR || liveKey.isBlank()) return false
        val savedTaskMatchesLive = !savedSourceKey.isNullOrBlank() &&
            !savedActiveKey.isNullOrBlank() &&
            liveKey == savedActiveKey &&
            (requestedTaskKey == savedSourceKey || requestedTaskKey == savedActiveKey)
        return liveKey == requestedTaskKey || savedTaskMatchesLive
    }

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
