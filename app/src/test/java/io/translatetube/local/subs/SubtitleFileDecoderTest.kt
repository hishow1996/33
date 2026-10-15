package io.translatetube.local.subs

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFileDecoderTest {
    @Test
    fun decodesUtf8AndStripsUtf8Bom() {
        val text = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n你好"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(StandardCharsets.UTF_8)
        assertEquals(text, SubtitleFileDecoder.decode(bytes))
    }

    @Test
    fun decodesBomMarkedUtf16LittleEndianAndBigEndian() {
        val text = "1" + 13.toChar() + 10.toChar() + "00:00:00,000 --> 00:00:01,000" + 13.toChar() + 10.toChar() + "字幕"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(StandardCharsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            text.toByteArray(StandardCharsets.UTF_16BE)
        assertEquals(text, SubtitleFileDecoder.decode(littleEndian))
        assertEquals(text, SubtitleFileDecoder.decode(bigEndian))
    }

    @Test
    fun detectsUtf16WithoutBomFromTimestampText() {
        val text = "1\\r\\n00:00:00,000 --> 00:00:01,000\\r\\n字幕"
        assertEquals(text, SubtitleFileDecoder.decode(text.toByteArray(StandardCharsets.UTF_16LE)))
        assertEquals(text, SubtitleFileDecoder.decode(text.toByteArray(StandardCharsets.UTF_16BE)))
    }

    @Test
    fun fallsBackToGb18030ForLegacyChineseSubtitleFiles() {
        val text = "1\n00:00:00,000 --> 00:00:01,000\n中文字幕"
        val bytes = text.toByteArray(Charset.forName("GB18030"))
        assertEquals(text, SubtitleFileDecoder.decode(bytes))
    }
}
