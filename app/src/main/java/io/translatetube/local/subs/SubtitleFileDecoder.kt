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

        decodeStrict(bytes, StandardCharsets.UTF_8, 0)?.let { return it.removePrefix("\uFEFF") }
        decodeStrict(bytes, gb18030(), 0)?.let { return it.removePrefix("\uFEFF") }
        return String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
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
