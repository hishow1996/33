package io.translatetube.local.tts

/** Maps detected people to the voices the active Android TTS engine can actually provide. */
internal object SystemTtsSpeakerVoicePolicy {
    const val CATALOG_PREF_KEY = "system_tts_voice_catalog"
    private const val CATALOG_SEPARATOR = "\u001F"
    private const val MAX_CATALOG_VOICES = 128
    private const val MAX_CATALOG_CHARS = 8_192

    /**
     * Prefer on-device voices for reliable offline dubbing. Only expose network-backed voices
     * when the engine has no local voice in the requested language.
     */
    fun <T> preferOfflineVoices(
        voices: List<T>,
        requiresNetwork: (T) -> Boolean,
    ): List<T> {
        val offline = voices.filterNot(requiresNetwork)
        return if (offline.isNotEmpty()) offline else voices
    }

    /**
     * Prefer the current voice if it is listed; otherwise choose a listed voice, and finally
     * fall back to the current voice when an OEM TTS engine omits it from getVoices().
     */
    fun <T> chooseDefaultVoice(
        current: T?,
        available: List<T>,
        name: (T) -> String,
    ): T? {
        val currentName = current?.let(name)
        return currentName?.let { candidate ->
            available.firstOrNull { name(it) == candidate }
        } ?: available.firstOrNull() ?: current
    }

    /**
     * Voice index 0 in the catalog is the engine's default. Nonnegative IDs address the remaining
     * entries directly. Do not wrap unavailable/old IDs onto another real voice: that makes the
     * same person silently change voice when a phone's catalog changes.
     */
    fun indexForSpeaker(speakerId: Int, voiceCount: Int): Int {
        if (voiceCount <= 1 || speakerId < 0) return 0
        return if (speakerId < voiceCount - 1) speakerId + 1 else 0
    }

    /** Choose per-person voices without accidentally applying Kokoro's preference to system TTS. */
    fun voiceForCanonicalSpeaker(
        engineId: String,
        canonicalSpeakerId: Int,
        systemChineseVoiceCount: Int,
        preferredKokoroVoice: Int,
    ): Int {
        if (canonicalSpeakerId <= 0) return -1
        if (engineId == "system") {
            val alternatives = (systemChineseVoiceCount - 1).coerceAtLeast(0)
            return if (alternatives == 0) -1 else Math.floorMod(canonicalSpeakerId - 1, alternatives)
        }
        if (engineId != "kokoro" || canonicalSpeakerId !in 1..53) return -1
        val candidate = canonicalSpeakerId - 1
        val preferred = preferredKokoroVoice.coerceIn(0, 53)
        return if (candidate >= preferred) candidate + 1 else candidate
    }

    /** Serialize names in their exact synthesis order while keeping preferences bounded. */
    fun encodeVoiceCatalog(names: List<String>): String =
        names.asSequence()
            .map { it.replace(CATALOG_SEPARATOR, "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CATALOG_VOICES)
            .joinToString(CATALOG_SEPARATOR)
            .take(MAX_CATALOG_CHARS)

    fun decodeVoiceCatalog(encoded: String?): List<String> {
        if (encoded.isNullOrBlank() || encoded.length > MAX_CATALOG_CHARS) return emptyList()
        return encoded.split(CATALOG_SEPARATOR)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_CATALOG_VOICES)
            .toList()
    }

    fun displayLabel(speakerId: Int, engineId: String, catalog: List<String>): String {
        if (engineId == "system") {
            if (speakerId < 0) return catalog.firstOrNull()?.let { "系统默认 · $it" } ?: "系统默认音色"
            return catalog.getOrNull(speakerId + 1) ?: "系统音色编号 $speakerId（当前不可用）"
        }
        return if (speakerId < 0) "默认音色" else "音色 $speakerId"
    }
}
