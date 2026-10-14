package io.translatetube.local.yt

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded streaming read for remote caption payloads, including responses with unknown length. */
internal object BoundedTextPayload {
    const val MAX_BYTES = 16L * 1024L * 1024L
    private const val BUFFER_BYTES = 16 * 1024

    fun readBytes(
        input: InputStream,
        maxBytes: Long = MAX_BYTES,
        resourceLabel: String = "字幕响应",
        checkActive: () -> Unit = {},
    ): ByteArray {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "文本响应大小上限无效" }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_BYTES.toLong()).toInt())
        val buffer = ByteArray(BUFFER_BYTES)
        var totalBytes = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (totalBytes > maxBytes - count.toLong()) {
                throw IllegalStateException("${resourceLabel}超过 ${maxBytes / (1024L * 1024L)} MB 限制，已停止读取以保护设备内存。")
            }
            output.write(buffer, 0, count)
            totalBytes += count
        }
        return output.toByteArray()
    }
}
