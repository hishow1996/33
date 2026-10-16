package io.translatetube.local.translate

/** 翻译引擎统一接口 */
interface TranslationEngine {
    val id: String
    val displayName: String
    /** 支持模型列表拉取的引擎返回列表；传统固定引擎返回 null */
    suspend fun listModels(): List<String>? = null
    /**
     * Test the configured inference endpoint with a single short request. This validates
     * the selected model's generation route rather than merely checking the model catalog.
     */
    suspend fun testConnection() {
        val result = translate(
            listOf("Translate the word OK into Chinese. Reply with the translation only."),
            "en",
            "zh",
        )
        if (result.size != 1 || result.first().isBlank()) {
            throw IllegalStateException("接口有响应，但模型没有返回可识别的译文格式")
        }
    }

    /** 批量翻译；失败抛异常（上层重试/降级） */
    suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String>

    /** Optional continuity context from already-translated cues immediately before this batch. */
    suspend fun translateWithContext(
        lines: List<String>,
        srcLang: String,
        tgtLang: String,
        context: Pair<List<String>, List<String>>?,
    ): List<String> = translate(lines, srcLang, tgtLang)
}

object LangMap {
    /** YouTube/ISO 码 → ML Kit 支持码 */
    private val mlkit = mapOf(
        "zh" to "zh", "zh-Hans" to "zh", "zh-CN" to "zh", "zh-Hant" to "zh-TW", "zh-TW" to "zh-TW",
        "en" to "en", "ja" to "ja", "ko" to "ko", "fr" to "fr", "de" to "de", "es" to "es",
        "pt" to "pt", "ru" to "ru", "it" to "it", "ar" to "ar", "hi" to "hi", "th" to "th",
        "vi" to "vi", "id" to "id", "nl" to "nl", "pl" to "pl", "tr" to "tr", "sv" to "sv",
        "da" to "da", "fi" to "fi", "no" to "no", "nb" to "no", "el" to "el", "he" to "he",
        "hu" to "hu", "cs" to "cs", "ro" to "ro", "uk" to "uk", "bn" to "bn", "ms" to "ms",
    )
    /**
     * Normalize BCP-47 tags and provider variants such as zh_CN and EN-us.
     * ML Kit expects canonical language IDs; Traditional Chinese maps to zh-TW.
     */
    fun mlkit(code: String): String {
        val normalized = code.trim().replace('_', '-')
        if (normalized.isEmpty()) return ""
        val exact = mlkit.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }
        if (exact != null) return exact.value
        val lower = normalized.lowercase(java.util.Locale.ROOT)
        if (lower == "zh-hant" || lower.startsWith("zh-hant-")) return "zh-TW"
        if (lower == "zh-hans" || lower.startsWith("zh-hans-")) return "zh"
        return lower.substringBefore('-')
    }

    /** Unknown source language falls back to English; never pass UI sentinel "auto" to ML Kit. */
    fun mlkitSource(code: String): String {
        val normalized = code.trim().replace('_', '-')
        val primary = normalized.substringBefore('-').lowercase(java.util.Locale.ROOT)
        // Detection sentinels can carry script/region suffixes (for example und-Latn).
        return if (
            normalized.isEmpty() ||
            primary in setOf("auto", "und", "unknown", "undefined")
        ) "en" else mlkit(normalized)
    }

    fun isMlkitSupported(code: String): Boolean = mlkit(code) in mlkit.values

    /** Canonical code for Google's public web and Cloud Translation endpoints. */
    internal fun google(code: String, target: Boolean = false): String {
        val raw = code.trim().replace('_', '-')
        val normalized = raw.lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty() || normalized == "auto" ||
            normalized == "und" || normalized == "unknown" || normalized == "undefined"
        ) return if (target) "zh-CN" else "auto"
        return when {
            normalized in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn", "zh-sg") ||
                normalized.startsWith("zh-hans-") -> "zh-CN"
            normalized in setOf("zh-tw", "zh-hant", "zh-hant-tw", "zh-hk") ||
                normalized.startsWith("zh-hant-") -> "zh-TW"
            normalized == "nb" || normalized == "nn" -> "no"
            normalized == "iw" -> "he"
            normalized == "in" -> "id"
            else -> raw.substringBefore('-').lowercase(java.util.Locale.ROOT)
        }
    }

    /** Human-readable language labels for model prompts, including region-tagged subtitles. */
    fun display(code: String): String {
        val normalized = code.trim().replace('_', '-').lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty() || normalized in setOf("auto", "und", "unknown", "undefined")) {
            return "the original language"
        }
        if (normalized.startsWith("zh-hant") || normalized == "zh-tw" ||
            normalized.startsWith("zh-tw-") || normalized == "zh-hk"
        ) return "繁體中文"
        if (normalized.startsWith("zh") || normalized == "cmn") return "中文"
        if (normalized == "yue") return "粤语"
        return when (mlkit(code)) {
            "en" -> "英语"; "ja" -> "日语"; "ko" -> "韩语"; "fr" -> "法语"; "de" -> "德语"
            "es" -> "西班牙语"; "pt" -> "葡萄牙语"; "ru" -> "俄语"; "it" -> "意大利语"
            "ar" -> "阿拉伯语"; "hi" -> "印地语"; "th" -> "泰语"; "vi" -> "越南语"
            "id" -> "印尼语"; "nl" -> "荷兰语"; "pl" -> "波兰语"; "tr" -> "土耳其语"
            "sv" -> "瑞典语"; "da" -> "丹麦语"; "fi" -> "芬兰语"; "no" -> "挪威语"
            else -> code.trim().ifBlank { "the original language" }
        }
    }
}
