package io.translatetube.local.player

/**
 * A debounce decision can outlive the settings snapshot it was based on while media identity
 * resolution is suspended. Never apply that decision after preferences have changed again.
 */
internal object EnginePreferenceRestartDecisionPolicy {
    fun isStillCurrent(
        decisionFingerprint: String,
        currentFingerprint: String,
    ): Boolean =
        decisionFingerprint.isNotBlank() && decisionFingerprint == currentFingerprint
}
