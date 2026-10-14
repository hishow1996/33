package io.translatetube.local.player

import java.security.MessageDigest

/** Stable identity and bounds for partial Whisper transcripts tied to a PCM/VAD layout. */
internal object AsrTranscriptCheckpointPolicy {
    fun signature(pcmSamples: FloatArray, segments: List<Pair<Long, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pcmSamples.size}|${segments.size}|".toByteArray(Charsets.UTF_8))

        // Checkpoints can skip an expensive native ASR pass only when the decoded PCM matches.
        // Hash all samples in bounded blocks: sparse anchors can collide when audio changes between
        // sampled positions, reusing text/timestamps from a different same-length recording.
        val sampleBuffer = java.nio.ByteBuffer.allocate(16 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmSamples) {
            if (sampleBuffer.remaining() < Int.SIZE_BYTES) {
                digest.update(sampleBuffer.array(), 0, sampleBuffer.position())
                sampleBuffer.clear()
            }
            sampleBuffer.putInt(sample.toRawBits())
        }
        if (sampleBuffer.position() > 0) {
            digest.update(sampleBuffer.array(), 0, sampleBuffer.position())
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
