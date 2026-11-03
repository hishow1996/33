package io.translatetube.local.player

import java.util.Locale

/**
 * Stable identity for ASR transcript and subtitle caches.
 *
 * A completed transcript belongs to the selected recognition engine. Mutable credentials,
 * endpoint settings, language hints and decoder tuning do not create a new engine and therefore
 * do not split the cache. The engine version marker leaves room for a deliberate cache-format
 * change while the prefix remains compatible with older, more detailed cache keys.
 */
internal object AsrTranscriptCacheIdentity {
    @Suppress("UNUSED_PARAMETER")
    fun key(
        provider: String,
        localModelId: String,
        language: String = "auto",
        configFingerprint: String = "",
        modelFingerprint: String = "",
    ): String = enginePrefix(provider, localModelId) + "engine-v1"

    /**
     * Prefix shared by current and legacy keys from the same selected engine.
     *
     * Older app versions appended language and a configuration digest to this prefix. Looking up
     * by this prefix lets the current version migrate those source transcripts without rerunning ASR.
     */
    fun enginePrefix(
        provider: String,
        localModelId: String,
    ): String {
        val normalizedProvider = token(provider)
        val model = if (normalizedProvider == "local") token(localModelId) else "cloud"
        return "$normalizedProvider-$model-"
    }

    private fun token(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "unknown" }
}
