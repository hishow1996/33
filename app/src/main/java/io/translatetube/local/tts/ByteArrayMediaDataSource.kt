package io.translatetube.local.tts

import android.media.MediaDataSource

/** Provides in-memory encoded audio bytes to MediaExtractor on Android API 23+. */
internal class ByteArrayMediaDataSource(
    private val data: ByteArray,
) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L) return -1
        if (position >= data.size.toLong()) return -1
        if (offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IndexOutOfBoundsException("Invalid MediaDataSource read range")
        }
        val count = minOf(size, data.size - position.toInt())
        if (count <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() = Unit
}
