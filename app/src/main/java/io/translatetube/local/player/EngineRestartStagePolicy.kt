package io.translatetube.local.player

/**
 * Engine configuration domains used to decide which processing stage must be resumed.
 * The policy is source-independent: YouTube, webpage and local video share translation/dubbing
 * restart rules; only YouTube-caption flow can ignore ASR changes.
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

        if (previous.translation != current.translation) {
            if (!hasLines) return null
            return if (
                sourceLanguageIsChinese ||
                processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES
            ) EngineRestartStage.DUBBING else EngineRestartStage.TRANSLATION
        }

        if (previous.dubbing != current.dubbing) {
            if (!hasLines) return null
            return if (hasMissingTranslation) null else EngineRestartStage.DUBBING
        }

        return null
    }
}
