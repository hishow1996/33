package io.translatetube.local.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsWavDecoderTest {
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
