package io.translatetube.local.player

import java.io.File
import java.io.FileOutputStream
import io.translatetube.local.asr.AudioExtract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCheckpointPolicyTest {
    @Test
    fun signatureIsStableForSamePcmAndVadSegments() {
        val segments = listOf(0L to 900L, 1_200L to 2_400L, 3_100L to 4_000L)
        val pcm = FloatArray(64_000) { index -> ((index % 97) - 48) / 48f }
        assertEquals(
            AsrTranscriptCheckpointPolicy.signature(pcm, segments),
            AsrTranscriptCheckpointPolicy.signature(pcm, segments.toList()),
        )
    }

    @Test
    fun signatureChangesWhenAudioLengthPcmContentOrVadLayoutChanges() {
        val pcm = FloatArray(4_096) { index -> (index % 31) / 31f }
        val segments = listOf(0L to 900L, 1_200L to 2_400L)
        val signature = AsrTranscriptCheckpointPolicy.signature(pcm, segments)

        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(FloatArray(4_097), segments))
        val changedPcm = pcm.copyOf().also { it[2_048] = -0.75f }
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(changedPcm, segments))
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(pcm, listOf(0L to 800L, 1_200L to 2_400L)))
    }

    @Test
    fun restoredRowsMustBelongToCompletedSegmentsAndBeUnique() {
        val completed = listOf(0L to 1_000L, 1_500L to 2_500L, 3_000L to 4_000L)
        assertTrue(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 1_000L, "hello"), Triple(3_000L, 4_000L, "world")),
                completed,
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 1_000L, "hello"), Triple(0L, 1_000L, "duplicate")),
                completed,
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 1_000L, "hello"), Triple(2_000L, 2_500L, "wrong segment")),
                completed,
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(3_000L, 4_000L, "later"), Triple(0L, 1_000L, "out of order")),
                completed,
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 1_000L, "  ")),
                completed,
            )
        )
    }

    @Test
    fun onlyResumesMatchingCheckpointWithValidProcessedCount() {
        val pcm = FloatArray(128_000) { index -> ((index % 113) - 56) / 56f }
        val signature = AsrTranscriptCheckpointPolicy.signature(
            pcm,
            listOf(0L to 1_000L, 2_000L to 3_000L),
        )
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 1))
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 2))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, "other", 2, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 3, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 3))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume("", signature, 2, 2, 1))
    }
    @Test
    fun fileBackedSignatureMatchesInMemorySignatureForSamePcm16Samples() {
        val pcm16 = shortArrayOf(-32768, -12000, -1, 0, 1, 12000, 32767, 500, -500)
        val segments = listOf(0L to 300L, 400L to 900L)
        val file = File.createTempFile("pcm-signature-test", ".s16le")
        try {
            FileOutputStream(file).use { output ->
                pcm16.forEach { sample ->
                    output.write(sample.toInt() and 0xff)
                    output.write((sample.toInt() shr 8) and 0xff)
                }
            }
            val inMemory = FloatArray(pcm16.size) { pcm16[it] / 32768f }
            val fromFile = AudioExtract.PcmFile(file, pcm16.size.toLong(), 16_000)
            assertEquals(
                AsrTranscriptCheckpointPolicy.signature(inMemory, segments),
                AsrTranscriptCheckpointPolicy.signature(fromFile, segments),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun lowMemoryPolicyReleasesRecognizerOnlyWhenNeeded() {
        assertTrue(AsrMemoryPolicy.shouldReleaseRecognizer(true, 512, false, 2_000_000_000L, 4_000_000_000L))
        assertTrue(AsrMemoryPolicy.shouldReleaseRecognizer(false, 512, true, 2_000_000_000L, 4_000_000_000L))
        assertTrue(AsrMemoryPolicy.shouldReleaseRecognizer(false, 512, false, 300_000_000L, 4_000_000_000L))
        assertTrue(AsrMemoryPolicy.shouldReleaseRecognizer(false, 512, false, 350_000_000L, 8_000_000_000L))
        assertTrue(AsrMemoryPolicy.shouldReleaseRecognizer(false, 256, false, 1_000_000_000L, 4_000_000_000L))
        assertFalse(AsrMemoryPolicy.shouldReleaseRecognizer(false, 384, false, 1_000_000_000L, 4_000_000_000L))
        assertFalse(AsrMemoryPolicy.shouldReleaseRecognizer(false, 384, false, 0L, 0L))
    }


    @Test
    fun fileBackedSignatureCanBeCancelledBetweenSparseAnchors() {
        val sampleCount = 8_192
        val file = File.createTempFile("pcm-signature-cancel-test", ".s16le")
        try {
            FileOutputStream(file).use { output ->
                repeat(sampleCount) {
                    output.write(0x00)
                    output.write(0x20)
                }
            }
            val pcm = AudioExtract.PcmFile(file, sampleCount.toLong(), 16_000)
            var checks = 0
            val error = runCatching {
                AsrTranscriptCheckpointPolicy.signature(pcm, listOf(0L to 1_000L)) {
                    checks++
                    if (checks == 3) throw java.util.concurrent.CancellationException("fingerprint cancelled")
                }
            }.exceptionOrNull()

            assertTrue(error is java.util.concurrent.CancellationException)
            assertEquals(3, checks)
        } finally {
            file.delete()
        }
    }
}
