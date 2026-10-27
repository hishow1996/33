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
    fun chunkKeysAreDeterministicAndNamespacedToThePartialCheckpoint() {
        val key = "local:video|source|local-asr-sensevoice|asr-progress-v2"

        assertEquals(
            "$key|chunk:",
            AsrTranscriptCheckpointPolicy.chunkCacheKeyPrefix(key),
        )
        assertEquals(
            "$key|chunk:0",
            AsrTranscriptCheckpointPolicy.chunkCacheKey(key, 0),
        )
        assertEquals(
            "$key|chunk:3",
            AsrTranscriptCheckpointPolicy.chunkCacheKey(key, 3),
        )
        assertTrue(
            AsrTranscriptCheckpointPolicy.chunkCacheKey(key, 0) !=
                AsrTranscriptCheckpointPolicy.chunkCacheKey("another-video", 0),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkKeysRejectMisalignedSegmentStarts() {
        AsrTranscriptCheckpointPolicy.chunkCacheKey("checkpoint", 2)
    }

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
    fun signatureChangesWhenOnlyTheLastSparsePcmSampleDiffers() {
        val pcm = FloatArray(4_096) { index -> (index % 31) / 31f }
        val segments = listOf(0L to 1_000L, 1_200L to 2_400L)
        val changedLastSample = pcm.copyOf().also { it[it.lastIndex] = 0.999f }

        assertFalse(
            AsrTranscriptCheckpointPolicy.signature(pcm, segments) ==
                AsrTranscriptCheckpointPolicy.signature(changedLastSample, segments)
        )
    }

    @Test
    fun restoredRowsMayContainSeveralTimedCaptionsInsideOneCompletedSegment() {
        val completed = listOf(0L to 1_000L, 1_500L to 2_500L, 3_000L to 4_000L)
        assertTrue(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(
                    Triple(0L, 250L, "first"),
                    Triple(250L, 600L, "second"),
                    Triple(600L, 1_000L, "third"),
                    Triple(1_500L, 2_100L, "next segment"),
                ),
                completed.take(2),
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 250L, "first"), Triple(0L, 250L, "duplicate interval")),
                completed.take(1),
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 250L, "valid"), Triple(1_200L, 1_400L, "outside VAD")),
                completed.take(1),
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(500L, 800L, "later"), Triple(100L, 250L, "out of order")),
                completed.take(1),
            )
        )
    }

    @Test
    fun restoredRowsMustFollowSegmentOrderWhenStartTimesTie() {
        val completed = listOf(0L to 1_000L, 0L to 2_000L, 2_500L to 3_000L)
        assertTrue(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(
                    Triple(0L, 1_000L, "first"),
                    Triple(0L, 2_000L, "second"),
                    Triple(2_500L, 3_000L, "third"),
                ),
                completed,
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(
                    Triple(0L, 2_000L, "second before first"),
                    Triple(0L, 1_000L, "first after second"),
                ),
                completed.take(2),
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
            val memorySignature = AsrTranscriptCheckpointPolicy.signature(inMemory, segments)
            assertEquals(
                memorySignature,
                AsrTranscriptCheckpointPolicy.signature(fromFile, segments),
            )
            assertFalse(
                memorySignature == AsrTranscriptCheckpointPolicy.signature(
                    AudioExtract.PcmFile(file, pcm16.size.toLong(), 8_000),
                    segments,
                )
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
    fun fileBackedSignatureRejectsTruncatedPcmPayloadBeforeSeeking() {
        val file = File.createTempFile("pcm-signature-truncated-test", ".s16le")
        try {
            FileOutputStream(file).use { it.write(byteArrayOf(1, 0, 2, 0)) }
            val mismatched = AudioExtract.PcmFile(file, 3L, 16_000)
            val error = runCatching {
                AsrTranscriptCheckpointPolicy.signature(mismatched, listOf(0L to 1L))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        } finally {
            file.delete()
        }
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
    @Test
    fun rejectsUnorderedVadSegmentMetadataBeforeResumingRows() {
        val validRows = listOf(Triple(0L, 100L, "first"))
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                validRows,
                listOf(0L to 200L, 100L to 150L),
            )
        )
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                validRows,
                listOf(100L to 200L, 0L to 300L),
            )
        )
        assertTrue(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 100L, "first"), Triple(100L, 250L, "second")),
                listOf(0L to 100L, 100L to 250L),
            )
        )
    }


@Test
    fun rejectsCheckpointRowsAndSegmentsAboveRecoveryBounds() {
        val tooManyRows = List(100_001) { index -> Triple(index.toLong(), index.toLong() + 1L, "cue") }
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                tooManyRows,
                listOf(0L to 100_001L),
            )
        )

        val tooManySegments = List(100_001) { index -> index.toLong() * 2L to index.toLong() * 2L + 1L }
        assertFalse(AsrTranscriptCheckpointPolicy.areRowsValid(emptyList(), tooManySegments))
    }

    @Test
    fun resumeRequiresCanonicalGeneratedSignatureShape() {
        val valid = "0123456789abcdef0123456789abcdef"
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(valid, valid, 2, 2, 0))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume("not-a-hash", "not-a-hash", 2, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(valid.uppercase(), valid.uppercase(), 2, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(valid, valid, -1, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(valid, valid, 2, 2, -1))
    }

    @Test
    fun checkpointBoundsStillPermitEmptyRowsForAnEmptyCompletedPrefix() {
        assertTrue(AsrTranscriptCheckpointPolicy.areRowsValid(emptyList(), emptyList()))
        assertFalse(
            AsrTranscriptCheckpointPolicy.areRowsValid(
                listOf(Triple(0L, 1L, "cue")),
                emptyList(),
            )
        )
    }

}