package io.translatetube.local.translate

import java.util.Locale

/**
 * Canonicalizes language tags before they cross provider boundaries.
 *
 * UI sentinels such as "auto" and ASR hints such as "und-Latn" are not language codes and must
 * be omitted from source-language request fields. All normalization is locale-independent.
 */
internal object SourceLanguagePolicy {
    private val undeterminedPrimaryTags = setOf("auto", "und", "unknown", "undefined")

    fun normalizeTag(tag: String): String {
        val raw = tag.trim().replace('_', '-')
        if (raw.isEmpty()) return ""
        val parts = raw.split('-')
        if (parts.any { it.isEmpty() }) return raw.lowercase(Locale.ROOT)

        var extensionsStarted = false
        return parts.mapIndexed { index, part ->
            when {
                index == 0 -> {
                    if (part.length == 1) extensionsStarted = true
                    part.lowercase(Locale.ROOT)
                }
                extensionsStarted -> part.lowercase(Locale.ROOT)
                part.length == 1 && part.all { it.isLetterOrDigit() } -> {
                    extensionsStarted = true
                    part.lowercase(Locale.ROOT)
                }
                part.length == 4 && part.all { it.isLetter() } -> {
                    val lower = part.lowercase(Locale.ROOT)
                    lower.substring(0, 1).uppercase(Locale.ROOT) + lower.substring(1)
                }
                (part.length == 2 && part.all { it.isLetter() }) ||
                    (part.length == 3 && part.all { it.isDigit() }) -> part.uppercase(Locale.ROOT)
                else -> part.lowercase(Locale.ROOT)
            }
        }.joinToString("-")
    }

    fun isUndetermined(tag: String): Boolean {
        val normalized = normalizeTag(tag)
        return normalized.isEmpty() ||
            normalized.substringBefore('-').lowercase(Locale.ROOT) in undeterminedPrimaryTags
    }

    /**
     * Canonical ISO-639 primary language code shared by local translation engines.
     *
     * Subtitle providers and ASR engines do not all emit ISO-639-1 tags: some return
     * ISO-639-2/3 codes (for example eng/jpn/kor/rus) or legacy bibliographic aliases
     * (for example ger/fre/chi). Normalize these before validating model support.
     */
    private val languageCodeAliases = mapOf(
        "alb" to "sq", "sqi" to "sq",
        "ara" to "ar",
        "ben" to "bn",
        "bul" to "bg",
        "cat" to "ca",
        "ces" to "cs", "cze" to "cs",
        "chi" to "zh", "zho" to "zh", "cmn" to "zh",
        "cym" to "cy", "wel" to "cy",
        "dan" to "da",
        "deu" to "de", "ger" to "de",
        "ell" to "el", "gre" to "el",
        "eng" to "en",
        "est" to "et",
        "fas" to "fa", "per" to "fa",
        "fin" to "fi",
        "fra" to "fr", "fre" to "fr",
        "glg" to "gl",
        "guj" to "gu",
        "heb" to "he", "iw" to "he",
        "hin" to "hi",
        "hrv" to "hr",
        "hun" to "hu",
        "ice" to "is", "isl" to "is",
        "ind" to "id", "in" to "id",
        "ita" to "it",
        "jpn" to "ja",
        "kan" to "kn",
        "kor" to "ko",
        "lav" to "lv",
        "lit" to "lt",
        "mal" to "ml",
        "mar" to "mr",
        "may" to "ms", "msa" to "ms",
        "nld" to "nl", "dut" to "nl",
        "nob" to "no", "nno" to "no", "nor" to "no", "nb" to "no", "nn" to "no",
        "pol" to "pl",
        "por" to "pt",
        "ron" to "ro", "rum" to "ro",
        "rus" to "ru",
        "slk" to "sk", "slo" to "sk",
        "slv" to "sl",
        "spa" to "es",
        "srp" to "sr",
        "swe" to "sv",
        "tam" to "ta",
        "tel" to "te",
        "tgl" to "tl", "fil" to "tl",
        "tha" to "th",
        "tur" to "tr",
        "ukr" to "uk",
        "urd" to "ur",
        "vie" to "vi",
        "yid" to "yi",
    )

    fun canonicalPrimaryCode(tag: String): String {
        val primary = normalizeTag(tag).substringBefore('-').lowercase(Locale.ROOT)
        return languageCodeAliases[primary] ?: primary
    }

    fun isChinese(tag: String): Boolean =
        canonicalPrimaryCode(tag) in setOf("zh", "yue")

    /** Empty string tells translation APIs to auto-detect instead of receiving a fake language ID. */
    fun sourceForTranslation(tag: String): String =
        normalizeTag(tag).takeUnless(::isUndetermined).orEmpty()
}
