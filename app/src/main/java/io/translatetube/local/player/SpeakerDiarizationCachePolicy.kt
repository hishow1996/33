package io.translatetube.local.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pure, versioned speaker timeline record so cache validation can be JVM-tested without Android. */
internal data class SpeakerDiarizationRecord(
    val startSec: Float,
    val endSec: Float,
    val speaker: Int,
    val confidence: Float,
)

/**
 * Audio fingerprint and defensive serialization for per-video speaker diarization checkpoints.
 * Fingerprints sample sparse, evenly spaced PCM anchors plus the final sample so a repeat tap can
 * reuse CPU-heavy diarization only when the decoded source audio still matches.
 */
internal object SpeakerDiarizationCachePolicy {
    private const val VERSION = 1
    private const val MAX_CACHED_SEGMENTS = 20_000
    private const val HEADER = "speaker-diarization-v$VERSION"

    fun signature(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("speaker-audio-v$VERSION|${samples.size}|".toByteArray(Charsets.UTF_8))
        val stride = maxOf(1, samples.size / 1_024)
        val sample = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        for (index in samples.indices step stride) {
            sample.clear()
            sample.putInt(samples[index].toRawBits())
            digest.update(sample.array())
        }
        if (samples.isNotEmpty() && samples.lastIndex % stride != 0) {
            sample.clear()
            sample.putInt(samples.last().toRawBits())
            digest.update(sample.array())
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encodeSegments(signature: String, segments: List<SpeakerDiarizationRecord>): String {
        require(signature.isNotBlank())
        require(segments.size in 1..MAX_CACHED_SEGMENTS)
        val rows = ArrayList<String>(segments.size + 1)
        rows += "$HEADER|$signature"
        segments.forEach { segment ->
            require(segment.startSec.isFinite() && segment.endSec.isFinite())
            require(segment.startSec >= 0f && segment.endSec > segment.startSec)
            require(segment.speaker in 0..999)
            val confidence = segment.confidence.takeIf { it.isFinite() } ?: -2f
            rows += listOf(
                segment.startSec.toRawBits(),
                segment.endSec.toRawBits(),
                segment.speaker,
                confidence.toRawBits(),
            ).joinToString(",")
        }
        return rows.joinToString("\n")
    }

    fun decodeSegments(
        encoded: String,
        savedSignature: String,
        expectedSignature: String,
    ): List<SpeakerDiarizationRecord>? {
        if (savedSignature.isBlank() || savedSignature != expectedSignature) return null
        val rows = encoded.split('\n')
        if (rows.firstOrNull() != "$HEADER|$expectedSignature") return null
        val records = rows.drop(1)
        if (records.size !in 1..MAX_CACHED_SEGMENTS) return null
        val decoded = ArrayList<SpeakerDiarizationRecord>(records.size)
        for (row in records) {
            val values = row.split(',')
            if (values.size != 4) return null
            val startBits = values[0].toIntOrNull() ?: return null
            val endBits = values[1].toIntOrNull() ?: return null
            val speaker = values[2].toIntOrNull() ?: return null
            val confidenceBits = values[3].toIntOrNull() ?: return null
            val start = Float.fromBits(startBits)
            val end = Float.fromBits(endBits)
            val confidence = Float.fromBits(confidenceBits)
            if (!start.isFinite() || !end.isFinite() || start < 0f ||
                end <= start || speaker !in 0..999 || !confidence.isFinite()
            ) return null
            decoded += SpeakerDiarizationRecord(start, end, speaker, confidence)
        }
        return decoded
    }

    /** Keep recent speaker checkpoints bounded while always preserving the cache just written. */
    fun keysToEvict(
        newestFirstKeys: List<String>,
        keepKey: String,
        maxEntries: Int = 5,
    ): List<String> {
        if (maxEntries <= 0) return newestFirstKeys.filter { it != keepKey }
        var retained = if (keepKey in newestFirstKeys) 1 else 0
        val evicted = mutableListOf<String>()
        newestFirstKeys.forEach { key ->
            if (key == keepKey) return@forEach
            if (retained < maxEntries) retained++ else evicted += key
        }
        return evicted
    }
}
