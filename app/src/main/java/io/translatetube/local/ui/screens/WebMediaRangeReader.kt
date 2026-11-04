package io.translatetube.local.ui.screens

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * [WebRangeReader] over OkHttp. It sends the same Referer/Cookie/User-Agent as the player, so a
 * site's hotlink protection treats the source check like the real playback request.
 */
internal class OkHttpRangeReader(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
) : WebRangeReader {

    override fun read(start: Long, length: Int): WebRangeChunk {
        val request = Request.Builder().url(url).get().apply {
            header("Range", "bytes=$start-${start + length - 1}")
            // Ask for the bytes as stored: transparent gzip would break range arithmetic.
            header("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // Range starts at or beyond the end of the file.
                return WebRangeChunk(
                    ByteArray(0),
                    parseContentRangeTotal(response.header("Content-Range")),
                    true,
                )
            }
            if (response.code !in 200..299) {
                throw IOException("HTTP ${response.code} ${response.message}")
            }
            val partial = response.code == 206
            // A full 200 answer to a later range means the server ignores Range: stop here
            // instead of streaming the whole file just to look at a different offset.
            if (!partial && start > 0L) return WebRangeChunk(ByteArray(0), null, false)
            val body = response.body
                ?: throw EOFException("服务器没有返回内容（HTTP ${response.code}）。")
            val total = if (partial) {
                parseContentRangeTotal(response.header("Content-Range"))
            } else {
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
            }
            return WebRangeChunk(readUpTo(body.byteStream(), length), total, partial)
        }
    }

    internal companion object {
        // "bytes 0-262143/9876543" gives 9876543, and so does the 416 form "bytes star/9876543".
        // An unknown total ("star" after the slash) gives null.
        fun parseContentRangeTotal(value: String?): Long? =
            value?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

        private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            input.use { source ->
                while (total < maxBytes) {
                    val count = source.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            return output.toByteArray()
        }
    }
}
