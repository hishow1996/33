package io.translatetube.local.player

import java.security.MessageDigest

/** Stable identity and bounds for partial Whisper transcripts tied to a PCM/VAD layout. */
internal object AsrTranscriptCheckpointPolicy {
    fun signature(sampleCount: Int, segments: List<Pair<Long, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${sampleCount}|${segments.size}|".toByteArray(Charsets.UTF_8))
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
