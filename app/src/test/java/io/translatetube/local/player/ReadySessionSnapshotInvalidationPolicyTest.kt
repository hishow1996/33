package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadySessionSnapshotInvalidationPolicyTest {
    private fun state(
        flow: ProcessingFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
        sourceLang: String = "ko",
        text: String = "안녕하세요",
        translated: String? = "你好",
        startMs: Long = 0L,
        endMs: Long = 1200L,
    ) = PlayerUiState(
        phase = Phase.READY,
        key = "youtube:test",
        sourceLang = sourceLang,
        processingFlow = flow,
        lines = listOf(SubLine(0, startMs, endMs, text, translated, -1)),
    )

    @Test
    fun sameCaptionTimelineIsMatchedAcrossTranslationAndTtsResults() {
        assertTrue(
            ReadySessionSnapshotInvalidationPolicy.sameYouTubeCaptionSource(
                saved = state(translated = "你好"),
                invalid = state(translated = "您好", sourceLang = "ko"),
            ),
        )
    }

    @Test
    fun differentCaptionTextOrTimingIsPreserved() {
        assertFalse(
            ReadySessionSnapshotInvalidationPolicy.sameYouTubeCaptionSource(
                saved = state(text = "안녕하세요"),
                invalid = state(text = "감사합니다"),
            ),
        )
        assertFalse(
            ReadySessionSnapshotInvalidationPolicy.sameYouTubeCaptionSource(
                saved = state(endMs = 1200L),
                invalid = state(endMs = 1500L),
            ),
        )
    }

    @Test
    fun importedSubtitleResultsAreNeverInvalidatedAsYoutubeCaptionSource() {
        assertFalse(
            ReadySessionSnapshotInvalidationPolicy.sameYouTubeCaptionSource(
                saved = state(flow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES),
                invalid = state(),
            ),
        )
    }

    @Test
    fun noEmptyTimelineCanMatchForBulkInvalidation() {
        assertFalse(
            ReadySessionSnapshotInvalidationPolicy.sameYouTubeCaptionSource(
                saved = state(),
                invalid = state().copy(lines = emptyList()),
            ),
        )
    }
}
