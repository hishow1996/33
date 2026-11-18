package io.translatetube.local.util

/**
 * Credential scrubber shared by every log/report export path.
 *
 * Patterns are compiled once (they used to be rebuilt on every call) and cover:
 * whole-line auth headers (multi-token values such as "Token xxx" or "a=1; b=2"),
 * Bearer/Basic credentials, JSON and key=value secrets (including cloud-vendor style names such as
 * secretKey / accessKeySecret), URL user-info and signed query parameters.
 */
internal object LogRedactor {
    private const val SECRET_NAMES =
        """api[\s_-]?key|authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|password|""" +
            """(?:client|app)[_-]?secret|secret[_-]?key|access[_-]?key(?:[_-]?(?:id|secret))?|""" +
            """private[_-]?key|session[_-]?token|id[_-]?token"""

    private val headerLine = Regex(
        """(?im)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|api-key|ocp-apim-subscription-key)(\s*[:=：]\s*)[^\r\n]+""",
    )
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val basic = Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]{8,}""")
    private val jsonSecrets = Regex("""(?i)("(?:$SECRET_NAMES)"\s*:\s*")[^"]*(")""")
    private val urlUserInfo = Regex("""(?i)(https?://)[^/\s@]+@""")
    private val credentials = Regex("""(?i)($SECRET_NAMES)(\s*[=:：]\s*)[^\s,;]+""")
    private val querySecrets = Regex(
        """(?i)([?&](?:[A-Za-z0-9_.-]*(?:api[_-]?key|key|token|sig(?:nature)?|cookie|auth|password|secret|expires|credential|session|hdnts|policy|jwt))=)[^\s&]+""",
    )

    fun redact(input: String): String {
        var value = input
        value = headerLine.replace(value) { it.groupValues[1] + it.groupValues[2] + "[REDACTED]" }
        value = bearer.replace(value, "Bearer [REDACTED]")
        value = basic.replace(value, "Basic [REDACTED]")
        value = jsonSecrets.replace(value) { it.groupValues[1] + "[REDACTED]" + it.groupValues[2] }
        value = urlUserInfo.replace(value) { it.groupValues[1] + "[REDACTED]@" }
        value = credentials.replace(value) { it.groupValues[1] + it.groupValues[2] + "[REDACTED]" }
        value = querySecrets.replace(value) { it.groupValues[1] + "[REDACTED]" }
        return value
    }
}
