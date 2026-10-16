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

    fun isChinese(tag: String): Boolean =
        normalizeTag(tag).substringBefore('-').lowercase(Locale.ROOT) in setOf("zh", "cmn", "yue")

    /** Empty string tells translation APIs to auto-detect instead of receiving a fake language ID. */
    fun sourceForTranslation(tag: String): String =
        normalizeTag(tag).takeUnless(::isUndetermined).orEmpty()
}
