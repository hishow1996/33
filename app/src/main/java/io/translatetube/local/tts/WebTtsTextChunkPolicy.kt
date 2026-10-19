package io.translatetube.local.tts

import java.net.URLEncoder

/**
 * Splits TTS text against the encoded GET-query budget, not just UTF-8 bytes.
 * Non-ASCII text expands to percent-encoded triplets, and iteration is by Unicode
 * code point so an emoji/surrogate pair is never broken between requests.
 */
internal object WebTtsTextChunkPolicy {
    const val MAX_ENCODED_QUERY_CHARS = 1_800
    const val SOFT_ENCODED_QUERY_CHARS = 900

    private val sentenceBreakers = setOf('。', '！', '？', '；', '，', '、', ' ')

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val chunks = ArrayList<String>()
        var current = StringBuilder()
        var encodedLength = 0
        var offset = 0

        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            val piece = text.substring(offset, nextOffset)
            val pieceEncodedLength = URLEncoder.encode(piece, "UTF-8").length

            // Flush before the next code point would exceed the URL query budget.
            if (current.isNotEmpty() &&
                encodedLength + pieceEncodedLength > MAX_ENCODED_QUERY_CHARS
            ) {
                chunks.add(current.toString())
                current = StringBuilder()
                encodedLength = 0
            }

            current.append(piece)
            encodedLength += pieceEncodedLength
            offset = nextOffset

            // Prefer semantic boundaries near the soft budget to keep speech natural.
            if (codePoint <= Char.MAX_VALUE.code &&
                codePoint.toChar() in sentenceBreakers &&
                encodedLength >= SOFT_ENCODED_QUERY_CHARS
            ) {
                chunks.add(current.toString())
                current = StringBuilder()
                encodedLength = 0
            }
        }

        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
