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
    )
    private val entityPattern = Regex("""&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);""")

    fun decode(text: String): String = entityPattern.replace(text) { match ->
        val entity = match.groupValues[1]
        if (entity.startsWith("#x", ignoreCase = true)) {
            entity.substring(2).toLongOrNull(16)?.let(::fromCodePoint) ?: match.value
        } else if (entity.startsWith("#")) {
            entity.substring(1).toLongOrNull(10)?.let(::fromCodePoint) ?: match.value
        } else {
            namedEntities[entity.lowercase(Locale.ROOT)] ?: match.value
        }
    }

    private fun fromCodePoint(value: Long): String {
        if (value !in 1L..0x10ffffL || value in 0xd800L..0xdfffL) return "\uFFFD"
        return String(Character.toChars(value.toInt()))
    }
}
