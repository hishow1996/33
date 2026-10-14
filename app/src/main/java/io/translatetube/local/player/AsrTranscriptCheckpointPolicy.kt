package io.translatetube.local.player

import java.security.MessageDigest

/** Stable identity and bounds for partial Whisper transcripts tied to a PCM/VAD layout. */
internal object AsrTranscriptCheckpointPolicy {
    fun signature(pcmSamples: FloatArray, segments: List<Pair<Long, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pcmSamples.size}|${segments.size}|".toByteArray(Charsets.UTF_8))

        // Sparse, evenly spaced PCM anchors distinguish same-length tracks without hashing every
        // sample on older devices. The VAD boundaries below add another inexpensive identity check.
        val stride = maxOf(1, pcmSamples.size / 1_024)
        val sampleBuffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (index in pcmSamples.indices step stride) {
            sampleBuffer.clear()
            sampleBuffer.putInt(pcmSamples[index].toRawBits())
            digest.update(sampleBuffer.array())
        }
        segments.forEach { (start, end) ->
            digest.update("${start},${end};".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }


    fun signature(pcmFile: io.translatetube.local.asr.AudioExtract.PcmFile, segments: List<Pair<Long, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pcmFile.sampleCount}|${segments.size}|".toByteArray(Charsets.UTF_8))
        // Use the same sparse, evenly spaced normalized PCM anchors as the in-memory signature.
        val stride = maxOf(1L, pcmFile.sampleCount / 1_024L)
        val sampleBuffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        java.io.RandomAccessFile(pcmFile.file, "r").use { input ->
            var index = 0L
            while (index < pcmFile.sampleCount) {
                input.seek(index * 2L)
                val raw = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                val normalizedSample = raw.toShort() / 32768f
                sampleBuffer.clear()
                sampleBuffer.putInt(normalizedSample.toRawBits())
                digest.update(sampleBuffer.array())
                index += stride
            }
        }
        segments.forEach { (start, end) ->
            digest.update("${start},${end};".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun canResume(
        savedSignature: String,
        expectedSignature: String,
        savedTotalSegments: Int,
        expectedTotalSegments: Int,
        processedSegments: Int,
    ): Boolean =
        savedSignature.isNotBlank() && savedSignature == expectedSignature &&
            expectedTotalSegments > 0 && savedTotalSegments == expectedTotalSegments &&
            processedSegments in 0..expectedTotalSegments
}

internal object AsrMemoryPolicy {
    private const val ABSOLUTE_AVAILABLE_THRESHOLD_BYTES = 384L * 1024L * 1024L
    private const val AVAILABLE_MEMORY_RATIO_THRESHOLD = 0.10

    fun shouldReleaseRecognizer(
        isLowRamDevice: Boolean,
        systemLowMemory: Boolean,
        availableMemoryBytes: Long,
        totalMemoryBytes: Long,
    ): Boolean {
        if (isLowRamDevice || systemLowMemory) return true
        if (availableMemoryBytes <= 0L || totalMemoryBytes <= 0L) return false
        return availableMemoryBytes < ABSOLUTE_AVAILABLE_THRESHOLD_BYTES ||
            availableMemoryBytes.toDouble() / totalMemoryBytes.toDouble() < AVAILABLE_MEMORY_RATIO_THRESHOLD
    }
}
