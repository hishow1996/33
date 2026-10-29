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
    fun importedTranslatedSubtitleSnapshotSurvivesIrrelevantTranslationConfigChange() {
        val previous = EngineConfigFingerprints(
            transcription = "asr-a",
            translation = "translator-a",
            dubbing = "tts-a",
        )
        val current = EngineConfigFingerprints(
            transcription = "asr-a",
            translation = "translator-b",
            dubbing = "tts-a",
        )
        val restartStage = EngineRestartStagePolicy.choose(
            previous = previous,
            current = current,
            processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
            ignoreTranscription = true,
            hasLines = true,
            sourceLanguageIsChinese = false,
        )

        assertTrue(restartStage == null)
        assertTrue(
            ReadySessionSnapshotReusePolicy.canRestoreComparableFallbackSnapshot(
                fallbackSnapshotAvailable = true,
                fallbackConfigComparable = true,
                fallbackRestartStage = restartStage,
            ),
        )
        assertFalse(
            ReadySessionSnapshotReusePolicy.shouldRegenerateDubbingForFallback(
                forceRegenerateDubbing = false,
                restoreReadySnapshot = false,
                hasReusableSnapshot = false,
                fallbackSnapshotAvailable = true,
                fallbackConfigComparable = true,
                fallbackRestartStage = restartStage,
                engineConfigChangedSinceLastSuccess = true,
            ),
        )
    }

    @Test
    fun actualDubbingConfigChangeStillRegeneratesFallbackSpeech() {
        val previous = EngineConfigFingerprints(
            transcription = "asr-a",
            translation = "translator-a",
            dubbing = "tts-a",
        )
        val current = previous.copy(dubbing = "tts-b")
        val restartStage = EngineRestartStagePolicy.choose(
            previous = previous,
            current = current,
            processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
            ignoreTranscription = true,
            hasLines = true,
            sourceLanguageIsChinese = true,
        )

        assertTrue(restartStage == EngineRestartStage.DUBBING)
        assertFalse(
            ReadySessionSnapshotReusePolicy.canRestoreComparableFallbackSnapshot(
                fallbackSnapshotAvailable = true,
                fallbackConfigComparable = true,
                fallbackRestartStage = restartStage,
            ),
        )
        assertTrue(
            ReadySessionSnapshotReusePolicy.shouldRegenerateDubbingForFallback(
                forceRegenerateDubbing = false,
                restoreReadySnapshot = false,
                hasReusableSnapshot = false,
                fallbackSnapshotAvailable = true,
                fallbackConfigComparable = true,
                fallbackRestartStage = restartStage,
                engineConfigChangedSinceLastSuccess = true,
            ),
        )
    }

    @Test
    fun reopeningLocalVideoCanRestoreItsSuccessfulImportedSubtitleResult() {
        assertTrue(
            ReadySessionSnapshotReusePolicy.isSnapshotFlowCompatible(
                snapshotFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                snapshotIgnoresTranscription = true,
                expectedFlows = setOf(
                    ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                    ProcessingFlow.IMPORTED_SUBTITLES,
                    ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ),
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
