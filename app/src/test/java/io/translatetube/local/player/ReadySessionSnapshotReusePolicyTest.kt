package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadySessionSnapshotReusePolicyTest {
    @Test
    fun forcedYouTubeSubtitleRefreshNeverUsesAnExactHistoricalSnapshot() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.canReuseExactSnapshot(
                canRestoreStoredResult = true,
                forceYouTubeSubtitleRefresh = true,
            ),
        )
    }

    @Test
    fun normalReopenCanReuseAnExactSuccessfulSnapshot() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.canReuseExactSnapshot(
                canRestoreStoredResult = true,
                forceYouTubeSubtitleRefresh = false,
            ),
        )
    }

    @Test
    fun explicitResumeOrImportedInputStillCannotUseAnImplicitSnapshot() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.canReuseExactSnapshot(
                canRestoreStoredResult = false,
                forceYouTubeSubtitleRefresh = false,
            ),
        )
    }
    @Test
    fun switchingAwayAndBackRestoresAnExactHistoricalSuccess() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.canRestoreMatchingHistoricalSnapshot(
                matchingSuccessfulSnapshot = true,
                forceYouTubeSubtitleRefresh = false,
            ),
        )
    }

    @Test
    fun exactHistoricalSnapshotCannotSkipExplicitYouTubeCaptionRediscovery() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.canRestoreMatchingHistoricalSnapshot(
                matchingSuccessfulSnapshot = true,
                forceYouTubeSubtitleRefresh = true,
            ),
        )
    }

    @Test
    fun missingHistoricalSnapshotFallsBackToStageSpecificRestart() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.canRestoreMatchingHistoricalSnapshot(
                matchingSuccessfulSnapshot = false,
                forceYouTubeSubtitleRefresh = false,
            ),
        )
    }

    @Test
    fun engineRollbackCannotReuseSnapshotFromDifferentProcessingFlow() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                snapshotIgnoresTranscription = true,
                expectedFlows = setOf(ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION),
                isYouTube = true,
            ),
        )
    }

    @Test
    fun matchingProcessingFlowCanReuseItsHistoricalSnapshot() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                snapshotIgnoresTranscription = false,
                expectedFlows = setOf(ProcessingFlow.LOCAL_SPEECH_RECOGNITION),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun genericHistoryLookupMayAcceptTheSnapshotOwnFlow() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                snapshotIgnoresTranscription = false,
                expectedFlows = null,
                isYouTube = false,
            ),
        )
    }

    @Test
    fun localAsrFlowCannotRestoreSnapshotThatIgnoredAsr() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                snapshotIgnoresTranscription = true,
                expectedFlows = setOf(ProcessingFlow.LOCAL_SPEECH_RECOGNITION),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun sourceFlowFilteringRejectsAWebSnapshotWhenOpeningLocalVideo() {
        assertFalse(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                snapshotIgnoresTranscription = false,
                expectedFlows = setOf(ProcessingFlow.LOCAL_SPEECH_RECOGNITION),
                isYouTube = false,
            ),
        )
    }

    @Test
    fun YouTubeCanRestoreEitherCaptionOrSpeechRecognitionFlowWhenItsMarkerMatches() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                snapshotIgnoresTranscription = false,
                expectedFlows = setOf(
                    ProcessingFlow.YOUTUBE_SUBTITLES,
                    ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                ),
                isYouTube = true,
            ),
        )
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                snapshotIgnoresTranscription = true,
                expectedFlows = setOf(
                    ProcessingFlow.YOUTUBE_SUBTITLES,
                    ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                ),
                isYouTube = true,
            ),
        )
    }

    @Test
    fun youtubeCaptionFlowRequiresAsrIgnoredSnapshot() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                snapshotIgnoresTranscription = true,
                expectedFlows = setOf(ProcessingFlow.YOUTUBE_SUBTITLES),
                isYouTube = true,
            ),
        )
        assertFalse(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                snapshotIgnoresTranscription = false,
                expectedFlows = setOf(ProcessingFlow.YOUTUBE_SUBTITLES),
                isYouTube = true,
            ),
        )
    }

}
