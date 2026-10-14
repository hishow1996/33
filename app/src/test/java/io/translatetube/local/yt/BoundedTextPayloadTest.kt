package io.translatetube.local.yt

import java.io.ByteArrayInputStream
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
}
