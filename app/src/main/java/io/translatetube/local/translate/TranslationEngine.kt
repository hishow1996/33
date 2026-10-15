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
    fun mlkitSource(code: String): String =
        mlkit(if (code.isBlank() || code.equals("auto", ignoreCase = true)) "en" else code)

    fun isMlkitSupported(code: String): Boolean = mlkit(code) in mlkit.values

    /** Canonical code for Google's public web and Cloud Translation endpoints. */
    internal fun google(code: String, target: Boolean = false): String {
        val raw = code.trim().replace('_', '-')
        if (raw.isEmpty() || raw.equals("auto", ignoreCase = true)) return if (target) "zh-CN" else "auto"
        return when (raw.lowercase(java.util.Locale.ROOT)) {
            "zh", "zh-cn", "zh-hans", "zh-hans-cn", "zh-sg" -> "zh-CN"
            "zh-tw", "zh-hant", "zh-hant-tw", "zh-hk" -> "zh-TW"
            else -> raw.substringBefore('-').lowercase(java.util.Locale.ROOT)
        }
    }

    /** 引擎无关的显示名（空/未知 → 让 LLM 自行识别） */
    fun display(code: String) = when (code) {
        "zh", "zh-Hans", "zh-CN" -> "中文"; "en" -> "英语"; "ja" -> "日语"; "ko" -> "韩语"
        "fr" -> "法语"; "de" -> "德语"; "es" -> "西班牙语"; "pt" -> "葡萄牙语"; "ru" -> "俄语"
        "it" -> "意大利语"; "ar" -> "阿拉伯语"; "hi" -> "印地语"; "th" -> "泰语"; "vi" -> "越南语"
        "id" -> "印尼语"; "nl" -> "荷兰语"; "pl" -> "波兰语"; "tr" -> "土耳其语"; "sv" -> "瑞典语"
        "", "auto" -> "the original language"
        else -> code
    }
}
