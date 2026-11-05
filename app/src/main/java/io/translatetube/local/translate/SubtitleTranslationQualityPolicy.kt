package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale
import io.translatetube.local.tts.TtsTextPolicy

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
     * 短字幕（人名、地名、品牌、外来词）只有在原译词数一致、逐词拼写相同或仅有一个轻微字符差异时，
     * 才能作为无需翻译的保留项。这样可接受 Sarah → Sara，但不会把 John → David 误判成人名。
     * 首次翻译仍严格要求中文；仅在重试、缓存恢复和最终配音校验中启用此例外。
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
        if (SubtitleInterjectionPolicy.chineseFor(sourceText, sourceLanguage, targetLanguage) != null) return false

        val source = sourceText.trim()
        val translated = translatedText.trim()
        if (source.length > 40 || translated.length > 48) return false
        // Common expressions and ordinary words must never use the post-retry name exception.
        if (normalize(source) in commonShortPhrases || hasCommonEnglishWord(source)) return false
        val sourceTokens = shortLatinTokens(source) ?: return false
        val translatedTokens = shortLatinTokens(translated) ?: return false
        if (sourceTokens.size != translatedTokens.size) return false
        // Only title-cased name-like text can use the spelling exception. All-caps phrases
        // (for example "NO WAY") are not names; acronyms are handled by the exact-term rule.
        if (!hasTitleCasedWords(source) || !hasTitleCasedWords(translated)) return false

        return sourceTokens.indices.all { index ->
            hasSameOrNearSpelling(sourceTokens[index], translatedTokens[index])
        }
    }

    /** 1~3 个拉丁词；允许字幕包裹符号与行尾标点，但不放行数字和其他文字系统。 */
    private fun shortLatinTokens(value: String): List<String>? {
        val rawTokens = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty() || rawTokens.size > 3) return null
        return rawTokens.map { rawToken ->
            // Subtitle speaker labels/names are often wrapped as "(John):", "[Sarah]" or "“John”".
            // Remove only boundary punctuation; keep apostrophes, hyphens and periods inside names.
            val token = rawToken.trim { it in shortTokenBoundaryPunctuation }
            if (token.isEmpty()) return null
            val letters = token.filter { it.isLetter() }
            val dottedInitial = letters.length == 1 && token.endsWith(".") && letters.first().isUpperCase()
            if (letters.length < 2 && !dottedInitial) return null
            if (token.any { it.isDigit() || (!it.isLetter() && it !in shortTokenInnerPunctuation) }) return null
            if (letters.any { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return null
            letters.lowercase(Locale.ROOT)
        }
    }

    private const val shortTokenBoundaryPunctuation = "\"“”‘’()[]{}【】「」『』〈〉《》:;!?，。！？：；、,–—"
    private const val shortTokenInnerPunctuation = ".'’-"

    private fun hasTitleCasedWords(value: String): Boolean =
        value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { token ->
            val letters = token.filter { it.isLetter() }
            letters.isNotEmpty() && letters.first().isUpperCase() &&
                (letters.size == 1 || letters.drop(1).all { it.isLowerCase() })
        }

    /** Same spelling, or one insertion/deletion/substitution on a sufficiently long name token. */
    private fun hasSameOrNearSpelling(source: String, translated: String): Boolean {
        if (source == translated) return true
        if (source.length < 5 || translated.length < 4) return false
        if (kotlin.math.abs(source.length - translated.length) > 1) return false
        if (source.first() != translated.first()) return false
        val shorter = minOf(source.length, translated.length)
        val longer = maxOf(source.length, translated.length)
        if (shorter * 5 < longer * 4) return false

        var sourceIndex = 0
        var translatedIndex = 0
        var edits = 0
        while (sourceIndex < source.length && translatedIndex < translated.length) {
            if (source[sourceIndex] == translated[translatedIndex]) {
                sourceIndex++
                translatedIndex++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                source.length > translated.length -> sourceIndex++
                translated.length > source.length -> translatedIndex++
                else -> {
                    sourceIndex++
                    translatedIndex++
                }
            }
        }
        if (sourceIndex < source.length || translatedIndex < translated.length) edits++
        return edits <= 1
    }

    private fun diagnosticShortSpellingMatch(source: String, translated: String): Boolean {
        val sourceTokens = shortLatinTokens(source) ?: return false
        val translatedTokens = shortLatinTokens(translated) ?: return false
        return sourceTokens.size == translatedTokens.size && sourceTokens.indices.all { index ->
            hasSameOrNearSpelling(sourceTokens[index], translatedTokens[index])
        }
    }

    /** Shared acceptance check for cache recovery, retry context and the final dubbing guard. */
    fun isUsableChineseTranslation(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String = "zh",
        acceptShortUnchanged: Boolean = false,
    ): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        if (!TtsTextPolicy.hasSpeakableContent(translatedText)) return false
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) return false
        val sourceEcho = isSourceEcho(sourceText, translatedText, sourceLanguage)
        val targetMismatch = isTargetLanguageMismatch(sourceText, translatedText, sourceLanguage, targetLanguage)
        if (!sourceEcho && !targetMismatch) return true
        return acceptShortUnchanged && isAcceptableShortUntranslatedCue(
            sourceText, translatedText, sourceLanguage, targetLanguage,
        )
    }

    // High-frequency English words must never be treated as untranslated names merely
    // because subtitles capitalize the first letter or the model returns the line in uppercase.
    private val commonShortWords = setOf(
        "about", "after", "again", "all", "also", "always", "am", "an", "and", "any", "are",
        "around", "as", "ask", "at", "away", "back", "bad", "be", "because", "before", "best",
        "better", "big", "but", "by", "bye", "call", "can", "care", "come", "could", "day",
        "did", "do", "does", "down", "each", "enough", "even", "ever", "every", "fine", "first",
        "for", "from", "fun", "get", "give", "go", "going", "good", "goodbye", "got", "great",
        "had", "has", "have", "he", "hello", "her", "here", "hey", "him", "his", "how", "i",
        "if", "in", "into", "is", "it", "its", "just", "keep", "know", "last", "later", "leave",
        "let", "like", "little", "listen", "live", "look", "lot", "love", "make", "many", "maybe",
        "me", "more", "most", "much", "must", "my", "name", "near", "need", "never", "new",
        "next", "nice", "no", "not", "now", "of", "off", "on", "one", "only", "or", "other",
        "our", "out", "over", "own", "please", "problem", "really", "right", "run", "same", "say",
        "see", "she", "should", "so", "some", "something", "sorry", "still", "stop", "sure",
        "take", "taxi", "than", "thank", "thanks", "that", "the", "their", "them", "then",
        "there", "these", "they", "thing", "think", "this", "time", "to", "too", "try", "two",
        "up", "us", "very", "wait", "want", "was", "we", "well", "were", "what", "when",
        "where", "which", "while", "who", "why", "with", "work", "world", "would", "yes",
        "you", "your", "afk", "asap", "bff", "brb", "btw", "fyi", "gg", "idk", "imo", "irl",
        "lmao", "lmk", "lol", "nvm", "omg", "rofl", "smh", "tbh", "tmi", "wtf",
    )

    // Values use normalize()'s punctuation-free representation.
    private val commonShortPhrases = setOf(
        "goodmorning", "goodafternoon", "goodevening", "goodnight",
        "thankyou", "thanksalot", "howareyou", "nicetomeetyou",
        "seeyou", "takecare", "welldone", "comeon", "letsgo",
        "goodluck", "happybirthday", "haveaniceday", "whatsup",
        "helloworld", "noway", "noproblem", "notyet", "nevermind",
        "allright", "alright", "ofcourse", "dontworry", "becareful",
        "getout", "goaway", "comeback", "rightnow", "notnow",
        "onemore", "whatswrong", "goodjob", "nicework",
    )

    private fun hasCommonEnglishWord(value: String): Boolean =
        value.split(wordSeparatorRegex).any { token -> normalize(token) in commonShortWords }

    /** 原文里没有任何可朗读内容（只有 ♪、…、- 之类的符号）：不需要翻译，也不会有配音。 */
    fun hasNothingToSpeak(sourceText: String?): Boolean =
        !sourceText.isNullOrBlank() && !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(sourceText)

    /**
     * Detect a likely wrong-language response for Chinese subtitle output.
     * Names, abbreviations, URLs and numbers may remain without Han text only when unchanged.
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
        val speakable = TtsTextPolicy.hasSpeakableContent(output)
        val shortSpellingMatch = diagnosticShortSpellingMatch(sourceText, output)
        val shortCueAccepted = isAcceptableShortUntranslatedCue(sourceText, output, sourceLanguage, targetLanguage)
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
            append("; shortTokenSpellingCompatible=").append(shortSpellingMatch)
            append("; shortUntranslatedCueAccepted=").append(shortCueAccepted)
            append("; targetLanguageMismatch=").append(mismatch != null)
            append("; mismatchReason=").append(mismatch?.let { it.take(180) } ?: "无")
        }
    }

    /**
     * Detect common assistant-style meta replies that look like subtitles because they
     * contain Chinese characters. Equivalent source dialogue is explicitly preserved.
     */
    fun translationMetaResponseReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage) ||
            SourceLanguagePolicy.isChinese(sourceLanguage)
        ) return null

        val source = sourceText.lowercase(Locale.ROOT)
        val output = normalize(translatedText)

        val sourceExpressesAiIdentity = listOf(
            "as an ai language model", "i am an ai language model", "i'm an ai language model",
            "as an artificial intelligence language model", "i am an artificial intelligence language model",
        ).any(source::contains)
        val aiIdentityReply = listOf(
            "作为ai语言模型", "作为人工智能语言模型", "作为一个ai语言模型",
            "作为ai助手", "我是ai语言模型",
        ).any(output::contains)
        if (aiIdentityReply && !sourceExpressesAiIdentity) {
            return "译文包含 AI 身份说明，疑似模型说明而非字幕内容"
        }

        val sourceRequestsText = listOf(
            "please provide the original text", "please provide the text to translate",
            "please provide the sentence to translate", "please send me the text",
            "please send me the original text", "send me the original text",
            "give me the text to translate", "provide the subtitle text",
        ).any(source::contains)
        val asksForInput = listOf("请提供", "请发送", "请给出", "请粘贴").any(output::startsWith) &&
            listOf("需要翻译", "待翻译", "要翻译", "原文", "文本", "字幕").any(output::contains)
        if (asksForInput && !sourceRequestsText) {
            return "译文要求补充原文或待翻译文本，疑似模型提示而非字幕译文"
        }

        val sourceExpressesTranslationFailure =
            source.contains("translate") && (source.contains("cannot") || source.contains("unable"))
        val refusal = listOf(
            "无法翻译", "不能翻译", "无法提供翻译", "不能提供翻译",
            "无法帮助你翻译", "不能帮助你翻译", "无法协助翻译", "不能协助翻译",
            "无法帮助完成这个请求", "无法协助完成这个请求",
        ).any(output::contains)
        val apology = output.startsWith("抱歉") || output.startsWith("很抱歉") || output.startsWith("对不起")
        if (((apology && refusal) || output.startsWith("无法提供翻译") ||
                output.startsWith("不能提供翻译")) && !sourceExpressesTranslationFailure
        ) return "译文疑似模型拒绝回复，不是当前字幕的翻译内容"

        val explanatoryReply = listOf(
            "这句话的意思是", "这段话的意思是", "这段文本的意思是", "它的意思是", "其意思是",
        ).any(output::startsWith)
        if (explanatoryReply && listOf("means", "meaning", "signifies").none(source::contains)) {
            return "译文在解释句意而不是翻译字幕原句，疑似模型说明内容"
        }
        return null
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
        if (SourceLanguagePolicy.isChinese(sourceLanguage)) return null
        // Names, abbreviations and numbers may remain Latin only when the model kept the same term.
        if (isNonTranslatableCue(sourceText) && normalize(sourceText) == normalize(translatedText)) return null
        if (isPunctuationOnlyTranslation(sourceText, translatedText)) {
            return "模型只返回标点/符号，未返回可翻译文字"
        }
        translationMetaResponseReason(
            sourceText, translatedText, sourceLanguage, targetLanguage,
        )?.let { return it }

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
        val sourceTokens = sourceText.split(wordSeparatorRegex)
            .filter { it.isNotBlank() }
            .map(::normalize)
            .toSet()
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
        // Even a short untranslated fragment (for example "你好 hello") is not a Chinese
        // translation. Known source names, brands, acronyms and model identifiers are excluded
        // above, so a small amount of ordinary Latin text should not be hidden by a long Han prefix.
        return foreignLetters >= 2 && hanCount <= foreignLetters
    }

    private fun isLikelyRetainedTerm(
        token: String,
        letters: String,
        sourceTokens: Set<String>,
    ): Boolean {
        val normalized = normalize(token)
        // Common English words remain translatable even when sentence-initial or uppercased.
        if (normalized in commonShortWords) return false
        if (token.any { it.isDigit() }) return true
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return true
        if (letters.drop(1).any { it.isUpperCase() }) return true
        return letters.length >= 2 && letters.first().isUpperCase() && normalized in sourceTokens
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
        // Common greetings/commands remain translatable even when every word is capitalized.
        if (normalize(value) in commonShortPhrases || hasCommonEnglishWord(value)) return false
        val tokens = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 3) return false
        // 带感叹/问号/分号的是句子，不是词条。
        if (value.any { it in "!?;:" }) return false
        val letters = value.filter { it.isLetter() }
        // 只对拉丁字母的词条放行；日文汉字、西里尔文等原样返回仍按没翻译处理。
        if (letters.any { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return false

        if (tokens.size == 1) {
            // Exact acronyms, opaque product identifiers, and internal-capitalization brands
            // may remain unchanged. Ordinary uppercase words are excluded by commonShortWords.
            if (letters.length in 2..10 && letters.all { it.isUpperCase() }) return true
            if (value.any { it.isDigit() } && letters.isNotEmpty()) return true
            if (letters.drop(1).any { it.isUpperCase() }) return true
            return false
        }

        // "iPhone 15" is a two-token model/brand identifier, not a translated sentence.
        if (tokens.size == 2 &&
            tokens.first().filter { it.isLetter() }.drop(1).any { it.isUpperCase() } &&
            tokens.last().all { it.isDigit() }
        ) return true

        // Multiword names are accepted only when every word uses true title case and no token
        // is a common English word. All-caps sentences such as "NO WAY" cannot pass this guard.
        return hasTitleCasedWords(value) && !hasCommonEnglishWord(value)
    }

    /** Ignore case, whitespace and punctuation so formatting-only changes do not pass as translation. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
}
