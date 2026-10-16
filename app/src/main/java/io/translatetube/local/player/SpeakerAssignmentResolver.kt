package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Applies stored voice assignments to subtitle cues without Android dependencies. */
internal object SpeakerAssignmentResolver {
    fun apply(
        lines: List<SubLine>,
        assignments: SpeakerAssignmentSnapshot,
    ): List<SubLine> {
        if (lines.isEmpty() || (assignments.lineOverrides.isEmpty() && assignments.lineVoices.isEmpty())) {
            return lines
        }
        return lines.map { line ->
            val key = SpeakerAssignmentCodec.lineKey(line.startMs, line.endMs)
            // A user's manual per-cue choice must always win over an automatic speaker mapping.
            val voice = assignments.lineOverrides[key] ?: assignments.lineVoices[key]
            if (voice == null) line else line.copy(speakerId = voice)
        }
    }

    /** Return all subtitle keys attributed to the same canonical detected person. */
    fun lineKeysForPerson(
        lines: List<SubLine>,
        assignments: SpeakerAssignmentSnapshot,
        personId: Int,
    ): Set<String> {
        if (personId !in 0..999) return emptySet()
        return lines.asSequence()
            .filter { line ->
                assignments.lineSpeakers[SpeakerAssignmentCodec.lineKey(line.startMs, line.endMs)] == personId
            }
            .map { SpeakerAssignmentCodec.lineKey(it.startMs, it.endMs) }
            .toSet()
    }

    /**
     * Apply an explicit person-wide choice: remove superseded per-cue overrides and set every
     * attributed cue so both the current timeline and a future diarization reuse the chosen tone.
     */
    fun applyToneToPerson(
        assignments: SpeakerAssignmentSnapshot,
        personId: Int,
        voiceId: Int,
        lineKeys: Set<String>,
    ): SpeakerAssignmentSnapshot {
        if (personId !in 0..999 || voiceId !in -1..53 || lineKeys.isEmpty()) return assignments
        return assignments.copy(
            speakerVoices = assignments.speakerVoices + (personId to voiceId),
            lineVoices = assignments.lineVoices + lineKeys.associateWith { voiceId },
            lineOverrides = assignments.lineOverrides - lineKeys,
        )
    }
}
