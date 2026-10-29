package io.translatetube.local.player

import io.translatetube.local.yt.YouTubeSubtitleSelectionPolicy

/**
 * Validates cached YouTube source rows without turning unavailable language metadata into a
 * needless ASR cache miss. Audio-transcript keys are already bound to video/audio identity and
 * ASR configuration; when the source-language hint is temporarily unavailable, a valid timeline
 * is safer to reuse than to discard and transcribe again.
 */
internal object YouTubeAsrSourceCacheValidationPolicy {
    fun shouldInvalidate(
        expectedSourceLanguage: String?,
        detectedTextLanguage: String?,
        savedSourceLanguage: String?,
        isCaptionCache: Boolean,
    ): Boolean {
        if (!isCaptionCache && YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(expectedSourceLanguage) == null) {
            // An unknown hint is not evidence that an ASR transcript is wrong. A known, conflicting
            // language still invalidates it below.
            return false
        }
        return YouTubeSubtitleSelectionPolicy.shouldInvalidateSubtitleSnapshot(
            expectedSourceLanguage = expectedSourceLanguage,
            detectedTextLanguage = detectedTextLanguage,
            savedSourceLanguage = savedSourceLanguage,
            allowSubtitleLanguageFallback = isCaptionCache,
        )
    }
}
