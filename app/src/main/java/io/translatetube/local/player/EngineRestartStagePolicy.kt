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
            return if (hasMissingTranslation) null else EngineRestartStage.DUBBING
        }

        return null
    }
}
