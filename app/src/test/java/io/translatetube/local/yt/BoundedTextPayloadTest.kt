package io.translatetube.local.yt

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BoundedTextPayloadTest {
    @Test
    fun readsPayloadUpToTheExactLimit() {
        val bytes = "字幕内容".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, BoundedTextPayload.readBytes(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test
    fun rejectsPayloadThatExceedsLimitEvenWhenLengthIsUnknown() {
        val bytes = ByteArray(1025) { 65.toByte() }
        val result = runCatching {
            BoundedTextPayload.readBytes(ByteArrayInputStream(bytes), 1024L)
        }
        assertFalse(result.isSuccess)
        assertEquals(true, result.exceptionOrNull()?.message.orEmpty().contains("超过"))
    }

    @Test
    fun acceptsEmptyPayloadWithoutAllocatingTheFullLimit() {
        assertArrayEquals(
            ByteArray(0),
            BoundedTextPayload.readBytes(ByteArrayInputStream(ByteArray(0)), 1024L),
        )
    }

    @Test
    fun reportsWhichLocalResourceExceededItsMemoryBound() {
        val result = runCatching {
            BoundedTextPayload.readBytes(
                ByteArrayInputStream(ByteArray(1025) { 65.toByte() }),
                maxBytes = 1024L,
                resourceLabel = "导入的字幕文件",
            )
        }

        assertFalse(result.isSuccess)
        assertEquals(true, result.exceptionOrNull()?.message.orEmpty().contains("导入的字幕文件"))
    }

    @Test
    fun stopsReadingWhenOwnerCancelsBetweenChunks() {
        var checks = 0
        val result = runCatching {
            BoundedTextPayload.readBytes(
                ByteArrayInputStream(ByteArray(40 * 1024) { 65.toByte() }),
                maxBytes = 64L * 1024L,
                checkActive = {
                    checks++
                    if (checks == 2) throw CancellationException("subtitle import cancelled")
                },
            )
        }

        assertEquals(true, result.exceptionOrNull() is CancellationException)
        assertEquals(2, checks)
    }
}
