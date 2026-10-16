package io.translatetube.local.subs

import java.util.Locale

/** Decodes common HTML entities in subtitle text after real markup tags have been removed. */
internal object SubtitleTextDecoder {
    private val namedEntities = mapOf(
        "amp" to "&",
        "apos" to "'",
        "nbsp" to " ",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "bull" to "•",
        "middot" to "·",
        "euro" to "€",
        "pound" to "£",
        "yen" to "¥",
        "cent" to "¢",
        "deg" to "°",
        "plusmn" to "±",
        "times" to "×",
        "divide" to "÷",
        "laquo" to "«",
        "raquo" to "»",
        // Common Latin-1 entities occur in web subtitles, especially French, Spanish and German.
        "iexcl" to "¡",
        "iquest" to "¿",
        "aacute" to "á",
        "Aacute" to "Á",
        "agrave" to "à",
        "Agrave" to "À",
        "acirc" to "â",
        "Acirc" to "Â",
        "auml" to "ä",
        "Auml" to "Ä",
        "aring" to "å",
        "Aring" to "Å",
        "atilde" to "ã",
        "Atilde" to "Ã",
        "aelig" to "æ",
        "AElig" to "Æ",
        "ccedil" to "ç",
        "Ccedil" to "Ç",
        "eacute" to "é",
        "Eacute" to "É",
        "egrave" to "è",
        "Egrave" to "È",
        "ecirc" to "ê",
        "Ecirc" to "Ê",
        "euml" to "ë",
        "Euml" to "Ë",
        "iacute" to "í",
        "Iacute" to "Í",
        "igrave" to "ì",
        "Igrave" to "Ì",
        "icirc" to "î",
        "Icirc" to "Î",
        "iuml" to "ï",
        "Iuml" to "Ï",
        "ntilde" to "ñ",
        "Ntilde" to "Ñ",
        "oacute" to "ó",
        "Oacute" to "Ó",
        "ograve" to "ò",
        "Ograve" to "Ò",
        "ocirc" to "ô",
        "Ocirc" to "Ô",
        "ouml" to "ö",
        "Ouml" to "Ö",
        "otilde" to "õ",
        "Otilde" to "Õ",
        "oslash" to "ø",
        "Oslash" to "Ø",
        "uacute" to "ú",
        "Uacute" to "Ú",
        "ugrave" to "ù",
        "Ugrave" to "Ù",
        "ucirc" to "û",
        "Ucirc" to "Û",
        "uuml" to "ü",
        "Uuml" to "Ü",
        "yacute" to "ý",
        "Yacute" to "Ý",
        "yuml" to "ÿ",
        "Yuml" to "Ÿ",
        "szlig" to "ß",
    )
    private val entityPattern = Regex("""&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);""")

    fun decode(text: String): String = entityPattern.replace(text) { match ->
        val entity = match.groupValues[1]
        if (entity.startsWith("#x", ignoreCase = true)) {
            entity.substring(2).toLongOrNull(16)?.let(::fromCodePoint) ?: match.value
        } else if (entity.startsWith("#")) {
            entity.substring(1).toLongOrNull(10)?.let(::fromCodePoint) ?: match.value
        } else {
            namedEntities[entity] ?: namedEntities[entity.lowercase(Locale.ROOT)] ?: match.value
        }
    }

    private fun fromCodePoint(value: Long): String {
        if (value !in 1L..0x10ffffL || value in 0xd800L..0xdfffL) return "\uFFFD"
        return String(Character.toChars(value.toInt()))
    }
}
