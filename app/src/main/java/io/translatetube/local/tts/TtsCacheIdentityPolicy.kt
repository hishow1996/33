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

    // Keep the historical cache identity for the shipped model order so previously
    // synthesized, successful PCM remains reusable. Any changed candidate or priority order
    // receives a distinct identity automatically rather than relying on a manual version bump.
    private val legacyGeminiModelCandidates = listOf(
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts",
    )

    fun geminiCacheKey(
        voiceName: String,
        apiKey: String,
        modelCandidates: List<String> = legacyGeminiModelCandidates,
    ): String {
        val base = "gemini-tts-v3-${voiceName.trim().lowercase(Locale.ROOT)}-${credentialFingerprint(apiKey)}"
        val normalizedCandidates = modelCandidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedCandidates == legacyGeminiModelCandidates) return base
        val modelFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCandidates.joinToString("\n").toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$base-models-$modelFingerprint"
    }
}
