package io.translatetube.local.player

/** 点播放被拦住时给用户看的原因；[offersOriginalOnly] 为 true 时界面提供"仅播放原声"按钮。 */
internal data class PlaybackBlockedHint(
    val message: String,
    val offersOriginalOnly: Boolean,
)

/**
 * 与 PlaybackService.isDubbingPlaybackBlocked 使用同一组条件：
 * 以前这些情况下点播放键会被静默忽略，页面没有任何说明，用户只看到"点了没反应"。
 */
internal object PlaybackBlockedHintPolicy {
    private const val MAX_DETAIL_CHARS = 120

    fun hintFor(
        waitingForTranscriptionEngine: Boolean,
        dubbingOn: Boolean,
        waitingForDubbingEngine: Boolean,
        dubbingHasErrors: Boolean,
        phaseIsError: Boolean,
        ttsEngineName: String,
        failureDetail: String?,
    ): PlaybackBlockedHint? {
        val engine = ttsEngineName.trim().ifEmpty { "配音引擎" }
        val detail = failureDetail?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > MAX_DETAIL_CHARS) it.take(MAX_DETAIL_CHARS) + "…" else it }
        return when {
            waitingForTranscriptionEngine -> PlaybackBlockedHint(
                "视频暂时不能播放：转写引擎还没准备好。请先在设置里下载或选择转写引擎。",
                offersOriginalOnly = false,
            )
            dubbingOn && waitingForDubbingEngine -> PlaybackBlockedHint(
                "视频暂时不能播放：$engine 还没准备好。请先在设置里检查配音引擎，或选择「仅播放原声」直接观看。",
                offersOriginalOnly = true,
            )
            dubbingOn && dubbingHasErrors -> PlaybackBlockedHint(
                "配音合成出错，视频已暂停" + (detail?.let { "：$it" } ?: "") +
                    "。可以到设置里检查配音引擎后重新处理，或选择「仅播放原声」直接观看。",
                offersOriginalOnly = true,
            )
            dubbingOn && phaseIsError -> PlaybackBlockedHint(
                "处理过程出错，视频暂不能播放" + (detail?.let { "：$it" } ?: "") + "。请先重试处理。",
                offersOriginalOnly = false,
            )
            else -> null
        }
    }
}
