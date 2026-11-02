package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale

/** Shared validation for live translation batches and restored subtitle caches. */
internal object SubtitleTranslationQualityPolicy {
    private val wordSeparatorRegex = Regex("""[^\p{L}\p{N}]+""")
    private val urlPattern = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val emailPattern = Regex(
        """^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$""",
        RegexOption.IGNORE_CASE,
    )

    fun isSourceEcho(sourceText: String?, translatedText: String?, sourceLanguage: String): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        val language = SourceLanguagePolicy.normalizeTag(sourceLanguage)
        if (SourceLanguagePolicy.isChinese(language)) return false

        // A cue with real words "translated" into only punctuation (for example "。") is a failed
        // translation. Treat it like an echoed source row so it is retried or left untranslated
        // instead of showing "。" as the subtitle and leaving nothing to dub.
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) return true

        // Numbers, symbol-only cues, URLs and email addresses are valid even when unchanged.
        // Treating them as failed translations can make a perfectly valid subtitle batch fail.
        if (isNonTranslatableCue(sourceText)) return false
        val source = normalize(sourceText)
        val translated = normalize(translatedText)
        return source.isNotEmpty() && source == translated
    }

    /**
     * Detect a likely wrong-language response for Chinese subtitle output.
     * Names, abbreviations, URLs, numbers and symbol-only cues may legitimately have no Han text.
     * Japanese kana or Korean Hangul dominating a result is rejected even when a few Han
     * characters happen to be present.
     */
    fun isTargetLanguageMismatch(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        if (!SourceLanguagePolicy.isChinese(targetLanguage)) return false
        if (SourceLanguagePolicy.isChinese(sourceLanguage) || isNonTranslatableCue(sourceText)) return false
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) return true

        var han = 0
        var kana = 0
        var hangul = 0
        var lettersOrDigits = 0
        translatedText.forEach { char ->
            if (char.isLetterOrDigit()) lettersOrDigits++
            when (Character.UnicodeScript.of(char.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
            }
        }
        if (lettersOrDigits == 0) return false
        if (han == 0) return true
        if ((kana >= 2 && kana > han) || (hangul >= 2 && hangul > han)) return true
        return hasDominantForeignText(sourceText, translatedText, han)
    }

    /**
     * Reject a Chinese-prefixed response that leaves most of the sentence in another language.
     * Proper names, acronyms, model names and brands that are also present in the source are
     * excluded, so ordinary Chinese subtitles can retain terms such as Google Gemini or iPhone.
     */
    private fun hasDominantForeignText(
        sourceText: String,
        translatedText: String,
        hanCount: Int,
    ): Boolean {
        val sourceTokens = sourceText.split(wordSeparatorRegex).filter { it.isNotBlank() }.toSet()
        var foreignLetters = 0
        translatedText.split(wordSeparatorRegex).forEach { token ->
            val letters = token.filter { it.isLetter() }
            if (letters.isEmpty() || isLikelyRetainedTerm(token, letters, sourceTokens)) {
                return@forEach
            }
            foreignLetters += letters.count {
                Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
            }
        }
        // A real Chinese sentence may contain a long product/person name. Only reject when at
        // least ten non-Han letters remain and Chinese characters account for less than 20%.
        return foreignLetters >= 10 && hanCount * 4 < foreignLetters
    }

    private fun isLikelyRetainedTerm(
        token: String,
        letters: String,
        sourceTokens: Set<String>,
    ): Boolean {
        if (token.any { it.isDigit() }) return true
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return true
        if (letters.drop(1).any { it.isUpperCase() }) return true
        return letters.length >= 2 && letters.first().isUpperCase() && token in sourceTokens
    }

    /**
     * True when the source contains letters/digits but the translation has none, e.g. "。" or "…".
     * Source cues that are themselves symbol-only (music notes, "...") are not affected.
     */
    fun isPunctuationOnlyTranslation(sourceText: String?, translatedText: String?): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        if (translatedText.any { it.isLetterOrDigit() }) return false
        return sourceText.any { it.isLetterOrDigit() }
    }

    private fun isNonTranslatableCue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.none { it.isLetter() } ||
            urlPattern.matches(trimmed) ||
            emailPattern.matches(trimmed) ||
            isTermLikeCue(trimmed)
    }

    /**
     * 专有名词、缩写、型号、签证类别（"H-1B"、"NASA"、"COVID-19"、"iPhone 15"、"Elon Musk"）本来就不翻译，
     * 翻译结果与原文相同是正确的，不能当成"没翻译"而让这一句没有中文配音、只能回退原声。
     * 只放行很短的词条：普通句子/单词原样返回仍按翻译失败处理。
     */
    private fun isTermLikeCue(value: String): Boolean {
        // Compatibility forms (for example full-width Latin letters/digits) must reach the
        // NFKC-based source-echo comparison instead of being mistaken for unchanged acronyms.
        if (Normalizer.normalize(value, Normalizer.Form.NFKC) != value) return false
        val tokens = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 3) return false
        // 带感叹/问号/分号的是句子，不是词条。
        if (value.any { it in "!?;:" }) return false
        val letters = value.filter { it.isLetter() }
        // 只对拉丁字母的词条放行；日文汉字、西里尔文等原样返回仍按没翻译处理。
        if (letters.any { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return false
        // 全大写缩写 / 含数字的短词条
        if (letters.none { it.isLowerCase() }) return true
        if (value.any { it.isDigit() }) return true
        // 单个驼峰/品牌写法（SomeBrandName、YouTube）：首字母之后还有大写字母。
        if (tokens.size == 1 && letters.drop(1).any { it.isUpperCase() }) return true
        // 专有名词：每个词都以大写字母开头（Elon Musk、Silicon Valley）；单个首字母大写的普通词不算。
        return tokens.size >= 2 && tokens.all { token ->
            token.firstOrNull { it.isLetter() }?.isUpperCase() == true
        }
    }

    /** Ignore case, whitespace and punctuation so formatting-only changes do not pass as translation. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
}
