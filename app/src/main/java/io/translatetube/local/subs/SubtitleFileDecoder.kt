package io.translatetube.local.subs

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes subtitle files selected from external sources. Many Windows-created Chinese SRT files
 * use GB18030/GBK, and BOM-marked UTF-16 must not be forced through UTF-8.
 */
internal object SubtitleFileDecoder {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        when {
            startsWith(bytes, 0xEF, 0xBB, 0xBF) -> {
                decodeStrict(bytes, StandardCharsets.UTF_8, 3)?.let { return it.removePrefix("\uFEFF") }
                decodeStrict(bytes, gb18030(), 3)?.let { return it.removePrefix("\uFEFF") }
            }
            startsWith(bytes, 0xFF, 0xFE) -> {
                decodeStrict(bytes, StandardCharsets.UTF_16LE, 2)?.let { return it.removePrefix("\uFEFF") }
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE).removePrefix("\uFEFF")
            }
            startsWith(bytes, 0xFE, 0xFF) -> {
                decodeStrict(bytes, StandardCharsets.UTF_16BE, 2)?.let { return it.removePrefix("\uFEFF") }
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE).removePrefix("\uFEFF")
            }
        }

        // UTF-16 subtitles are sometimes exported without a BOM. Detect a strong alternating-NUL
        // pattern in ASCII timestamps before falling back to UTF-8/GB18030.
        when (bomlessUtf16Order(bytes)) {
            StandardCharsets.UTF_16LE -> decodeStrict(bytes, StandardCharsets.UTF_16LE, 0)?.let { return it }
            StandardCharsets.UTF_16BE -> decodeStrict(bytes, StandardCharsets.UTF_16BE, 0)?.let { return it }
        }

        decodeStrict(bytes, StandardCharsets.UTF_8, 0)?.let { return it.removePrefix("\uFEFF") }
        decodeStrict(bytes, gb18030(), 0)?.let { return it.removePrefix("\uFEFF") }
        return String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun bomlessUtf16Order(bytes: ByteArray): Charset? {
        val count = minOf(bytes.size, 256)
        if (count < 8) return null
        var evenNuls = 0
        var oddNuls = 0
        var pairs = 0
        for (index in 0 until count) {
            if (bytes[index].toInt() == 0) {
                if (index % 2 == 0) evenNuls++ else oddNuls++
            }
            if (index % 2 == 1) pairs++
        }
        if (pairs <= 0) return null
        val evenRatio = evenNuls.toDouble() / pairs
        val oddRatio = oddNuls.toDouble() / pairs
        return when {
            oddRatio >= 0.30 && evenRatio <= 0.10 -> StandardCharsets.UTF_16LE
            evenRatio >= 0.30 && oddRatio <= 0.10 -> StandardCharsets.UTF_16BE
            else -> null
        }
    }

    private fun startsWith(bytes: ByteArray, vararg values: Int): Boolean =
        bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 0xff) == values[it] }

    private fun gb18030(): Charset = Charset.forName("GB18030")

    private fun decodeStrict(bytes: ByteArray, charset: Charset, offset: Int): String? =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }.getOrNull()
}
