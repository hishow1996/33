package io.translatetube.local.player

internal enum class PlaybackHintAction {
    RETRY,
    RELOAD,
    REPARSE,
    PLAY_ORIGINAL_ONLY,
    OPEN_SETTINGS,
}

internal data class PlaybackHint(
    val key: String,
    val message: String,
    val showSpinner: Boolean = false,
    val action: PlaybackHintAction? = null,
    val actionLabel: String? = null,
    val secondaryAction: PlaybackHintAction? = null,
    val secondaryActionLabel: String? = null,
    val priority: Int,
    val keepPlayerVisible: Boolean = true,
)

internal data class PlaybackFailure(
    val errorCode: Int,
    val errorCodeName: String,
    val httpStatusCode: Int?,
    val message: String,
    val isNetworkFailure: Boolean,
) {
    val isExpiredAddress: Boolean
        get() = httpStatusCode == 403 ||
            httpStatusCode == 410 ||
            message.contains("地址已失效") ||
            message.contains("签名链接已过期") ||
            message.contains("URL has expired", ignoreCase = true) ||
            message.contains("signed url expired", ignoreCase = true)
}

internal data class PlaybackHintInput(
    val screenEligible: Boolean = true,
    val isPlaying: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val isBuffering: Boolean = false,
    val bufferingDurationMs: Long = 0L,
    val loadRetryCount: Int = 0,
    val hasMedia: Boolean = false,
    val idleRecovery: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val recoveryFailure: String? = null,
    val httpStatusCode: Int? = null,
    val networkFailure: Boolean = false,
    val failureMessage: String? = null,
    val blockedMessage: String? = null,
    val blockedOffersOriginalOnly: Boolean = false,
    val noFirstFrameTimedOut: Boolean = false,
)

internal object PlaybackHintPolicy {
    const val MIN_HINT_DURATION_MS = 1_500L
    private const val MAX_DETAIL_CHARS = 140

    fun hintFor(input: PlaybackHintInput): PlaybackHint? {
        if (input.hasRenderedFirstFrame && input.isPlaying) return null
        if (!input.screenEligible) return null

        val failure = safeReason(input.failureMessage)
        val recoveryFailure = safeReason(input.recoveryFailure)

        return when {
            !input.recoveryFailure.isNullOrBlank() -> PlaybackHint(
                key = "recovery-failed",
                message = "视频加载失败：$recoveryFailure",
                action = PlaybackHintAction.REPARSE,
                actionLabel = "重试",
                priority = 120,
            )
            input.noFirstFrameTimedOut -> PlaybackHint(
                key = "first-frame-timeout",
                message = "视频迟迟没有画面",
                action = PlaybackHintAction.RELOAD,
                actionLabel = "重新加载",
                priority = 110,
            )
            input.recoveryInProgress -> PlaybackHint(
                key = "address-recovery",
                message = input.recoveryMessage?.takeIf { it.isNotBlank() }
                    ?: "视频地址已失效，正在重新获取…",
                showSpinner = true,
                priority = 105,
            )
            input.httpStatusCode == 403 || input.httpStatusCode == 410 -> PlaybackHint(
                key = "expired-address",
                message = "视频地址已失效，正在重新获取…",
                showSpinner = true,
                priority = 100,
            )
            input.networkFailure -> PlaybackHint(
                key = "network-failure",
                message = "网络连接失败，请检查网络",
                action = PlaybackHintAction.RETRY,
                actionLabel = "重试",
                priority = 95,
            )
            !input.failureMessage.isNullOrBlank() -> PlaybackHint(
                key = "load-failed",
                message = "视频加载失败：$failure",
                action = PlaybackHintAction.RETRY,
                actionLabel = "重试",
                priority = 90,
            )
            !input.blockedMessage.isNullOrBlank() -> PlaybackHint(
                key = "playback-blocked",
                message = safeReason(input.blockedMessage),
                showSpinner = input.blockedMessage.contains("准备中") ||
                    input.blockedMessage.contains("准备好后"),
                action = if (input.blockedOffersOriginalOnly) {
                    PlaybackHintAction.PLAY_ORIGINAL_ONLY
                } else {
                    PlaybackHintAction.OPEN_SETTINGS
                },
                actionLabel = if (input.blockedOffersOriginalOnly) "仅播放原声" else "去设置",
                secondaryAction = if (input.blockedOffersOriginalOnly) {
                    PlaybackHintAction.OPEN_SETTINGS
                } else {
                    null
                },
                secondaryActionLabel = if (input.blockedOffersOriginalOnly) "去设置" else null,
                priority = 85,
            )
            input.loadRetryCount > 0 && input.isBuffering -> PlaybackHint(
                key = "automatic-retry",
                message = "连接不稳定，正在重试（第 ${input.loadRetryCount} 次）",
                showSpinner = true,
                priority = 70,
            )
            input.isBuffering && input.bufferingDurationMs >= 5_000L -> PlaybackHint(
                key = "slow-buffering",
                message = "网络较慢，仍在加载…",
                showSpinner = true,
                priority = 60,
            )
            input.idleRecovery && input.hasMedia -> PlaybackHint(
                key = "restoring",
                message = "正在恢复播放…",
                showSpinner = true,
                priority = 55,
            )
            input.isBuffering -> PlaybackHint(
                key = "loading",
                message = "视频加载中…",
                showSpinner = true,
                priority = 50,
            )
            else -> null
        }
    }

    fun shouldKeepCurrent(
        current: PlaybackHint?,
        next: PlaybackHint?,
        elapsedSinceCurrentMs: Long,
    ): Boolean {
        if (current == null || elapsedSinceCurrentMs >= MIN_HINT_DURATION_MS) return false
        if (next != null && next.priority > current.priority) return false
        return true
    }

    fun safeReason(raw: String?): String {
        val safe = raw.orEmpty()
            .replace(Regex("(?i)https?://[^\\s\\\"'<>]+"), "视频地址")
            .replace(Regex("(?i)(sig(?:nature)?|token|expire|policy|key-pair-id)=([^&\\s]+)")) {
                "${it.groupValues[1]}=[已隐藏]"
            }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (safe.isEmpty()) return "播放器无法读取该视频流"
        return if (safe.length > MAX_DETAIL_CHARS) safe.take(MAX_DETAIL_CHARS) + "…" else safe
    }
}
