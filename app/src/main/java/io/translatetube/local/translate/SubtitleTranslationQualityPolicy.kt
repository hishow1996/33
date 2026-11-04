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
     * 短字幕（人名、地名、品牌、外来词，如 "Sarah." / "Taxi" / "Tom Hanks?"）译文可以和原文相同，
     * 也可能只是换了拉丁字母写法（Sarah → Sara）。这类行没有可翻的内容，模型/引擎反复返回也不会变；
     * 普通整句原样返回才是翻译失败。
     *
     * 只在重试之后才使用：第一次返回时仍按失败处理，让引擎有机会真的翻成中文。
     * 条件很窄：目标是中文、原文和译文都是 1~3 个纯拉丁字母词，且多词时每个词都首字母大写。
     */
    fun isAcceptableShortUntranslatedCue(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String = "zh",
    ): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        if (!SourceLanguagePolicy.isChinese(targetLanguage)) return false
        if (SourceLanguagePolicy.isChinese(SourceLanguagePolicy.normalizeTag(sourceLanguage))) return false
        // A short word with a known Chinese interjection equivalent must not be cached as an
        // untranslated proper name (for example Hello -> Hello instead of Hello -> 你好).
        if (SubtitleInterjectionPolicy.chineseFor(sourceText, sourceLanguage, targetLanguage) != null) {
            return false
        }
        val source = sourceText.trim()
        val translated = translatedText.trim()
        if (source.length > 40 || translated.length > 48) return false
        if (!translated.any { it.isLetterOrDigit() }) return false
        val sourceTokens = shortLatinTokens(source) ?: return false
        val translatedTokens = shortLatinTokens(translated) ?: return false
        if (sourceTokens.size > 1 && !sourceTokens.all { it.first().isUpperCase() }) return false
        return translatedTokens.isNotEmpty()
    }

    /** 1~3 个词、每个词都由拉丁字母（可含 ' . - ’）组成时返回词列表，否则返回 null。 */
    private fun shortLatinTokens(value: String): List<String>? {
        val tokens = value.trim().trim('"', '\'', '“', '”', '‘', '’').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 3) return null
        val cleaned = tokens.map { token -> token.trim { !it.isLetter() } }
        if (cleaned.any { it.length < 2 }) return null
        if (cleaned.any { token ->
                token.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN } ||
                    token.any { it.isDigit() }
            }
        ) return null
        return cleaned
    }

    /** 原文里没有任何可朗读内容（只有 ♪、…、- 之类的符号）：不需要翻译，也不会有配音。 */
    fun hasNothingToSpeak(sourceText: String?): Boolean =
        !sourceText.isNullOrBlank() && !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(sourceText)

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
    ): Boolean = targetLanguageMismatchReason(
        sourceText, translatedText, sourceLanguage, targetLanguage,
    ) != null

    /**
     * Content-free evidence describing the exact quality checks applied to one source/target pair.
     * Counts are based on the full cleaned model output, not on the shortened diagnostic preview.
     */
    fun diagnosticEvidence(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        if (sourceText.isNullOrBlank()) return "source=empty; target=${targetLanguage}"
        val output = translatedText.orEmpty()
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        var lettersOrDigits = 0
        output.forEach { char ->
            if (char.isLetterOrDigit()) lettersOrDigits++
            when (Character.UnicodeScript.of(char.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.LATIN -> latin++
                else -> Unit
            }
        }
        val mismatch = targetLanguageMismatchReason(
            sourceText = sourceText,
            translatedText = output,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
        )
        val nonTranslatable = isNonTranslatableCue(sourceText)
        val sourceEcho = isSourceEcho(sourceText, output, sourceLanguage)
        val punctuationOnly = isPunctuationOnlyTranslation(sourceText, output)
        val speakable = io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(output)
        return buildString {
            append("sourceLanguage=").append(sourceLanguage.ifBlank { "auto" })
            append("; targetLanguage=").append(targetLanguage)
            append("; sourceChars=").append(sourceText.length)
            append("; outputChars=").append(output.length)
            append("; outputLettersOrDigits=").append(lettersOrDigits)
            append("; han=").append(han)
            append("; kana=").append(kana)
            append("; hangul=").append(hangul)
            append("; latin=").append(latin)
            append("; sourceEcho=").append(sourceEcho)
            append("; nonTranslatableSourceCue=").append(nonTranslatable)
            append("; punctuationOnlyOutput=").append(punctuationOnly)
            append("; speakable=").append(speakable)
            append("; targetLanguageMismatch=").append(mismatch != null)
            append("; mismatchReason=").append(mismatch?.let { it.take(180) } ?: "无")
        }
    }

    /** Content-free explanation for a rejected Chinese-target response; subtitle text is not logged. */
    fun targetLanguageMismatchReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage)) return null
        if (SourceLanguagePolicy.isChinese(sourceLanguage) || isNonTranslatableCue(sourceText)) return null
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) {
            return "模型只返回标点/符号，未返回可翻译文字"
        }

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
                else -> Unit
            }
        }
        if (lettersOrDigits == 0) return null
        // Prefer script-specific diagnostics before the generic no-Han fallback.
        if (kana >= 2 && kana > han) {
            return "译文日文假名占主导（汉字=$han，日文假名=$kana，韩文音节=$hangul）"
        }
        if (hangul >= 2 && hangul > han) {
            return "译文韩文音节占主导（汉字=$han，日文假名=$kana，韩文音节=$hangul）"
        }
        if (han == 0) {
            return "未检测到汉字（汉字=0，日文假名=$kana，韩文音节=$hangul，字母/数字=$lettersOrDigits）"
        }
        if (kana >= 2 && kana > han) {
            return "译文日文假名占主导（汉字=$han，日文假名=$kana，韩文音节=$hangul）"
        }
        if (hangul >= 2 && hangul > han) {
            return "译文韩文音节占主导（汉字=$han，日文假名=$kana，韩文音节=$hangul）"
        }
        if (hasDominantForeignText(sourceText, translatedText, han)) {
            return "译文保留过多非汉字外语文本，汉字字符占比低于质量阈值"
        }
        return null
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
     * 翻译结果与原文相同可以作为有效中文配音文本；普通句子/单词原样返回仍按翻译失败处理。
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
