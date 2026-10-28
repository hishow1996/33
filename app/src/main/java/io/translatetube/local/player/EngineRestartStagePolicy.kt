package io.translatetube.local.player

/**
 * Engine configuration domains used to decide which processing stage must be resumed.
 * The policy is source-independent: YouTube, webpage and local video share translation/dubbing
 * restart rules; imported subtitle flows and actual YouTube-caption flow ignore ASR changes.
 */
internal enum class EngineRestartStage {
    TRANSCRIPTION,
    TRANSLATION,
    DUBBING,
}

internal data class EngineConfigFingerprints(
    val transcription: String,
    val translation: String,
    val dubbing: String,
)

internal object EngineRestartStagePolicy {
    fun ignoresTranscriptionConfig(
        processingFlow: ProcessingFlow?,
        isYouTube: Boolean,
    ): Boolean = when (processingFlow) {
        ProcessingFlow.IMPORTED_SUBTITLES,
        ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> true
        ProcessingFlow.YOUTUBE_SUBTITLES -> isYouTube
        else -> false
    }

    /**
     * Source-preparation sessions may not have subtitle rows yet. A configuration switch during
     * audio extraction/transcription can still resume from this boundary without repeating video
     * metadata resolution or subtitle discovery. The source-audio cache/checkpoint decides whether
     * the actual audio needs to be prepared again.
     */
    fun canResumeFromSourcePreparation(
        phase: Phase,
        processingFlow: ProcessingFlow,
    ): Boolean =
        phase in setOf(Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING) &&
            processingFlow in setOf(
                ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                ProcessingFlow.WEB_SPEECH_RECOGNITION,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            )

    /**
     * A downstream translation/TTS change does not invalidate in-flight audio preparation or ASR.
     * Keep the current source stage running when the transcription configuration is unchanged;
     * those downstream settings are read when their own stages begin.
     */
    fun canDeferDownstreamChangeDuringSourcePreparation(
        phase: Phase,
        processingFlow: ProcessingFlow,
        previous: EngineConfigFingerprints,
        current: EngineConfigFingerprints,
    ): Boolean {
        if (!canResumeFromSourcePreparation(phase, processingFlow)) return false
        if (previous.transcription != current.transcription) return false
        return previous.translation != current.translation || previous.dubbing != current.dubbing
    }

    fun hasRelevantConfigChange(
        previous: EngineConfigFingerprints,
        current: EngineConfigFingerprints,
        processingFlow: ProcessingFlow?,
        ignoreTranscription: Boolean,
    ): Boolean {
        val transcriptionChanged = !ignoreTranscription &&
            !ignoresTranscriptionConfig(
                processingFlow = processingFlow,
                isYouTube = processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES,
            ) &&
            previous.transcription != current.transcription
        val importedTranslationIsAuthoritative =
            processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
        val translationChanged = !importedTranslationIsAuthoritative &&
            previous.translation != current.translation
        return transcriptionChanged || translationChanged ||
            previous.dubbing != current.dubbing
    }

    fun choose(
        previous: EngineConfigFingerprints,
        current: EngineConfigFingerprints,
        processingFlow: ProcessingFlow,
        ignoreTranscription: Boolean,
        hasLines: Boolean,
        sourceLanguageIsChinese: Boolean,
        hasMissingTranslation: Boolean,
    ): EngineRestartStage? {
        val importedFlow = processingFlow == ProcessingFlow.IMPORTED_SUBTITLES ||
            processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES

        if (!ignoreTranscription && !importedFlow &&
            previous.transcription != current.transcription
        ) {
            return EngineRestartStage.TRANSCRIPTION
        }

        if (
            processingFlow != ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES &&
            previous.translation != current.translation
        ) {
            if (!hasLines) return null
            return if (sourceLanguageIsChinese) {
                EngineRestartStage.DUBBING
            } else {
                EngineRestartStage.TRANSLATION
            }
        }

        if (previous.dubbing != current.dubbing) {
            if (!hasLines) return null
            // runPipeline preserves completed translations and resolves missing rows before stage 5.
            // A TTS-only change must not discard the source timeline or fall back to the public entry.
            return EngineRestartStage.DUBBING
        }

        return null
    }
}
