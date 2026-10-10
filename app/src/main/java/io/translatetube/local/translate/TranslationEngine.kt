package io.translatetube.local.translate

/** 翻译引擎统一接口 */
interface TranslationEngine {
    val id: String
    val displayName: String
    /** 支持模型列表拉取的引擎返回列表；传统固定引擎返回 null */
    suspend fun listModels(): List<String>? = null
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
    fun mlkit(code: String) = mlkit[code] ?: code.substringBefore('-')
    fun isMlkitSupported(code: String) = mlkit.containsKey(code) || mlkit.containsValue(code)

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
