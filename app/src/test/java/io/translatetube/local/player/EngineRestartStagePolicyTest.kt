package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineRestartStagePolicyTest {
    private val oldConfig = EngineConfigFingerprints(
        transcription = "asr-a",
        translation = "translation-model-a",
        dubbing = "tts-a",
    )

    private fun changed(
        translation: String = oldConfig.translation,
        transcription: String = oldConfig.transcription,
        dubbing: String = oldConfig.dubbing,
    ) = EngineConfigFingerprints(transcription, translation, dubbing)

    @Test
    fun switchingApiModelsRestartsAtTranslationForYoutubeWebAndLocalFlows() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SUBTITLES,
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            ProcessingFlow.IMPORTED_SUBTITLES,
        )
        for (flow in flows) {
            assertEquals(
                "flow=$flow",
                EngineRestartStage.TRANSLATION,
                EngineRestartStagePolicy.choose(
                    previous = oldConfig,
                    current = changed(translation = "translation-model-b"),
                    processingFlow = flow,
                    ignoreTranscription = flow == ProcessingFlow.YOUTUBE_SUBTITLES,
                    hasLines = true,
                    sourceLanguageIsChinese = false,
                    hasMissingTranslation = false,
                ),
            )
        }
    }

    @Test
    fun chineseSourceRebuildsSpeechWithoutRequiringTranslation() {
        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(translation = "translation-model-b"),
                processingFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = true,
                hasMissingTranslation = false,
            ),
        )
    }

    @Test
    fun translatedSubtitleImportOnlyNeedsNewDubbingWhenTranslationSettingChanges() {
        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(translation = "translation-model-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
                hasMissingTranslation = false,
            ),
        )
    }

    @Test
    fun transcriptionSwitchRestartsAsrForYoutubeWebAndLocalSpeechFlows() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
        )
        for (flow in flows) {
            assertEquals(
                "flow=$flow",
                EngineRestartStage.TRANSCRIPTION,
                EngineRestartStagePolicy.choose(
                    previous = oldConfig,
                    current = changed(transcription = "asr-b"),
                    processingFlow = flow,
                    ignoreTranscription = false,
                    hasLines = true,
                    sourceLanguageIsChinese = false,
                    hasMissingTranslation = false,
                ),
            )
        }
    }

    @Test
    fun importedTranslatedSubtitlesIgnoreTranscriptionChanges() {
        assertNull(
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(transcription = "asr-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = true,
                hasLines = true,
                sourceLanguageIsChinese = false,
                hasMissingTranslation = false,
            ),
        )
    }

    @Test
    fun youtubeCaptionFlowDoesNotRestartTranscriptionForAsrOnlyChange() {
        assertNull(
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(transcription = "asr-b"),
                processingFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                ignoreTranscription = true,
                hasLines = true,
                sourceLanguageIsChinese = false,
                hasMissingTranslation = false,
            ),
        )
    }

    @Test
    fun missingTranslationMustBeResolvedBeforeTtsOnlyRestart() {
        assertNull(
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(dubbing = "tts-b"),
                processingFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
                hasMissingTranslation = true,
            ),
        )
    }
}
