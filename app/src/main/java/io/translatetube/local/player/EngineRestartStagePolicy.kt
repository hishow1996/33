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
    ): Boolean = when {
        // Before YouTube chooses between captions and ASR, translation/TTS changes still do not
        // affect metadata or caption discovery. Caption-mode changes are handled separately.
        phase == Phase.PARSING && processingFlow == ProcessingFlow.AUTO -> true
        phase in setOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING) &&
            processingFlow in setOf(
                ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                ProcessingFlow.WEB_SPEECH_RECOGNITION,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            ) -> true
        else -> false
    }

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
        // YouTube caption discovery is also source preparation. A TTS/translation change must not
        // cancel it before captions are loaded. Explicit subtitle-mode refresh bypasses this policy
        // in PlaybackService, so the user's request to choose another caption track is still honored.
        val canKeepCurrentSourceWork = canResumeFromSourcePreparation(phase, processingFlow) ||
            (phase == Phase.PARSING && processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES)
        if (!canKeepCurrentSourceWork) return false
        val sourceUsesTranscription = !ignoresTranscriptionConfig(
            processingFlow = processingFlow,
            isYouTube = processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES,
        )
        if (sourceUsesTranscription && previous.transcription != current.transcription) return false
        return previous.translation != current.translation || previous.dubbing != current.dubbing
    }

    /**
     * Existing PCM may be reused after a TTS change only when every upstream setting relevant to
     * the active flow is unchanged. YouTube-caption and imported-subtitle flows do not depend on
     * ASR; imported translated subtitles do not depend on the selected translation engine.
     *
     * The per-cue cache identity still verifies the actual translated text, speaker/voice, speed,
     * and cue window. This policy only prevents an irrelevant upstream preference change from
     * needlessly bypassing that exact-identity cache.
     */
    fun canReuseMatchingDubbingCache(
        previous: EngineConfigFingerprints,
        current: EngineConfigFingerprints,
        processingFlow: ProcessingFlow? = null,
    ): Boolean {
        if (previous.dubbing == current.dubbing) return false

        // Unknown flow is handled conservatively for older callers and incomplete sessions.
        val transcriptionMustMatch = processingFlow == null ||
            !ignoresTranscriptionConfig(
                processingFlow = processingFlow,
                isYouTube = processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES,
            )
        val translationMustMatch =
            processingFlow != ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES

        return (!transcriptionMustMatch || previous.transcription == current.transcription) &&
            (!translationMustMatch || previous.translation == current.translation)
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
    ): EngineRestartStage? {
        val importedFlow = processingFlow == ProcessingFlow.IMPORTED_SUBTITLES ||
            processingFlow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES

        val flowIgnoresTranscription = importedFlow ||
            processingFlow == ProcessingFlow.YOUTUBE_SUBTITLES
        if (!ignoreTranscription && !flowIgnoresTranscription &&
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
