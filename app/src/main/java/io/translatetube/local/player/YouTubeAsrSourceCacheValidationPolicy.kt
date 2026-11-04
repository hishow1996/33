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
            // An unknown hint is not evidence that an ASR transcript is wrong. Preserve the cache;
            // resolve its language from the text when possible rather than re-transcribing audio.
            return false
        }
        return YouTubeSubtitleSelectionPolicy.shouldInvalidateSubtitleSnapshot(
            expectedSourceLanguage = expectedSourceLanguage,
            detectedTextLanguage = detectedTextLanguage,
            savedSourceLanguage = savedSourceLanguage,
            allowSubtitleLanguageFallback = isCaptionCache,
        )
    }

    /**
     * After the cache passes validation, prefer detectable language from its actual text. This
     * avoids carrying a stale or generic saved language such as "auto" when the source-language
     * endpoint was unavailable, while still honoring a verified language when detection is weak.
     */
    fun resolvedCachedLanguage(
        expectedSourceLanguage: String?,
        detectedTextLanguage: String?,
        savedSourceLanguage: String?,
        isCaptionCache: Boolean,
    ): String? {
        val expected = YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(expectedSourceLanguage)
        val detected = YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(detectedTextLanguage)
        val saved = YouTubeSubtitleSelectionPolicy.normalizeLanguageCode(savedSourceLanguage)
        return when {
            detected != null && (isCaptionCache || expected == null || detected == expected) -> detected
            saved != null -> saved
            expected != null -> expected
            else -> detected
        }
    }
}
