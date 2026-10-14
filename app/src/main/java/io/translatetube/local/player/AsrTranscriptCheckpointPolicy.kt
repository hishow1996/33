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
