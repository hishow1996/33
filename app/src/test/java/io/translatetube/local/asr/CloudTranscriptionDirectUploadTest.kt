package io.translatetube.local.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTranscriptionDirectUploadTest {
    @Test
    fun recognizesWebmFromContainerSignature() {
        val file = File.createTempFile("asr-webm-", ".bin")
        try {
            file.writeBytes(
                byteArrayOf(
                    0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(),
                    0x93.toByte(), 0x42, 0x82.toByte(), 0x88.toByte(),
                    0x6D, 0x61, 0x74, 0x72, 0x6F, 0x73, 0x6B, 0x61,
                )
            )
            val input = CloudTranscription.detectEncodedAudioInput(file, "cartesia")
            assertNotNull(input)
            assertEquals("source-audio.webm", input?.fileName)
            assertEquals("audio/webm", input?.mimeType)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aacIsDirectUploadOnlyForElevenLabs() {
        val file = File.createTempFile("asr-aac-", ".bin")
        try {
            file.writeBytes(
                byteArrayOf(
                    0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(),
                    0x00, 0x1F, 0xFC.toByte(), 0x00,
                )
            )
            assertNull(CloudTranscription.detectEncodedAudioInput(file, "cartesia"))
            assertEquals(
                "audio/aac",
                CloudTranscription.detectEncodedAudioInput(file, "elevenlabs")?.mimeType,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun unknownContainerFallsBackToExistingPcmPipeline() {
        val file = File.createTempFile("asr-unknown-", ".bin")
        try {
            file.writeBytes("not an audio container".toByteArray(Charsets.US_ASCII))
            assertNull(CloudTranscription.detectEncodedAudioInput(file, "elevenlabs"))
            assertNull(CloudTranscription.detectEncodedAudioInput(file, "cartesia"))
            assertNull(CloudTranscription.detectEncodedAudioInput(file, "kawa"))
        } finally {
            file.delete()
        }
    }
}
