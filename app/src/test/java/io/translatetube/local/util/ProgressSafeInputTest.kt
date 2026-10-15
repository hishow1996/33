package io.translatetube.local.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSafeInputTest {
    @Test
    fun recoversWhenBulkReadReturnsZeroWithoutDroppingBytes() {
        val backing = ByteArrayInputStream("audio".toByteArray(Charsets.UTF_8))
        val unusual = object : InputStream() {
            private var zeroOnce = true
            override fun read(): Int = backing.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (zeroOnce) {
                    zeroOnce = false
                    return 0
                }
                return backing.read(buffer, offset, length)
            }
        }
        val buffer = ByteArray(3)
        val result = ByteArrayOutputStreamForTest()
        while (true) {
            val count = ProgressSafeInput.read(unusual, buffer)
            if (count < 0) break
            assertTrue("positive-length read must make progress", count > 0)
            result.write(buffer, count)
        }
        assertEquals("audio", result.toString())
    }

    @Test
    fun emptyBufferIsRejectedBeforeReading() {
        val input = ByteArrayInputStream(byteArrayOf(1))
        val error = runCatching { ProgressSafeInput.read(input, ByteArray(0)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(1, input.read())
    }

    private class ByteArrayOutputStreamForTest {
        private val out = java.io.ByteArrayOutputStream()
        fun write(buffer: ByteArray, count: Int) = out.write(buffer, 0, count)
        override fun toString(): String = out.toString(Charsets.UTF_8.name())
    }
}
