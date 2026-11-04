package io.translatetube.local.player

/**
 * An imported translated-subtitle file does not need the active translation engine.
 *
 * Only bypass the broad pipeline mutex when the canceled task was translating and no active or
 * retiring dubbing engine exists. Every other phase continues to serialize engine replacement
 * and synthesis to avoid concurrent TTS/native-resource use.
 */
internal object ImportedTranslatedSubtitleConcurrencyPolicy {
    fun canBypassPipelineMutex(
        previousPhase: Phase,
        hasActiveDubbingEngine: Boolean,
        hasRetiringDubbingEngine: Boolean,
    ): Boolean =
        previousPhase == Phase.TRANSLATING &&
            !hasActiveDubbingEngine &&
            !hasRetiringDubbingEngine
}
