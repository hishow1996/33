package io.translatetube.local.subs

/**
 * Detects only script combinations that are distinctive enough to identify a source language
 * without loading an ML Kit model. Han-only text is intentionally left ambiguous because it may
 * be Chinese or Japanese.
 */
internal object SubtitleScriptLanguageHint {
    fun detect(text: String): String? {
        val hasKana = text.any {
            it in '\u3040'..'\u30FF' || it in '\u31F0'..'\u31FF' || it in '\uFF66'..'\uFF9D'
        }
        val hasHangul = text.any {
            it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' || it in '\uAC00'..'\uD7AF'
        }
        return when {
            hasKana && !hasHangul -> "ja"
            hasHangul && !hasKana -> "ko"
            else -> null
        }
    }
}