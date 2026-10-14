package io.translatetube.local.asr

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VadReadFullyTest {
    @Test
    fun advancesWhenInputStreamTemporarilyReturnsZero() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val input = object : InputStream() {
            private var index = 0
            private var returnZero = true

            override fun read(): Int =
                if (index >= expected.size) -1 else expected[index++].toInt() and 0xff

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                if (index >= expected.size) return -1
                val count = minOf(length, expected.size - index)
                expected.copyInto(buffer, offset, index, index + count)
                index += count
                return count
            }
        }

        val result = ByteArray(expected.size)
        readFully(input, result)

        assertArrayEquals(expected, result)
    }
}
