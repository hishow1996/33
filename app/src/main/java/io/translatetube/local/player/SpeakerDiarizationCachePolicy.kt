package io.translatetube.local.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import io.translatetube.local.asr.SpeakerDiarizer

/**
 * Audio fingerprint and defensive serialization for per-video speaker diarization checkpoints.
 * Fingerprints sample sparse, evenly spaced PCM anchors plus the final sample so a repeat tap can
 * reuse CPU-heavy diarization only when the decoded source audio still matches.
 */
internal object SpeakerDiarizationCachePolicy {
    private const val VERSION = 1
    private const val MAX_CACHED_SEGMENTS = 20_000

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

    fun encodeSegments(signature: String, segments: List<SpeakerDiarizer.Segment>): String {
        require(signature.isNotBlank())
        require(segments.size in 1..MAX_CACHED_SEGMENTS)
        val rows = JSONArray()
        segments.forEach { segment ->
            require(segment.startSec.isFinite() && segment.endSec.isFinite())
            require(segment.startSec >= 0f && segment.endSec > segment.startSec)
            require(segment.speaker in 0..999)
            rows.put(
                JSONObject()
                    .put("s", segment.startSec.toDouble())
                    .put("e", segment.endSec.toDouble())
                    .put("sp", segment.speaker)
                    .put("c", segment.confidence.takeIf { it.isFinite() }?.toDouble() ?: -2.0),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("signature", signature)
            .put("segments", rows)
            .toString()
    }

    fun decodeSegments(
        json: String,
        savedSignature: String,
        expectedSignature: String,
    ): List<SpeakerDiarizer.Segment>? {
        if (savedSignature.isBlank() || savedSignature != expectedSignature) return null
        return runCatching {
            val root = JSONObject(json)
            if (root.optInt("version", -1) != VERSION ||
                root.optString("signature") != expectedSignature
            ) return@runCatching null
            val rows = root.optJSONArray("segments") ?: return@runCatching null
            if (rows.length() !in 1..MAX_CACHED_SEGMENTS) return@runCatching null
            val segments = ArrayList<SpeakerDiarizer.Segment>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: return@runCatching null
                val start = row.optDouble("s", Double.NaN).toFloat()
                val end = row.optDouble("e", Double.NaN).toFloat()
                val speaker = row.optInt("sp", -1)
                val confidence = row.optDouble("c", -2.0).toFloat()
                if (!start.isFinite() || !end.isFinite() || start < 0f ||
                    end <= start || speaker !in 0..999 || !confidence.isFinite()
                ) return@runCatching null
                segments += SpeakerDiarizer.Segment(start, end, speaker, confidence)
            }
            segments
        }.getOrNull()
    }
}
