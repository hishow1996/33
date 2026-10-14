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
}
