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
}
