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
