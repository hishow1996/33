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
    fun switchingVoicesWithinTheSameEngineRestartsOnlyDubbingAndCanReuseOnReturn() {
        val voiceA = EngineConfigFingerprints(
            transcription = "asr-a",
            translation = "translation-a",
            dubbing = "gemini|voice=kore|credential=key-a",
        )
        val voiceB = voiceA.copy(dubbing = "gemini|voice=puck|credential=key-a")

        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = voiceA,
                current = voiceB,
                processingFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
        assertEquals(true, EngineRestartStagePolicy.canReuseMatchingDubbingCache(voiceA, voiceB))
        assertEquals(true, EngineRestartStagePolicy.canReuseMatchingDubbingCache(voiceB, voiceA))
    }

    @Test
    fun switchingBetweenEnginesRestartsOnlyDubbingAndCanReuseEachEngineOnReturn() {
        val googleVoice = EngineConfigFingerprints(
            transcription = "asr-a",
            translation = "translation-a",
            dubbing = "google|default-voice|google-tts-v2",
        )
        val geminiVoice = googleVoice.copy(
            dubbing = "gemini|voice=kore|credential=key-a",
        )

        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = googleVoice,
                current = geminiVoice,
                processingFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
        assertEquals(true, EngineRestartStagePolicy.canReuseMatchingDubbingCache(googleVoice, geminiVoice))
        assertEquals(true, EngineRestartStagePolicy.canReuseMatchingDubbingCache(geminiVoice, googleVoice))
    }

    @Test
    fun matchingDubbingCacheIsReusableOnlyForDubbingOnlyChanges() {
        assertEquals(
            true,
            EngineRestartStagePolicy.canReuseMatchingDubbingCache(
                oldConfig,
                changed(dubbing = "gemini-tts-v3-puck"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canReuseMatchingDubbingCache(
                oldConfig,
                changed(translation = "translation-model-b", dubbing = "gemini-tts-v3-puck"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canReuseMatchingDubbingCache(
                oldConfig,
                changed(transcription = "asr-b", dubbing = "gemini-tts-v3-puck"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canReuseMatchingDubbingCache(oldConfig, oldConfig),
        )
    }

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
            ),
        )
    }

    @Test
    fun translatedSubtitleImportIgnoresTranslationModelChanges() {
        assertNull(
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(translation = "translation-model-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.hasRelevantConfigChange(
                previous = oldConfig,
                current = changed(translation = "translation-model-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
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
                ),
            )
        }
    }

    @Test
    fun sourcePreparationCanResumeBeforeSubtitleRowsExist() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
        )
        for (flow in flows) {
            for (phase in listOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING)) {
                assertEquals(
                    "source preparation should be resumable for $flow at $phase",
                    true,
                    EngineRestartStagePolicy.canResumeFromSourcePreparation(phase, flow),
                )
            }
        }
        for (phase in listOf(Phase.PARSING, Phase.TRANSCRIBING)) {
            assertEquals(
                "YouTube caption flow must not use speech-source deferral at $phase",
                false,
                EngineRestartStagePolicy.canResumeFromSourcePreparation(
                    phase,
                    ProcessingFlow.YOUTUBE_SUBTITLES,
                ),
            )
            assertEquals(
                "imported translated subtitles must not use speech-source deferral at $phase",
                false,
                EngineRestartStagePolicy.canResumeFromSourcePreparation(
                    phase,
                    ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ),
            )
        }
        assertEquals(
            "YouTube AUTO flow can defer downstream changes only during parsing",
            true,
            EngineRestartStagePolicy.canResumeFromSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.AUTO,
            ),
        )
        for (phase in listOf(Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING)) {
            assertEquals(
                "AUTO must have selected a concrete source path before $phase",
                false,
                EngineRestartStagePolicy.canResumeFromSourcePreparation(
                    phase,
                    ProcessingFlow.AUTO,
                ),
            )
        }
    }

    @Test
    fun transcriptionSwitchCanChooseTranscriptionBeforeTheFirstRowExists() {
        for (flow in listOf(
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
        )) {
            assertEquals(
                "flow=$flow",
                EngineRestartStage.TRANSCRIPTION,
                EngineRestartStagePolicy.choose(
                    previous = oldConfig,
                    current = changed(transcription = "asr-b"),
                    processingFlow = flow,
                    ignoreTranscription = false,
                    hasLines = false,
                    sourceLanguageIsChinese = false,
                ),
            )
        }
    }

    @Test
    fun geminiCredentialChangeResumesAtDubbingAcrossAllVideoSources() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SUBTITLES,
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            ProcessingFlow.IMPORTED_SUBTITLES,
            ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
        )
        for (flow in flows) {
            val current = changed(dubbing = "gemini-key-b")
            val ignoreTranscription = flow == ProcessingFlow.YOUTUBE_SUBTITLES ||
                flow == ProcessingFlow.IMPORTED_SUBTITLES ||
                flow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
            assertEquals(
                "config change should be relevant for $flow",
                true,
                EngineRestartStagePolicy.hasRelevantConfigChange(
                    previous = oldConfig,
                    current = current,
                    processingFlow = flow,
                    ignoreTranscription = ignoreTranscription,
                ),
            )
            assertEquals(
                "flow=$flow",
                EngineRestartStage.DUBBING,
                EngineRestartStagePolicy.choose(
                    previous = oldConfig,
                    current = current,
                    processingFlow = flow,
                    ignoreTranscription = ignoreTranscription,
                    hasLines = true,
                    sourceLanguageIsChinese = true,
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
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
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
            ),
        )
    }

    @Test
    fun youtubeCaptionFlowIgnoresAsrChangeEvenIfCallerDoesNotSetIgnoreFlag() {
        assertNull(
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(transcription = "asr-b"),
                processingFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
    }

    @Test
    fun switchingToGoogleOrGeminiTtsResumesAtDubbingAcrossAllSourceFlows() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SUBTITLES,
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            ProcessingFlow.IMPORTED_SUBTITLES,
            ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
        )
        val targetTtsFingerprints = listOf(
            "google-tts-v2",
            "gemini-tts-v3-kore-credential-b",
        )

        for (flow in flows) {
            val ignoreTranscription = flow == ProcessingFlow.YOUTUBE_SUBTITLES ||
                flow == ProcessingFlow.IMPORTED_SUBTITLES ||
                flow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
            for (targetTts in targetTtsFingerprints) {
                val current = changed(dubbing = targetTts)
                assertEquals(
                    "TTS change must be recognized for flow=$flow target=$targetTts",
                    true,
                    EngineRestartStagePolicy.hasRelevantConfigChange(
                        previous = oldConfig,
                        current = current,
                        processingFlow = flow,
                        ignoreTranscription = ignoreTranscription,
                    ),
                )
                assertEquals(
                    "TTS change must resume at stage 5 for flow=$flow target=$targetTts",
                    EngineRestartStage.DUBBING,
                    EngineRestartStagePolicy.choose(
                        previous = oldConfig,
                        current = current,
                        processingFlow = flow,
                        ignoreTranscription = ignoreTranscription,
                        hasLines = true,
                        sourceLanguageIsChinese = false,
                    ),
                )
            }
        }
    }

    @Test
    fun ttsOnlyChangeResumesAtDubbingWhenSourceRowsExist() {
        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(dubbing = "tts-b"),
                processingFlow = ProcessingFlow.WEB_SPEECH_RECOGNITION,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
    }
    @Test
    fun importedTranslatedFlowDoesNotTreatAsrChangesAsRelevant() {
        assertEquals(
            false,
            EngineRestartStagePolicy.hasRelevantConfigChange(
                previous = oldConfig,
                current = changed(transcription = "asr-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
            ),
        )
    }

    @Test
    fun asrChangesAreIrrelevantForYoutubeCaptionsAndImportedSubtitleFlows() {
        for (flow in listOf(
            ProcessingFlow.YOUTUBE_SUBTITLES,
            ProcessingFlow.IMPORTED_SUBTITLES,
            ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
        )) {
            assertEquals(
                "flow=$flow",
                false,
                EngineRestartStagePolicy.hasRelevantConfigChange(
                    previous = oldConfig,
                    current = changed(transcription = "asr-b"),
                    processingFlow = flow,
                    ignoreTranscription = false,
                ),
            )
        }
    }

    @Test
    fun normalSpeechFlowTreatsAsrChangesAsRelevant() {
        assertEquals(
            true,
            EngineRestartStagePolicy.hasRelevantConfigChange(
                previous = oldConfig,
                current = changed(transcription = "asr-b"),
                processingFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                ignoreTranscription = false,
            ),
        )
    }

    @Test
    fun importedTranslatedFlowStillTreatsDubbingChangesAsRelevant() {
        assertEquals(
            true,
            EngineRestartStagePolicy.hasRelevantConfigChange(
                previous = oldConfig,
                current = changed(dubbing = "tts-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
            ),
        )
    }

    @Test
    fun importedTranslatedFlowRestartsOnlyDubbingWhenTtsChanges() {
        assertEquals(
            EngineRestartStage.DUBBING,
            EngineRestartStagePolicy.choose(
                previous = oldConfig,
                current = changed(dubbing = "tts-b"),
                processingFlow = ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                ignoreTranscription = false,
                hasLines = true,
                sourceLanguageIsChinese = false,
            ),
        )
    }

    @Test
    fun importedOriginalSubtitlesStillTreatTranslationModelChangesAsRelevant() {
        assertEquals(
            true,
            EngineRestartStagePolicy.hasRelevantConfigChange(
                previous = oldConfig,
                current = changed(translation = "translation-model-b"),
                processingFlow = ProcessingFlow.IMPORTED_SUBTITLES,
                ignoreTranscription = false,
            ),
        )
    }

    @Test
    fun importedSubtitleFlowsIgnoreAsrForEverySourceType() {
        for (flow in listOf(
            ProcessingFlow.IMPORTED_SUBTITLES,
            ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
        )) {
            assertEquals(
                true,
                EngineRestartStagePolicy.ignoresTranscriptionConfig(
                    processingFlow = flow,
                    isYouTube = false,
                ),
            )
        }
    }

    @Test
    fun youtubeCaptionFlowIgnoresAsrOnlyForYoutubeSources() {
        assertEquals(
            true,
            EngineRestartStagePolicy.ignoresTranscriptionConfig(
                processingFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                isYouTube = true,
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.ignoresTranscriptionConfig(
                processingFlow = ProcessingFlow.YOUTUBE_SUBTITLES,
                isYouTube = false,
            ),
        )
    }

    @Test
    fun speechRecognitionFlowDependsOnTranscriptionConfig() {
        assertEquals(
            false,
            EngineRestartStagePolicy.ignoresTranscriptionConfig(
                processingFlow = ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                isYouTube = false,
            ),
        )
    }

    @Test
    fun downstreamChangesDoNotRestartAnUnchangedTranscriptionStage() {
        val flows = listOf(
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
            ProcessingFlow.WEB_SPEECH_RECOGNITION,
            ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
        )
        for (flow in flows) {
            for (phase in listOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING)) {
                assertEquals(
                    "translation change should not cancel active source work: flow=$flow phase=$phase",
                    true,
                    EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                        phase,
                        flow,
                        oldConfig,
                        changed(translation = "translation-model-b"),
                    ),
                )
                assertEquals(
                    "TTS change should not cancel active source work: flow=$flow phase=$phase",
                    true,
                    EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                        phase,
                        flow,
                        oldConfig,
                        changed(dubbing = "tts-b"),
                    ),
                )
            }
        }
    }

    @Test
    fun youtubeAutoParsingCanDeferOnlyDownstreamEngineChanges() {
        assertEquals(
            true,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.AUTO,
                oldConfig,
                changed(translation = "translation-model-b"),
            ),
        )
        assertEquals(
            true,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.AUTO,
                oldConfig,
                changed(dubbing = "tts-b"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.AUTO,
                oldConfig,
                changed(transcription = "asr-b", translation = "translation-model-b"),
            ),
        )
    }

    @Test
    fun sourceStageDeferralDoesNotHideTranscriptionOrSubtitleModeChanges() {
        assertEquals(
            false,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.TRANSCRIBING,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                oldConfig,
                changed(transcription = "asr-b", translation = "translation-model-b"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                oldConfig,
                changed(transcription = "asr-b", dubbing = "tts-b"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.TRANSCRIBING,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                oldConfig,
                changed(translation = "translation-model-b"),
            ),
        )
        assertEquals(
            true,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                oldConfig,
                changed(translation = "translation-model-b"),
            ),
        )
        assertEquals(
            true,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.PARSING,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                oldConfig,
                changed(transcription = "asr-b", dubbing = "google-tts-v2"),
            ),
        )
        assertEquals(
            false,
            EngineRestartStagePolicy.canDeferDownstreamChangeDuringSourcePreparation(
                Phase.TRANSLATING,
                ProcessingFlow.WEB_SPEECH_RECOGNITION,
                oldConfig,
                changed(translation = "translation-model-b"),
            ),
        )
    }

}
