package io.translatetube.local.data

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class ParallelBzip2InputStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sampleBytes(size: Int, seed: Long = 1L): ByteArray {
        val random = Random(seed)
        val out = ByteArray(size)
        var i = 0
        while (i < size) {
            val run = 1 + random.nextInt(4_000)
            val noisy = random.nextBoolean()
            val base = random.nextInt(256)
            var j = 0
            while (j < run && i < size) {
                out[i++] = if (noisy) random.nextInt(256).toByte() else (base + random.nextInt(5)).toByte()
                j++
            }
        }
        return out
    }

    private fun bz2(data: ByteArray, blockSize100k: Int): File {
        val file = temp.newFile()
        BZip2CompressorOutputStream(FileOutputStream(file), blockSize100k).use { it.write(data) }
        return file
    }

    private fun decode(file: File, threads: Int): ByteArray =
        ParallelBzip2InputStream(file, threads).use { it.readBytes() }

    @Test
    fun manyBlocksDecodeToExactlyTheOriginalWithAnyThreadCount() {
        // blockSize100k = 1 → about 100 KB per block, so 1.5 MB spans many unaligned blocks.
        val data = sampleBytes(1_500_000)
        val file = bz2(data, 1)
        for (threads in intArrayOf(1, 2, 4)) {
            assertArrayEquals("threads=$threads", data, decode(file, threads))
        }
    }

    @Test
    fun emptyAndTinyStreamsWork() {
        assertEquals(0, decode(bz2(ByteArray(0), 9), 2).size)
        assertArrayEquals(byteArrayOf(42), decode(bz2(byteArrayOf(42), 9), 2))
    }

    @Test
    fun progressReachesTheEndOfTheCompressedFile() {
        val file = bz2(sampleBytes(600_000, 3), 1)
        ParallelBzip2InputStream(file, 2).use {
            it.readBytes()
            assertEquals(file.length(), it.compressedBytesConsumed())
        }
    }

    @Test
    fun truncatedCorruptAndNonBzip2FilesFailWithTheFallbackException() {
        val good = bz2(sampleBytes(800_000, 5), 1)
        val bytes = good.readBytes()

        val truncated = temp.newFile().apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        val corrupt = temp.newFile().apply {
            writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() })
        }
        val notBz2 = temp.newFile().apply { writeBytes(ByteArray(200) { it.toByte() }) }

        for (file in listOf(truncated, corrupt, notBz2)) {
            try {
                decode(file, 2)
                fail("expected a failure for ${file.name}")
            } catch (e: ParallelBzip2InputStream.ParallelBzip2Exception) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun concatenatedStreamsAreRejectedSoTheCallerFallsBack() {
        val a = bz2(sampleBytes(10_000, 7), 9).readBytes()
        val b = bz2(sampleBytes(10_000, 8), 9).readBytes()
        val joined = temp.newFile().apply { writeBytes(a + b) }
        try {
            decode(joined, 2)
            fail("expected concatenated streams to be rejected")
        } catch (_: ParallelBzip2InputStream.ParallelBzip2Exception) {
        }
    }
}
