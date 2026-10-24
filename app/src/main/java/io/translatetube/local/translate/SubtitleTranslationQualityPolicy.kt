package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale

/** Shared validation for live translation batches and restored subtitle caches. */
internal object SubtitleTranslationQualityPolicy {
    private val urlPattern = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val emailPattern = Regex(
        """^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$""",
        RegexOption.IGNORE_CASE,
    )

    fun isSourceEcho(sourceText: String?, translatedText: String?, sourceLanguage: String): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        val language = SourceLanguagePolicy.normalizeTag(sourceLanguage)
        if (SourceLanguagePolicy.isChinese(language)) return false

        // A cue with real words "translated" into only punctuation (for example "。") is a failed
        // translation. Treat it like an echoed source row so it is retried or left untranslated
        // instead of showing "。" as the subtitle and leaving nothing to dub.
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) return true

        // Numbers, symbol-only cues, URLs and email addresses are valid even when unchanged.
        // Treating them as failed translations can make a perfectly valid subtitle batch fail.
        if (isNonTranslatableCue(sourceText)) return false
        val source = normalize(sourceText)
        val translated = normalize(translatedText)
        return source.isNotEmpty() && source == translated
    }

    /**
     * True when the source contains letters/digits but the translation has none, e.g. "。" or "…".
     * Source cues that are themselves symbol-only (music notes, "...") are not affected.
     */
    fun isPunctuationOnlyTranslation(sourceText: String?, translatedText: String?): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        if (translatedText.any { it.isLetterOrDigit() }) return false
        return sourceText.any { it.isLetterOrDigit() }
    }

    private fun isNonTranslatableCue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.none { it.isLetter() } ||
            urlPattern.matches(trimmed) ||
            emailPattern.matches(trimmed)
    }

    /** Ignore case, whitespace and punctuation so formatting-only changes do not pass as translation. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
}
