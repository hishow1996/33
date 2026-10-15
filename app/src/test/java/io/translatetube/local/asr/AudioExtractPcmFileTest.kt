package io.translatetube.local.asr

import java.io.File
import org.junit.Test

class AudioExtractPcmFileTest {
    @Test
    fun durationCalculationDoesNotOverflowForExtremeSampleCounts() {
        val count = Long.MAX_VALUE
        val rate = 16_000
        val expected = (count / rate) * 1_000L + (count % rate) * 1_000L / rate
        val pcm = AudioExtract.PcmFile(
            file = File("pcm-duration-overflow-test-does-not-need-to-exist"),
            sampleCount = count,
            sampleRate = rate,
        )

        org.junit.Assert.assertEquals(expected, pcm.durationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPcmFileWhosePhysicalLengthCannotCoverRequestedSamples() {
        val truncated = File.createTempFile("pcm-truncated-test", ".s16le")
        try {
            truncated.writeBytes(byteArrayOf(1, 0))
            AudioExtract.PcmFile(
                file = truncated,
                sampleCount = 3L,
            ).readFloatSamples(
                startSample = 0L,
                count = 3,
            )
        } finally {
            truncated.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPcmFileWithTrailingBytesBeyondDeclaredSampleCount() {
        val oversized = File.createTempFile("pcm-trailing-test", ".s16le")
        try {
            oversized.writeBytes(byteArrayOf(1, 0, 2, 0, 3, 0))
            AudioExtract.PcmFile(
                file = oversized,
                sampleCount = 2L,
            ).readFloatSamples(
                startSample = 0L,
                count = 2,
            )
        } finally {
            oversized.delete()
        }
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun checksCancellationBeforeAllocatingLargeFloatArrayOrOpeningFile() {
        val sampleCount = 32 * 1024 * 1024 / 4
        AudioExtract.PcmFile(
            file = File("pcm-read-cancellation-test-file-does-not-exist"),
            sampleCount = sampleCount.toLong(),
        ).readFloatSamples(
            startSample = 0L,
            count = sampleCount,
            checkActive = { throw java.util.concurrent.CancellationException("obsolete task") },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFloatReadsThatCouldAllocateMoreThan32MiB() {
        val sampleCount = (32L * 1024L * 1024L / 4L) + 1L
        AudioExtract.PcmFile(
            file = File("pcm-read-limit-test-does-not-need-to-exist"),
            sampleCount = sampleCount,
        ).readFloatSamples(
            startSample = 0L,
            count = sampleCount.toInt(),
        )
    }
}
