package io.translatetube.local.player

/**
 * Keeps the one legacy checkpoint flag readable only for its version-7 digest and restore path.
 * A version-7 row marked as original-audio fallback is deliberately restored without a translation,
 * so normal stage-4 translation must generate a fresh Chinese cue.
 */
internal object InProgressCheckpointLegacyCompatibilityPolicy {
    private const val LEGACY_FALLBACK_VERSION = 7

    fun includesLegacyOriginalAudioFlag(version: Int): Boolean =
        version == LEGACY_FALLBACK_VERSION

    fun restoreTranslation(
        version: Int,
        rawTranslation: String?,
        legacyOriginalAudioFallback: Boolean,
    ): String? {
        val translation = rawTranslation?.takeIf { it.isNotBlank() } ?: return null
        return if (includesLegacyOriginalAudioFlag(version) && legacyOriginalAudioFallback) {
            null
        } else {
            translation
        }
    }
}
