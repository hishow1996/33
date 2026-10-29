package io.translatetube.local.player

/**
 * Identifies the source-caption timeline without tying it to downstream translation/TTS settings.
 * A stale YouTube caption source must invalidate its results across engine configurations, while
 * preserving snapshots from another caption track or an imported-subtitle workflow.
 */
internal object ReadySessionSnapshotInvalidationPolicy {
    fun isYouTubeCaptionFlow(flow: ProcessingFlow): Boolean =
        flow == ProcessingFlow.YOUTUBE_SUBTITLES || flow == ProcessingFlow.AUTO

    fun sameYouTubeCaptionSource(
        saved: PlayerUiState,
        invalid: PlayerUiState,
    ): Boolean {
        if (!isYouTubeCaptionFlow(saved.processingFlow) ||
            !isYouTubeCaptionFlow(invalid.processingFlow) ||
            saved.lines.size != invalid.lines.size ||
            saved.lines.isEmpty()
        ) return false
        return saved.lines.indices.all { index ->
            val left = saved.lines[index]
            val right = invalid.lines[index]
            left.startMs == right.startMs &&
                left.endMs == right.endMs &&
                left.text == right.text
        }
    }
}
