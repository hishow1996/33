package io.translatetube.local.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsWavDecoderTest {
    @Test
    fun exactBoundedReadRejectsTruncatedFiles() {
        val input = java.io.ByteArrayInputStream(ByteArray(43))
        val error = runCatching {
            SystemTtsWavDecoder.readExactBoundedBytes(input, 44L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("截断"))
    }

    @Test
    fun exactBoundedReadRejectsFilesThatGrowAfterSizeCheck() {
        val input = java.io.ByteArrayInputStream(ByteArray(45))
        val error = runCatching {
            SystemTtsWavDecoder.readExactBoundedBytes(input, 44L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("发生变化"))
    }

    @Test
    fun exactBoundedReadHandlesZeroProgressStreams() {
        val backing = java.io.ByteArrayInputStream(ByteArray(44) { it.toByte() })
        val input = object : java.io.InputStream() {
            private var returnZero = true
            override fun read(): Int = backing.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return backing.read(buffer, offset, length)
            }
        }

        assertEquals(44, SystemTtsWavDecoder.readExactBoundedBytes(input, 44L).size)
    }

    @Test
    fun rejectsDuplicateFormatChunksInsteadOfSilentlyUsingTheLastOne() {
        val valid = wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1, 0))
        val bytes = ByteBuffer.allocate(valid.size + 24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(valid, 0, 36)
            put(valid, 12, 24)
            put(valid, 36, valid.size - 36)
        }.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)

        val error = runCatching { SystemTtsWavDecoder.decode(bytes) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("重复 fmt"))
    }

    @Test
    fun rejectsDuplicateDataChunksInsteadOfDroppingEarlierAudio() {
        val valid = wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1, 0))
        val bytes = ByteBuffer.allocate(valid.size + 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(valid)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(4)
            put(byteArrayOf(2, 0, 3, 0))
        }.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)

        val error = runCatching { SystemTtsWavDecoder.decode(bytes) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("重复 data"))
    }

    @Test
    fun rejectsWavWithAnIncorrectBlockAlign() {
        val bytes = wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1, 0))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(32, 1.toShort())

        val error = runCatching { SystemTtsWavDecoder.decode(bytes) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("blockAlign"))
    }

    @Test
    fun rejectsWavWithAnIncorrectByteRate() {
        val bytes = wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1, 0))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 12_345)

        val error = runCatching { SystemTtsWavDecoder.decode(bytes) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("byteRate"))
    }

    @Test
    fun decodesPcm16AndNormalizesSignedSamples() {
        val audio = SystemTtsWavDecoder.decode(
            wave(
                formatCode = 1,
                channels = 1,
                sampleRate = 16_000,
                bits = 16,
                data = byteArrayOf(0, 0, 0, 64, 0, -64),
            )
        )

        assertEquals(16_000, audio.sampleRate)
        assertEquals(3, audio.samples.size)
        assertEquals(0.0f, audio.samples[0], 0.001f)
        assertEquals(0.5f, audio.samples[1], 0.001f)
        assertEquals(-0.5f, audio.samples[2], 0.001f)
    }

    @Test
    fun downmixesStereoFramesToMono() {
        val audio = SystemTtsWavDecoder.decode(
            wave(
                formatCode = 1,
                channels = 2,
                sampleRate = 22_050,
                bits = 16,
                // frame 1: near-full positive in both channels; frame 2: -1 and 0
                data = byteArrayOf(-1, 127, -1, 127, 0, -128, 0, 0),
            )
        )

        assertEquals(22_050, audio.sampleRate)
        assertEquals(2, audio.samples.size)
        assertTrue(audio.samples[0] > 0.99f)
        assertEquals(-0.5f, audio.samples[1], 0.001f)
    }

    @Test
    fun decodesPcm24AndFloat32Samples() {
        val pcm24 = SystemTtsWavDecoder.decode(
            wave(1, 1, 24_000, 24, byteArrayOf(0, 0, 0, 0, 0, 64))
        )
        assertEquals(2, pcm24.samples.size)
        assertEquals(0.0f, pcm24.samples[0], 0.001f)
        assertEquals(0.5f, pcm24.samples[1], 0.001f)

        val floatData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.0f)
            .putFloat(-0.75f)
            .array()
        val floatPcm = SystemTtsWavDecoder.decode(
            wave(3, 1, 24_000, 32, floatData)
        )
        assertEquals(2, floatPcm.samples.size)
        assertEquals(-0.75f, floatPcm.samples[1], 0.001f)
    }

    @Test
    fun decodesExtensiblePcm16AndFloat32UsingTheirCanonicalSubformatGuids() {
        val pcm = SystemTtsWavDecoder.decode(
            extensibleWave(
                formatCode = 1,
                channels = 1,
                sampleRate = 16_000,
                bits = 16,
                data = byteArrayOf(0, 64, 0, -64),
            )
        )
        assertEquals(2, pcm.samples.size)
        assertEquals(0.5f, pcm.samples[0], 0.001f)
        assertEquals(-0.5f, pcm.samples[1], 0.001f)

        val floatData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f)
            .putFloat(-0.75f)
            .array()
        val floatPcm = SystemTtsWavDecoder.decode(
            extensibleWave(3, 1, 24_000, 32, floatData)
        )
        assertEquals(2, floatPcm.samples.size)
        assertEquals(0.25f, floatPcm.samples[0], 0.001f)
        assertEquals(-0.75f, floatPcm.samples[1], 0.001f)
    }

    @Test
    fun rejectsExtensibleWavWithUnknownSubformatGuid() {
        val bytes = extensibleWave(1, 1, 16_000, 16, byteArrayOf(0, 64, 0, -64))
        // Change the GUID's final byte. The decoder must not treat an unknown codec as PCM.
        bytes[59] = 0x70.toByte()

        val error = runCatching { SystemTtsWavDecoder.decode(bytes) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("子格式不支持"))
    }

    private fun extensibleWave(
        formatCode: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        data: ByteArray,
    ): ByteArray {
        val padding = data.size and 1
        val bytes = ByteBuffer.allocate(68 + data.size + padding).order(ByteOrder.LITTLE_ENDIAN)
        fun id(value: String) { bytes.put(value.toByteArray(Charsets.US_ASCII)) }
        id("RIFF")
        bytes.putInt(bytes.capacity() - 8)
        id("WAVE")
        id("fmt ")
        bytes.putInt(40)
        bytes.putShort(0xFFFE.toShort())
        bytes.putShort(channels.toShort())
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * channels * bits / 8)
        bytes.putShort((channels * bits / 8).toShort())
        bytes.putShort(bits.toShort())
        bytes.putShort(22.toShort()) // cbSize
        bytes.putShort(bits.toShort()) // validBitsPerSample
        bytes.putInt(if (channels == 1) 0x4 else 0x3) // channel mask
        bytes.putInt(formatCode) // SubFormat.Data1 (little-endian)
        bytes.putShort(0.toShort()) // SubFormat.Data2
        bytes.putShort(0x0010.toShort()) // SubFormat.Data3
        bytes.put(byteArrayOf(0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        id("data")
        bytes.putInt(data.size)
        bytes.put(data)
        if (padding != 0) bytes.put(0)
        return bytes.array()
    }

    @Test
    fun rejectsOversizedWavBeforeReadingItsContents() {
        val file = java.io.File.createTempFile("tts_oversized_", ".wav")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(17L * 1024L * 1024L) }
            try {
                SystemTtsWavDecoder.decode(file)
                throw AssertionError("Expected oversized WAV to be rejected")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("大小异常"))
            }
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDataOutsideTheDeclaredRiffContainer() {
        val bytes = wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1, 0))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4, bytes.size - 8 - 4)
        // The backing array still contains audio bytes, but the RIFF length ends at the data header.
        SystemTtsWavDecoder.decode(bytes)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsPcmDataThatEndsMidSampleFrame() {
        SystemTtsWavDecoder.decode(
            wave(1, 1, 16_000, 16, byteArrayOf(0, 64, 1))
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsNonWaveHeaders() {
        val bytes = wave(1, 1, 16_000, 16, byteArrayOf(0, 0, 1, 0))
        bytes[0] = 'X'.code.toByte()
        SystemTtsWavDecoder.decode(bytes)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnsupportedSampleEncoding() {
        SystemTtsWavDecoder.decode(wave(6, 1, 16_000, 16, byteArrayOf(0, 0, 1, 0)))
    }

    private fun wave(
        formatCode: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        data: ByteArray,
    ): ByteArray {
        val padding = data.size and 1
        val bytes = ByteBuffer.allocate(44 + data.size + padding).order(ByteOrder.LITTLE_ENDIAN)
        fun id(value: String) { bytes.put(value.toByteArray(Charsets.US_ASCII)) }
        id("RIFF")
        bytes.putInt(bytes.capacity() - 8)
        id("WAVE")
        id("fmt ")
        bytes.putInt(16)
        bytes.putShort(formatCode.toShort())
        bytes.putShort(channels.toShort())
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * channels * bits / 8)
        bytes.putShort((channels * bits / 8).toShort())
        bytes.putShort(bits.toShort())
        id("data")
        bytes.putInt(data.size)
        bytes.put(data)
        if (padding != 0) bytes.put(0)
        return bytes.array()
    }
}
