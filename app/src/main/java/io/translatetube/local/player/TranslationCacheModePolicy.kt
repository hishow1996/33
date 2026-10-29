package io.translatetube.local.player

/**
 * Builds the source-dependent part of translation cache identity.
 *
 * Downloaded YouTube captions are independent of the ASR engine. Their source rows are validated
 * separately before a translation cache is trusted. Web/local speech-recognition results must
 * include the complete ASR identity so results from different recognizers cannot be mixed.
 */
internal object TranslationCacheModePolicy {
    fun baseModeAwareKey(
        baseTranslationCacheKey: String,
        hasImportedLines: Boolean,
        isYouTubeCaptionSource: Boolean,
        asrCacheIdentity: String,
    ): String = when {
        hasImportedLines -> baseTranslationCacheKey
        isYouTubeCaptionSource -> baseTranslationCacheKey
        else -> "$baseTranslationCacheKey|asr-$asrCacheIdentity-timeline-v5"
    }
}
