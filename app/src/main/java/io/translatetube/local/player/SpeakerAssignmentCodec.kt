package io.translatetube.local.player

import io.translatetube.local.tts.KokoroVoiceRange

/**
 * Versioned, text-only representation of the user's per-video voice assignments.
 * Kept free of Android dependencies so decoding/validation can be unit-tested on the JVM.
 */
internal data class SpeakerAssignmentSnapshot(
    val speakerVoices: Map<Int, Int> = emptyMap(),
    val lineVoices: Map<String, Int> = emptyMap(),
    val lineOverrides: Map<String, Int> = emptyMap(),
)

internal object SpeakerAssignmentCodec {
    private const val VERSION = "speaker-map-v1"

    fun lineKey(startMs: Long, endMs: Long): String = "$startMs:$endMs"

    fun encode(snapshot: SpeakerAssignmentSnapshot): String {
        val parts = mutableListOf(VERSION)
        snapshot.speakerVoices.toSortedMap().forEach { (speaker, voice) ->
            if (speaker in 0..KokoroVoiceRange.MAX_SPEAKER_ID && voice in -1..KokoroVoiceRange.MAX_SPEAKER_ID) parts += "s:$speaker=$voice"
        }
        snapshot.lineVoices.toSortedMap().forEach { (key, voice) ->
            if (isValidLineKey(key) && voice in -1..KokoroVoiceRange.MAX_SPEAKER_ID) parts += "v:$key=$voice"
        }
        snapshot.lineOverrides.toSortedMap().forEach { (key, voice) ->
            if (isValidLineKey(key) && voice in -1..KokoroVoiceRange.MAX_SPEAKER_ID) parts += "m:$key=$voice"
        }
        return parts.joinToString("|")
    }

    fun decode(encoded: String?): SpeakerAssignmentSnapshot {
        if (encoded.isNullOrBlank()) return SpeakerAssignmentSnapshot()
        val parts = encoded.split('|')
        if (parts.firstOrNull() != VERSION) return SpeakerAssignmentSnapshot()

        val speakers = linkedMapOf<Int, Int>()
        val lines = linkedMapOf<String, Int>()
        val overrides = linkedMapOf<String, Int>()
        for (part in parts.drop(1)) {
            val equals = part.indexOf('=')
            if (equals <= 0 || equals == part.lastIndex) continue
            val left = part.substring(0, equals)
            val voice = part.substring(equals + 1).toIntOrNull()?.takeIf { it in -1..KokoroVoiceRange.MAX_SPEAKER_ID } ?: continue
            val kind = left.substringBefore(':')
            val key = left.substringAfter(':', "")
            when (kind) {
                "s" -> key.toIntOrNull()?.takeIf { it in 0..KokoroVoiceRange.MAX_SPEAKER_ID }?.let { speakers[it] = voice }
                "v" -> if (isValidLineKey(key)) lines[key] = voice
                "m" -> if (isValidLineKey(key)) overrides[key] = voice
            }
        }
        return SpeakerAssignmentSnapshot(speakers, lines, overrides)
    }

    private fun isValidLineKey(key: String): Boolean {
        val parts = key.split(':')
        if (parts.size != 2) return false
        val start = parts[0].toLongOrNull() ?: return false
        val end = parts[1].toLongOrNull() ?: return false
        return start >= 0L && end > start
    }
}
