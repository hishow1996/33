package io.translatetube.local.tts

import java.security.MessageDigest
import java.util.Locale

/**
 * Isolates cached PCM by Gemini voice and credential without exposing API keys in cache names.
 */
internal object TtsCacheIdentityPolicy {
    fun credentialFingerprint(apiKey: String): String {
        if (apiKey.isBlank()) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Mirrors the API-profile fallback used by Gemini TTS into a non-secret preference.
     * A separately configured Gemini key takes precedence and makes profile changes irrelevant.
     */
    fun geminiFallbackCredentialFingerprint(
        freeGeminiApiKey: String,
        fallbackProfileApiKey: String,
    ): String {
        if (freeGeminiApiKey.isNotBlank() || fallbackProfileApiKey.isBlank()) return ""
        return credentialFingerprint(fallbackProfileApiKey)
    }

    fun geminiCacheKey(voiceName: String, apiKey: String): String =
        "gemini-tts-v3-${voiceName.trim().lowercase(Locale.ROOT)}-${credentialFingerprint(apiKey)}"
}
