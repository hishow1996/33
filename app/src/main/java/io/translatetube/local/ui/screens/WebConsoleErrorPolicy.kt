package io.translatetube.local.ui.screens

import java.util.Locale

/**
 * Filters a single known third-party browser-capability probe failure.
 *
 * This is deliberately narrow: same-origin errors, other third-party errors, and incomplete
 * source metadata remain visible in app diagnostics.
 */
internal object WebConsoleErrorPolicy {
    fun shouldIgnoreKnownThirdPartyError(
        message: String,
        pageHost: String,
        scriptHost: String,
    ): Boolean {
        val normalizedPageHost = pageHost.trim().lowercase(Locale.ROOT)
        val normalizedScriptHost = scriptHost.trim().lowercase(Locale.ROOT)
        if (
            normalizedPageHost.isBlank() ||
            normalizedScriptHost.isBlank() ||
            normalizedPageHost == "未知" ||
            normalizedScriptHost == "未知"
        ) return false
        if (normalizedPageHost == normalizedScriptHost) return false

        val normalizedMessage = message.lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
        return normalizedMessage.contains("detectincognito") &&
            Regex("""\bbrowser\s+not\s+supported\b""")
                .containsMatchIn(normalizedMessage)
    }
}
