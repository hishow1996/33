package io.translatetube.local.yt

import java.util.Locale

internal enum class YouTubeSubtitleLanguageMode(val preferenceValue: String) {
    PREFER_ENGLISH("english"),
    MATCH_ORIGINAL("original");

    companion object {
        fun fromPreference(value: String?): YouTubeSubtitleLanguageMode =
            values().firstOrNull { it.preferenceValue == value } ?: PREFER_ENGLISH
    }
}

/**
 * Selects YouTube caption tracks according to the user's subtitle-language preference.
 * Language tags are reduced to their primary language subtag (for example, ko-KR -> ko).
 */
internal object YouTubeSubtitleSelectionPolicy {
    const val PREF_KEY = "youtube_subtitle_language_mode"
    const val DEFAULT_MODE = "english"

    fun modeFromPreference(value: String?): YouTubeSubtitleLanguageMode =
        YouTubeSubtitleLanguageMode.fromPreference(value)
    private val undeterminedTags = setOf("und", "mul", "zxx", "auto", "unknown", "undefined")
    private val iso6393To6391 = mapOf(
        "eng" to "en", "kor" to "ko", "jpn" to "ja",
        "zho" to "zh", "chi" to "zh", "cmn" to "zh",
        "spa" to "es", "fra" to "fr", "fre" to "fr",
        "deu" to "de", "ger" to "de", "por" to "pt",
        "rus" to "ru", "ara" to "ar", "hin" to "hi",
        "tha" to "th", "vie" to "vi", "ind" to "id",
        "nld" to "nl", "dut" to "nl", "pol" to "pl",
        "tur" to "tr", "swe" to "sv", "dan" to "da",
        "fin" to "fi", "ukr" to "uk", "ell" to "el",
        "gre" to "el", "heb" to "he", "hun" to "hu",
        "ces" to "cs", "cze" to "cs", "ron" to "ro",
        "rum" to "ro", "ben" to "bn", "msa" to "ms",
        "may" to "ms", "nob" to "no", "nno" to "nn",
    )

    fun normalizeLanguageCode(tag: String?): String? {
        val primary = tag
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase(Locale.ROOT)
            ?: return null
        if (primary in undeterminedTags) return null
        val canonical = iso6393To6391[primary] ?: primary
        return canonical.takeIf {
            it.length in 2..3 && it.all { character -> character in 'a'..'z' }
        }
    }

    fun sourceLanguageHint(
        originalAudioLanguageTags: Iterable<String?>,
        streamLanguageTag: String?,
        fallbackAudioLanguageTags: Iterable<String?>,
        originalAudioTrackPresent: Boolean = originalAudioLanguageTags.any {
            normalizeLanguageCode(it) != null
        },
    ): String? {
        val originalLanguages = originalAudioLanguageTags
            .mapNotNull(::normalizeLanguageCode)
            .distinct()
        if (originalLanguages.size == 1) return originalLanguages.single()
        // Conflicting explicit ORIGINAL markers are ambiguous. Do not guess from stream order.
        if (originalLanguages.size > 1) return null

        normalizeLanguageCode(streamLanguageTag)?.let { return it }

        // If an ORIGINAL track exists but its locale is missing/undetermined, other tracks cannot
        // safely stand in for it: YouTube may expose a localized dub beside the original stream.
        if (originalAudioTrackPresent) return null

        // A fallback is safe only if all non-dubbed/unknown-type audio tracks agree.
        val fallbackLanguages = fallbackAudioLanguageTags
            .mapNotNull(::normalizeLanguageCode)
            .distinct()
        return fallbackLanguages.singleOrNull()
    }

    fun matchesSourceLanguage(sourceLanguageTag: String?, subtitleLanguageTag: String?): Boolean {
        val source = normalizeLanguageCode(sourceLanguageTag) ?: return false
        return normalizeLanguageCode(subtitleLanguageTag) == source
    }

    /**
     * Whether a saved subtitle-based YouTube success snapshot should be discarded before playback.
     * Strong text-language evidence and a saved language label must both agree with the source
     * audio when available. Completely unidentifiable snapshots are rebuilt rather than trusted.
     */
    fun shouldInvalidateSubtitleSnapshot(
        expectedSourceLanguage: String?,
        detectedTextLanguage: String?,
        savedSourceLanguage: String?,
        allowEnglishCaption: Boolean = false,
    ): Boolean {
        val expected = normalizeLanguageCode(expectedSourceLanguage)
        val detected = normalizeLanguageCode(detectedTextLanguage)
        val saved = normalizeLanguageCode(savedSourceLanguage)
        // English captions are intentionally valid in English-first mode, even when audio is Korean/Japanese.
        // Strong text evidence that conflicts with the stored language label still invalidates the cache.
        if (allowEnglishCaption && (detected == "en" || saved == "en")) {
            if (detected != null && saved != null && detected != saved) return true
            return false
        }
        if (expected == null) return true
        return (detected != null && detected != expected) ||
            (saved != null && saved != expected) ||
            (detected == null && saved == null)
    }

    /**
     * Matching-language captions always outrank another language, even when the matching track is
     * automatic and the unrelated track is manually authored. Within one language, manual captions
     * are preferred over automatically generated captions. Unknown source language never implies
     * English; in that case manual tracks retain the neutral fallback preference.
     */
    fun rank(
        sourceLanguageTag: String?,
        subtitleLanguageTag: String?,
        isAutoGenerated: Boolean,
        mode: YouTubeSubtitleLanguageMode = YouTubeSubtitleLanguageMode.MATCH_ORIGINAL,
    ): Int {
        val sourceKnown = normalizeLanguageCode(sourceLanguageTag) != null
        val matchesSource = matchesSourceLanguage(sourceLanguageTag, subtitleLanguageTag)
        val isEnglish = normalizeLanguageCode(subtitleLanguageTag) == "en"
        if (mode == YouTubeSubtitleLanguageMode.PREFER_ENGLISH) {
            return when {
                isEnglish && !isAutoGenerated -> 0
                isEnglish -> 1
                matchesSource && !isAutoGenerated -> 2
                matchesSource -> 3
                !isAutoGenerated -> 4
                else -> 5
            }
        }
        return when {
            matchesSource && !isAutoGenerated -> 0
            matchesSource -> 1
            !sourceKnown && !isAutoGenerated -> 0
            !sourceKnown -> 1
            !isAutoGenerated -> 2
            else -> 3
        }
    }
}
