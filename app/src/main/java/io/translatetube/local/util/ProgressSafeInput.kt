package io.translatetube.local.util

import java.io.InputStream

/**
 * Reads at least one byte of progress from a non-empty buffer request.
 *
 * Some custom/network streams can return 0 for a positive-length bulk read. Callers that simply
 * repeat such reads may spin forever while downloading media, models, or subtitle payloads.
 */
internal object ProgressSafeInput {
    fun read(input: InputStream, buffer: ByteArray): Int =
        read(input, buffer, 0, buffer.size)

    fun read(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length > 0 && offset <= buffer.size - length) {
            "读取缓冲区范围无效"
        }
        val count = input.read(buffer, offset, length)
        if (count != 0) return count

        val value = input.read()
        if (value < 0) return -1
        buffer[offset] = value.toByte()
        return 1
    }
}
