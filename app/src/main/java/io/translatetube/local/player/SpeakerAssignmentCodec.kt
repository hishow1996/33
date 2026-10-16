package io.translatetube.local.player

/**
 * Versioned, text-only representation of the user's per-video voice assignments.
 * Kept free of Android dependencies so decoding/validation can be unit-tested on the JVM.
 */
internal data class SpeakerAssignmentSnapshot(
    val speakerVoices: Map<Int, Int> = emptyMap(),
    val lineVoices: Map<String, Int> = emptyMap(),
    val lineOverrides: Map<String, Int> = emptyMap(),
    /** Canonical person ID inferred for each cue; independent of its selected tone ID. */
    val lineSpeakers: Map<String, Int> = emptyMap(),
)

internal object SpeakerAssignmentCodec {
    private const val VERSION = "speaker-map-v3"
    private const val PREVIOUS_VERSION = "speaker-map-v2"
    private const val LEGACY_VERSION = "speaker-map-v1"
    // Preference strings should stay bounded on long videos and should never fan out into
    // unbounded split/map allocations if the stored value is corrupt.
    private const val MAX_LINE_ASSIGNMENTS = 10_000
    private const val MAX_ENCODED_CHARS = 1_500_000
    private const val MAX_DECODE_PARTS = 31_000

    fun lineKey(startMs: Long, endMs: Long): String = "$startMs:$endMs"

    /** Keep voice assignments isolated because a tone ID has engine-specific meaning. */
    fun storageIdentity(videoKey: String, engineId: String): String {
        val engine = engineId.trim().lowercase().take(32).ifBlank { "kokoro" }
        return "$videoKey|tts:$engine"
    }

    fun encode(snapshot: SpeakerAssignmentSnapshot): String {
        val parts = mutableListOf(VERSION)
        snapshot.speakerVoices.toSortedMap().forEach { (speaker, voice) ->
            if (speaker in 0..999 && voice in -1..53) parts += "s:$speaker=$voice"
        }
        boundedLineEntries(snapshot.lineVoices).forEach { (key, voice) ->
            parts += "v:$key=$voice"
        }
        boundedLineEntries(snapshot.lineOverrides).forEach { (key, voice) ->
            parts += "m:$key=$voice"
        }
        boundedPersonEntries(snapshot.lineSpeakers).forEach { (key, personId) ->
            parts += "p:$key=$personId"
        }
        // The entry limit makes this a defensive last line rather than a normal truncation path.
        val encoded = parts.joinToString("|")
        return if (encoded.length <= MAX_ENCODED_CHARS) encoded else VERSION
    }

    fun decode(encoded: String?): SpeakerAssignmentSnapshot {
        if (encoded.isNullOrBlank() || encoded.length > MAX_ENCODED_CHARS) {
            return SpeakerAssignmentSnapshot()
        }
        val parts = encoded.split('|')
        if (parts.size > MAX_DECODE_PARTS) return SpeakerAssignmentSnapshot()
        val payloadVersion = parts.firstOrNull()
        if (payloadVersion != VERSION &&
            payloadVersion != PREVIOUS_VERSION &&
            payloadVersion != LEGACY_VERSION
        ) {
            return SpeakerAssignmentSnapshot()
        }
        // v1 stored raw sherpa cluster IDs which were not stable across runs. v2 had already
        // normalized person IDs but did not store a per-cue person identity.
        val legacySpeakerIds = payloadVersion == LEGACY_VERSION
        val hasPersonTimeline = payloadVersion == VERSION

        val speakers = linkedMapOf<Int, Int>()
        val lines = linkedMapOf<String, Int>()
        val overrides = linkedMapOf<String, Int>()
        val personTimeline = linkedMapOf<String, Int>()
        for (part in parts.drop(1)) {
            val equals = part.indexOf('=')
            if (equals <= 0 || equals == part.lastIndex) continue
            val left = part.substring(0, equals)
            val value = part.substring(equals + 1).toIntOrNull() ?: continue
            val kind = left.substringBefore(':')
            val key = left.substringAfter(':', "")
            when (kind) {
                "s" -> if (!legacySpeakerIds && value in -1..53) {
                    key.toIntOrNull()?.takeIf { it in 0..999 }?.let { speakers[it] = value }
                }
                "v" -> if (value in -1..53 && isValidLineKey(key)) lines[key] = value
                "m" -> if (value in -1..53 && isValidLineKey(key)) overrides[key] = value
                "p" -> if (hasPersonTimeline && value in 0..999 && isValidLineKey(key)) {
                    personTimeline[key] = value
                }
            }
        }
        return SpeakerAssignmentSnapshot(speakers, lines, overrides, personTimeline)
    }

    /**
     * Keep the most recently timed cues when a very long subtitle file exceeds the persistence
     * budget. Numeric time ordering matters here; lexicographic key ordering would keep the wrong
     * range for keys such as 9000:9500 and 10000:10500.
     */
    private fun boundedLineEntries(values: Map<String, Int>): List<Map.Entry<String, Int>> =
        values.entries.asSequence()
            .filter { isValidLineKey(it.key) && it.value in -1..53 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { lineStart(it.key) }
                    .thenByDescending { lineEnd(it.key) }
                    .thenBy { it.key }
            )
            .take(MAX_LINE_ASSIGNMENTS)
            .sortedBy { it.key }
            .toList()

    private fun boundedPersonEntries(values: Map<String, Int>): List<Map.Entry<String, Int>> =
        values.entries.asSequence()
            .filter { isValidLineKey(it.key) && it.value in 0..999 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { lineStart(it.key) }
                    .thenByDescending { lineEnd(it.key) }
                    .thenBy { it.key }
            )
            .take(MAX_LINE_ASSIGNMENTS)
            .sortedBy { it.key }
            .toList()

    private fun lineStart(key: String): Long = key.substringBefore(':').toLongOrNull() ?: -1L

    private fun lineEnd(key: String): Long = key.substringAfter(':').toLongOrNull() ?: -1L

    private fun isValidLineKey(key: String): Boolean {
        val parts = key.split(':')
        if (parts.size != 2) return false
        val start = parts[0].toLongOrNull() ?: return false
        val end = parts[1].toLongOrNull() ?: return false
        return start >= 0L && end > start
    }
}
