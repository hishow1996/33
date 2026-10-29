package io.translatetube.local.player

/**
 * A playback session is reusable as a successful translated-dubbing result only when its
 * translations and dubbing output are complete. READY alone can also mean a partial fallback.
 */
internal object ReadySessionCompletionPolicy {
    fun hasCompleteTranslations(
        sourceLanguageIsChinese: Boolean,
        hasUntranslatedLines: Boolean,
    ): Boolean = sourceLanguageIsChinese || !hasUntranslatedLines

    fun canRegisterSuccessfulResult(
        phase: Phase,
        lineCount: Int,
        dubbingOn: Boolean,
        dubbingHasErrors: Boolean,
        waitingForTranscriptionEngine: Boolean,
        waitingForDubbingEngine: Boolean,
        translationsComplete: Boolean,
    ): Boolean =
        phase == Phase.READY &&
            lineCount > 0 &&
            dubbingOn &&
            !dubbingHasErrors &&
            !waitingForTranscriptionEngine &&
            !waitingForDubbingEngine &&
            translationsComplete
}
