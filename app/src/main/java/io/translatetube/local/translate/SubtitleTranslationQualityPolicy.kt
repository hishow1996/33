package io.translatetube.local.translate

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import io.translatetube.local.tts.TtsTextPolicy
import io.translatetube.local.subs.SubtitleLanguageDetector

internal data class TranslationQualityDecision(
    val accepted: Boolean,
    val reasonCode: String? = null,
    val reason: String? = null,
    val sourceEcho: Boolean = false,
    val targetLanguageMismatchReason: String? = null,
    val shortUntranslatedAccepted: Boolean = false,
    val modelMetaResponseReason: String? = null,
    val scriptMismatchReason: String? = null,
    val numericMismatchReason: String? = null,
    val numericUnitAssociationMismatchReason: String? = null,
    val unitMismatchReason: String? = null,
    val semanticMismatchReason: String? = null,
)

/** Shared validation for live translation batches and restored subtitle caches. */
internal object SubtitleTranslationQualityPolicy {
    private val wordSeparatorRegex = Regex("""[^\p{L}\p{N}]+""")
    private val urlPattern = Regex("""^(?:https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val emailPattern = Regex(
        """^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitNumberPattern = Regex(
        """(?<!\d)-?\d+(?:[.,]\d+)*"""
    )
    private val explicitFractionPattern = Regex("""(?<!\d)-?\d+\s*/\s*\d+(?!\d)""")
    private val chineseFractionPattern = Regex("""([零〇一二两兩三四五六七八九十百千万萬亿億]+)分之([零〇一二两兩三四五六七八九十百千万萬亿億]+)""")
    private val chineseRangePattern = Regex("""([零〇一二两兩三四五六七八九十百千万萬亿億]+)(?:到|至|~|～|—)([零〇一二两兩三四五六七八九十百千万萬亿億]+)""")
    private val clockTimePattern = Regex("""(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)""")
    private val chineseNumeralPattern = Regex("""[零〇一二两兩三四五六七八九十百千万萬亿億点點半负負正]+""")
    private val chineseClockPattern = Regex("""[零〇一二两兩三四五六七八九十百千万萬亿億\d]+[点點](?:[零〇一二两兩三四五六七八九十\d]+分|半)?""")
    private val commaDecimalLanguages = setOf(
        "de", "fr", "es", "it", "pt", "nl", "ru", "pl", "tr", "uk", "sv", "no",
        "da", "fi", "cs", "sk", "hu", "ro", "bg", "el", "hr", "sr", "id",
    )

    private data class ExplicitUnitAlias(val id: String, val aliases: List<String>)
    private data class NumericUnitPair(val number: String, val unitId: String)

    private val englishNegation = Regex(
        """\b(?:not|no|never|none|nobody|nothing|neither|nor|nowhere|without|cannot|can't|couldn't|wouldn't|shouldn't|mustn't|won't|don't|doesn't|didn't|isn't|aren't|wasn't|weren't|hasn't|haven't|hadn't|dont|doesnt|didnt|isnt|arent|wasnt|werent|hasnt|havent|hadnt)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val neutralEnglishNegation = Regex(
        """\b(?:not\s+(?:only|just|merely|simply|bad|too\s+bad|wrong|uncommon|unusual|impossible|necessarily)|no\s+(?:wonder|problem|worries?))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val affirmativeChineseSurface = Regex(
        """没关系|沒關係|没问题|沒問題|没事|沒事|没错|沒錯|不错|不錯|不客气|不客氣|不妨|不久|不愧|不失|不时|不時|不坏|不壞|不差|没有错|沒有錯|不一定|未必|无疑|無疑|毫无疑问|毫無疑問|无论|無論|不少见|不少見|不罕见|不罕見|并非不可能|並非不可能|不是不可能|不是没有|不是沒有"""
    )
    private val chineseNegation = Regex(
        """从来没有|從來沒有|从未|從未|绝不|絕不|并非|並非|不是|不曾|未曾|不再|没有|沒有|没能|沒能|没法|沒法|无法|無法|未能|未完成|未收到|尚未|仍未|还没|还未|还没有|還沒|還未|還沒有|不允许|不允許|不可能|不应该|不應該|不需要|不必|不能|不会|不會|不可|不行|不想|不愿|不願|不肯|禁止|严禁|嚴禁|不得(?!不)|不(?!仅|僅|但|止|只|光|单|單|错|錯|客气|客氣|妨|久|愧|失|时|時|一定|凡|俗|朽|如|了)|没(?!错|錯|关系|關係|问题|問題|事)|沒(?!錯|關係|問題|事)|无|無"""
    )
    private val englishMalePronoun = Regex("""\b(?:he|him|his|himself)\b""", RegexOption.IGNORE_CASE)
    private val englishFemalePronoun = Regex("""\b(?:she|her|hers|herself)\b""", RegexOption.IGNORE_CASE)
    private val chineseMalePronoun = Regex("""(?<!其)他(?!们)""")
    private val chineseFemalePronoun = Regex("""她(?!们)""")
    private val englishProhibition = Regex(
        """\b(?:must\s+not|mustn't|not\s+allowed\s+to|forbidden|prohibited)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val englishObligation = Regex(
        """\b(?:must(?!\s+(?:be|have\b))|have\s+to|has\s+to|had\s+to|need\s+to|needs\s+to|required\s+to)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val englishRecommendation = Regex("""\b(?:should|ought\s+to)\b(?!\s+not\b)""", RegexOption.IGNORE_CASE)
    private val englishPossibility = Regex("""\b(?:may|might|possibly|perhaps|maybe)\b""", RegexOption.IGNORE_CASE)
    private val chineseProhibition = Regex("""禁止|严禁|嚴禁|不准|不允许|不允許|不得|不许|不許|不要|不能|不可以|絕不能|绝不能""")
    private val chineseObligation = Regex("""必须|必須|务必|務必|需要|有必要|一定要""")
    private val chineseRecommendation = Regex("""应该|應該|应当|應當|最好|理应|理應""")
    private val chinesePossibility = Regex("""可能|也许|也許|或许|或許|说不定|說不定""")
    private data class SemanticOpposition(
        val sourcePositive: Regex,
        val sourceNegative: Regex,
        val targetPositive: Regex,
        val targetNegative: Regex,
        val label: String,
    )

    // Only high-signal opposite action/direction pairs are checked. Ambiguous verbs and
    // contextual roles are deliberately left to the translation model.
    private val semanticOppositions = listOf(
        SemanticOpposition(
            Regex("""\b(?:come|comes|came|coming)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:go|goes|went|going)\b""", RegexOption.IGNORE_CASE),
            Regex("""来|來|过来|過來|来到|來到"""),
            Regex("""去|前往|过去|過去|离开|離開"""),
            "来/去方向",
        ),
        SemanticOpposition(
            Regex("""\benter(?:s|ed|ing)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:leave|leaves|left|leaving|exit|exits|exited|exiting)\b""", RegexOption.IGNORE_CASE),
            Regex("""进入|進入|入内|入內"""),
            Regex("""离开|離開|离去|離去|退出|出去"""),
            "进入/离开动作",
        ),
        SemanticOpposition(
            Regex("""\b(?:increase|increases|increased|increasing|rise|rises|rose|risen|rising|grow|grows|grew|grown|growing)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:decrease|decreases|decreased|decreasing|fall|falls|fell|fallen|falling|drop|drops|dropped|dropping)\b""", RegexOption.IGNORE_CASE),
            Regex("""增加|上升|增长|增長|提高|提升|扩大|擴大"""),
            Regex("""减少|減少|下降|降低|缩小|縮小|下跌"""),
            "增加/减少关系",
        ),
        SemanticOpposition(
            Regex("""\b(?:buy|buys|bought|buying|purchase|purchases|purchased|purchasing)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:sell|sells|sold|selling)\b""", RegexOption.IGNORE_CASE),
            Regex("""买|買|购买|購買|买入|買入"""),
            Regex("""卖|賣|出售|售出"""),
            "买入/卖出动作",
        ),
        SemanticOpposition(
            Regex("""\b(?:win|wins|won|winning)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:lose|loses|lost|losing)\b""", RegexOption.IGNORE_CASE),
            Regex("""赢|贏|获胜|獲勝|胜利|勝利"""),
            Regex("""输|輸|失败|失敗|落败|落敗"""),
            "获胜/失败结果",
        ),
        SemanticOpposition(
            Regex("""\bopen(?:s|ed|ing)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bclose(?:s|d|ing)?\b""", RegexOption.IGNORE_CASE),
            Regex("""打开|打開|开启|開啟|开放|開放|开着|開著"""),
            Regex("""关闭|關閉|关上|關上|闭合|閉合"""),
            "打开/关闭状态",
        ),
        SemanticOpposition(
            Regex("""\b(?:more|greater|higher)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:less|fewer|lower)\b""", RegexOption.IGNORE_CASE),
            Regex("""更多|较多|較多|更大|更高"""),
            Regex("""更少|较少|較少|更小|更低"""),
            "比较数量或程度",
        ),
    )

    // Short symbols are recognized only beside explicit numbers, not as ordinary subtitle letters.
    private val explicitUnitAliases = listOf(
        ExplicitUnitAlias("km_per_hour", listOf("km/h", "kph", "公里/小时", "千米/小时")),
        ExplicitUnitAlias("miles_per_hour", listOf("mph", "miles per hour", "英里/小时")),
        ExplicitUnitAlias("kilometer", listOf("kilometers", "kilometer", "kilometres", "kilometre", "km", "公里", "千米")),
        ExplicitUnitAlias("mile", listOf("miles", "mile", "英里")),
        ExplicitUnitAlias("centimeter", listOf("centimeters", "centimeter", "centimetres", "centimetre", "cm", "厘米", "公分")),
        ExplicitUnitAlias("millimeter", listOf("millimeters", "millimeter", "millimetres", "millimetre", "mm", "毫米")),
        ExplicitUnitAlias("meter", listOf("meters", "meter", "metres", "metre", "m", "米")),
        ExplicitUnitAlias("kilogram", listOf("kilograms", "kilogram", "kg", "公斤", "千克")),
        ExplicitUnitAlias("milligram", listOf("milligrams", "milligram", "mg", "毫克")),
        ExplicitUnitAlias("gram", listOf("grams", "gram", "克")),
        ExplicitUnitAlias("milliliter", listOf("milliliters", "milliliter", "millilitres", "millilitre", "ml", "毫升")),
        ExplicitUnitAlias("liter", listOf("liters", "liter", "litres", "litre", "升", "公升")),
        ExplicitUnitAlias("hour", listOf("hours", "hour", "hrs", "hr", "小时")),
        ExplicitUnitAlias("minute", listOf("minutes", "minute", "mins", "min", "分钟")),
        ExplicitUnitAlias("second", listOf("seconds", "second", "secs", "sec", "秒")),
        ExplicitUnitAlias("celsius", listOf("°c", "celsius", "摄氏度")),
        ExplicitUnitAlias("fahrenheit", listOf("°f", "fahrenheit", "华氏度")),
        ExplicitUnitAlias("percent", listOf("percentages", "percentage", "percent", "%", "百分比", "百分之")),
        ExplicitUnitAlias("day", listOf("days", "day", "天", "日")),
        ExplicitUnitAlias("week", listOf("weeks", "week", "周", "星期")),
        ExplicitUnitAlias("month", listOf("months", "month", "个月", "個月", "月")),
        ExplicitUnitAlias("year", listOf("years", "year", "年")),
        ExplicitUnitAlias("usd", listOf("US dollars", "US dollar", "dollars", "dollar", "USD", "美元", "美金", "$")),
        ExplicitUnitAlias("euro", listOf("euros", "euro", "EUR", "欧元", "€")),
        ExplicitUnitAlias("gbp", listOf("GBP", "英镑", "英鎊", "sterling", "£")),
        ExplicitUnitAlias("jpy", listOf("yen", "JPY", "日元", "円")),
        ExplicitUnitAlias("cny", listOf("CNY", "RMB", "renminbi", "人民币", "人民幣", "元")),
    )
    private val explicitUnitMatches = explicitUnitAliases.map { unit ->
        unit.id to Regex(
            """(?<![\p{L}])(?:${unit.aliases.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})(?![\p{L}])""",
            RegexOption.IGNORE_CASE,
        )
    }

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
                (letters.length == 1 || letters.drop(1).all { it.isLowerCase() })
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

    /**
     * One authoritative decision for first-pass output, adaptive retries, caches and dubbing.
     * Detail methods remain available for diagnostics, but callers should not reassemble a
     * different validation order in each pipeline stage.
     */
    fun evaluateTranslation(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String = "zh",
        acceptShortUnchanged: Boolean = false,
    ): TranslationQualityDecision {
        if (translatedText.isNullOrBlank()) {
            return TranslationQualityDecision(false, "EMPTY_OR_STATUS_PLACEHOLDER", "译文为空或只有空白字符")
        }
        if (!TtsTextPolicy.hasSpeakableContent(translatedText)) {
            return TranslationQualityDecision(false, "UNSPEAKABLE_TRANSLATION", "译文只有标点、符号或表情，没有可朗读内容")
        }
        if (sourceText.isNullOrBlank()) return TranslationQualityDecision(true)

        val sourceEcho = isSourceEcho(sourceText, translatedText, sourceLanguage)
        val targetMismatch = targetLanguageMismatchReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val modelMeta = translationMetaResponseReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val scriptMismatch = if (SourceLanguagePolicy.isChinese(sourceLanguage)) null
            else targetChineseScriptMismatchReason(translatedText, targetLanguage)
        val numericMismatch = numericContentMismatchReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val numericUnitMismatch = numericUnitAssociationMismatchReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val unitMismatch = unitContentMismatchReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val semanticMismatch = semanticConsistencyMismatchReason(sourceText, translatedText, sourceLanguage, targetLanguage)
        val shortAccepted = acceptShortUnchanged &&
            isAcceptableShortUntranslatedCue(sourceText, translatedText, sourceLanguage, targetLanguage) &&
            modelMeta == null && scriptMismatch == null && numericMismatch == null &&
            numericUnitMismatch == null && unitMismatch == null && semanticMismatch == null

        val rejection = if (shortAccepted) null else when {
            isPunctuationOnlyTranslation(sourceText, translatedText) ->
                "UNSPEAKABLE_TRANSLATION" to "译文只有标点、符号或表情，没有可朗读内容"
            modelMeta != null -> "MODEL_META_RESPONSE" to modelMeta
            scriptMismatch != null -> "TARGET_SCRIPT_MISMATCH" to scriptMismatch
            numericMismatch != null -> "NUMERIC_CONTENT_MISMATCH" to numericMismatch
            numericUnitMismatch != null -> "NUMERIC_UNIT_ASSOCIATION_MISMATCH" to numericUnitMismatch
            unitMismatch != null -> "UNIT_CONTENT_MISMATCH" to unitMismatch
            semanticMismatch != null -> semanticMismatchCode(semanticMismatch) to semanticMismatch
            sourceEcho && targetMismatch != null ->
                "SOURCE_ECHO_AND_TARGET_LANGUAGE_MISMATCH" to ("译文与原文相同，且没有达到目标语言要求；" + targetMismatch)
            targetMismatch != null -> "TARGET_LANGUAGE_MISMATCH" to targetMismatch
            sourceEcho -> "SOURCE_ECHO" to "忽略大小写、空白和标点后，模型译文与原文相同"
            else -> null
        }
        return TranslationQualityDecision(
            accepted = rejection == null,
            reasonCode = rejection?.first,
            reason = rejection?.second,
            sourceEcho = sourceEcho,
            targetLanguageMismatchReason = targetMismatch,
            shortUntranslatedAccepted = shortAccepted,
            modelMetaResponseReason = modelMeta,
            scriptMismatchReason = scriptMismatch,
            numericMismatchReason = numericMismatch,
            numericUnitAssociationMismatchReason = numericUnitMismatch,
            unitMismatchReason = unitMismatch,
            semanticMismatchReason = semanticMismatch,
        )
    }

    private fun semanticMismatchCode(reason: String): String = when {
        reason.startsWith("SEMANTIC_POLARITY_MISMATCH") -> "SEMANTIC_POLARITY_MISMATCH"
        reason.startsWith("SEMANTIC_MODALITY_MISMATCH") -> "SEMANTIC_MODALITY_MISMATCH"
        reason.startsWith("SEMANTIC_ENTITY_MISMATCH") -> "SEMANTIC_ENTITY_MISMATCH"
        reason.startsWith("SEMANTIC_INTENT_MISMATCH") -> "SEMANTIC_INTENT_MISMATCH"
        else -> "SEMANTIC_RELATION_MISMATCH"
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
        return evaluateTranslation(
            sourceText, translatedText, sourceLanguage, targetLanguage, acceptShortUnchanged,
        ).accepted
    }


    /**
     * Reject output dominated by the opposite Chinese script. Short cues and a few retained
     * proper-name characters are not enough evidence; the mismatch must be repeated and clear.
     */
    fun targetChineseScriptMismatchReason(
        translatedText: String?,
        targetLanguage: String,
    ): String? {
        if (translatedText.isNullOrBlank() || !SourceLanguagePolicy.isChinese(targetLanguage)) return null
        val tag = SourceLanguagePolicy.normalizeTag(targetLanguage).lowercase(Locale.ROOT)
        val traditionalTarget = tag.startsWith("zh-hant") || tag.startsWith("cmn-hant") ||
            tag.startsWith("yue-hant") || tag in setOf("zh-tw", "zh-hk", "zh-mo")
        val simplifiedTarget = tag == "zh" || tag.startsWith("zh-cn") || tag.startsWith("zh-hans") ||
            tag == "zh-sg" || tag.startsWith("cmn-hans") || tag.startsWith("yue-hans") ||
            tag.startsWith("cmn-") && !tag.startsWith("cmn-hant")
        if (!traditionalTarget && !simplifiedTarget) return null

        val simplifiedOnly = "这为发后国门东车书云电汉广场龙风饭头还没与从让说时间开关长万亿爱见听写话语远边个们吗软体续会"
        val traditionalOnly = "這為發後國門東車書雲電漢廣場龍風飯頭還沒與從讓說時間開關長萬億愛見聽寫話語遠邊個們嗎軟體續會"
        val wrongScript = if (traditionalTarget) simplifiedOnly else traditionalOnly
        val expectedScript = if (traditionalTarget) traditionalOnly else simplifiedOnly
        val wrongCount = translatedText.count { it in wrongScript }
        val expectedCount = translatedText.count { it in expectedScript }
        val distinctWrong = translatedText.filter { it in wrongScript }.toSet().size
        if (distinctWrong >= 3 && wrongCount >= 4 && wrongCount > expectedCount * 2 + 1) {
            val actualName = if (traditionalTarget) "简体" else "繁体"
            val expectedName = if (traditionalTarget) "繁体" else "简体"
            return "TARGET_SCRIPT_MISMATCH：" + "译文中" + actualName + "字形占主导，但目标语言要求" + expectedName + "中文；冲突字形数=" + wrongCount
        }
        return null
    }

    /**
     * Normalize explicit Arabic-number tokens without logging their values.
     * Parse source and Chinese output using their own number-format conventions.
     */
    private fun normalizedExplicitNumbers(
        value: String,
        numberFormatLanguage: String,
        skipClockRanges: List<IntRange> = emptyList(),
    ): List<String> {
        val tokens = mutableListOf<Pair<Int, String>>()
        val fractions = explicitFractionPattern.findAll(value)
            .filterNot { match ->
                isSlashDateLike(match, value) ||
                    skipClockRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }
            }
            .toList()
        fractions.forEach { match ->
            normalizeArabicFraction(match.value)?.let { tokens += match.range.first to it }
        }
        explicitNumberPattern.findAll(value).forEach { match ->
            if (fractions.any { it.range.contains(match.range.first) && it.range.contains(match.range.last) }) return@forEach
            if (skipClockRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            normalizeExplicitNumberMatch(match, value, numberFormatLanguage)?.let { tokens += match.range.first to it }
        }
        return tokens.sortedBy { it.first }.distinctBy { it.first }.map { it.second }
    }

    private fun normalizeExplicitNumberMatch(
        match: MatchResult,
        fullText: String,
        numberFormatLanguage: String,
    ): String? {
        val raw = match.value
        // A hyphen immediately following a digit is a range/date separator, not a sign.
        val hyphenIsSeparator = raw.startsWith("-") &&
            match.range.first > 0 && fullText[match.range.first - 1].isDigit()
        return normalizeArabicNumber(
            if (hyphenIsSeparator) raw.removePrefix("-") else raw,
            numberFormatLanguage,
        )
    }

    private fun normalizeArabicNumber(rawValue: String, numberFormatLanguage: String): String? {
        val negative = rawValue.startsWith("-")
        var number = rawValue.removePrefix("-")
        val primaryLanguage = SourceLanguagePolicy.normalizeTag(numberFormatLanguage)
            .lowercase(Locale.ROOT)
            .substringBefore("-")
        val commaDecimal = primaryLanguage in commaDecimalLanguages
        val groupedComma = Regex("""^\d{1,3}(?:,\d{3})+$""").matches(number)
        val groupedDot = Regex("""^\d{1,3}(?:\.\d{3})+$""").matches(number)
        number = when {
            number.contains(",") && number.contains(".") && commaDecimal ->
                number.replace(".", "").replace(',', '.')
            number.contains(",") && number.contains(".") ->
                number.replace(",", "")
            number.contains(",") && groupedComma && !commaDecimal ->
                number.replace(",", "")
            number.contains(",") ->
                number.replace(',', '.')
            number.contains(".") && groupedDot && commaDecimal ->
                number.replace(".", "")
            else -> number
        }
        val signed = if (negative) "-$number" else number
        return runCatching { BigDecimal(signed).stripTrailingZeros().toPlainString() }.getOrNull()
    }

    private fun isSlashDateLike(match: MatchResult, fullText: String): Boolean {
        val nextIndex = match.range.last + 1
        val followedByAnotherDatePart = nextIndex < fullText.length && fullText[nextIndex] == '/'
        val firstPart = match.value.substringBefore('/').removePrefix("-")
        return followedByAnotherDatePart || firstPart.length == 4
    }

    private fun normalizeArabicFraction(rawValue: String): String? {
        val parts = rawValue.replace(" ", "").split("/")
        if (parts.size != 2) return null
        val numerator = runCatching { BigDecimal(parts[0]) }.getOrNull() ?: return null
        val denominator = runCatching { BigDecimal(parts[1]) }.getOrNull() ?: return null
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null
        return runCatching {
            numerator.divide(denominator, 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }.getOrNull()
    }



    private fun explicitNumericUnits(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val numberMatches = explicitNumberPattern.findAll(value).toList()
        if (numberMatches.isEmpty()) return emptyList()
        val matches = explicitUnitMatches.flatMap { (unitId, pattern) ->
            pattern.findAll(value).mapNotNull { match ->
                val adjacentToNumber = numberMatches.any { number ->
                    number.range.last < match.range.first &&
                        match.range.first - number.range.last - 1 <= 4
                }
                if (adjacentToNumber) Triple(match.range.first, match.range.last, unitId) else null
            }.toList()
        }.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second - it.first })
        val accepted = mutableListOf<Triple<Int, Int, String>>()
        matches.forEach { candidate ->
            if (accepted.none { existing ->
                    candidate.first <= existing.second && existing.first <= candidate.second
                }
            ) accepted += candidate
        }
        return accepted.sortedBy { it.first }.map { it.third }
    }

    /**
     * Preserve the association between explicit numeric values and adjacent units.
     * Require at least two pairs so single-unit substitutions use the dedicated unit-category check.
     */
    fun numericUnitAssociationMismatchReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage) ||
            SourceLanguagePolicy.isChinese(sourceLanguage)
        ) return null
        val sourcePairs = explicitNumericUnitPairs(sourceText, sourceLanguage, includeChineseNumerals = false)
        val targetPairs = explicitNumericUnitPairs(translatedText, targetLanguage, includeChineseNumerals = true)
        if (sourcePairs.size < 2 || sourcePairs.size != targetPairs.size) return null
        if (sourcePairs.groupingBy { it }.eachCount() == targetPairs.groupingBy { it }.eachCount()) return null
        return "数字与单位的对应关系发生变化；原文可识别配对数=" + sourcePairs.size +
            "，译文可识别配对数=" + targetPairs.size
    }

    private data class NumericToken(val start: Int, val end: Int, val value: String)

    private fun explicitNumericUnitPairs(
        value: String,
        numberFormatLanguage: String,
        includeChineseNumerals: Boolean,
    ): List<NumericUnitPair> {
        val numberMatches = mutableListOf<NumericToken>()
        val fractions = explicitFractionPattern.findAll(value)
            .filterNot { isSlashDateLike(it, value) }
            .toList()
        fractions.forEach { match ->
            normalizeArabicFraction(match.value)?.let {
                numberMatches += NumericToken(match.range.first, match.range.last, it)
            }
        }
        explicitNumberPattern.findAll(value).forEach { match ->
            if (fractions.any { it.range.contains(match.range.first) && it.range.contains(match.range.last) }) return@forEach
            normalizeExplicitNumberMatch(match, value, numberFormatLanguage)?.let {
                numberMatches += NumericToken(match.range.first, match.range.last, it)
            }
        }
        if (includeChineseNumerals) {
            val chineseFractions = chineseFractionPattern.findAll(value).toList()
            chineseFractions.forEach { match ->
                val denominator = parseChineseInteger(match.groupValues[1])?.toBigDecimalOrNull() ?: return@forEach
                val numerator = parseChineseInteger(match.groupValues[2])?.toBigDecimalOrNull() ?: return@forEach
                if (denominator.compareTo(BigDecimal.ZERO) == 0) return@forEach
                val fraction = runCatching {
                    numerator.divide(denominator, 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                }.getOrNull() ?: return@forEach
                numberMatches += NumericToken(match.range.first, match.range.last, fraction)
            }
            val ranges = chineseRangePattern.findAll(value).toList()
            ranges.forEach { match ->
                val first = parseChineseNumeral(match.groupValues[1]) ?: return@forEach
                val second = parseChineseNumeral(match.groupValues[2]) ?: return@forEach
                numberMatches += NumericToken(match.groups[1]!!.range.first, match.groups[1]!!.range.last, first)
                numberMatches += NumericToken(match.groups[2]!!.range.first, match.groups[2]!!.range.last, second)
            }
            val protectedRanges = chineseFractions.map { it.range } + ranges.map { it.range }
            chineseNumeralPattern.findAll(value).forEach { match ->
                if (protectedRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
                val nextChar = value.getOrNull(match.range.last + 1)
                if (match.value == "零" && nextChar == '下') return@forEach
                val suffix = value.substring(match.range.last + 1, minOf(value.length, match.range.last + 4))
                val hasMagnitude = match.value.any { it in "十百千万萬亿億" }
                val hasClassifier = listOf(
                    "个", "個", "只", "条", "條", "本", "件", "位", "张", "張", "岁", "歲", "人",
                    "年", "月", "日", "天", "周", "小时", "小時", "分钟", "分鐘", "秒", "公里",
                    "千米", "米", "厘米", "毫米", "公斤", "千克", "克", "毫升", "升", "度", "元", "美元",
                    "欧元", "英镑", "百分之", "百分比",
                ).any(suffix::startsWith)
                val prefix = value.substring(maxOf(0, match.range.first - 6), match.range.first)
                val hasNumberPrefix = Regex("""(?:是|为|為|有|共|达|達|约|約|第|超过|超過|至少|少于|少於|多于|多於|等于|等於|只剩|还剩|還剩|剩下|剩余|剩餘|重达|重達|长达|長達|正好|刚好|百分之)$""")
                    .containsMatchIn(prefix)
                val consecutiveDigits = match.value.length >= 2 &&
                    match.value.all { it in "零〇一二两兩三四五六七八九" }
                val trailingHalf = match.value == "半" || match.value == "一半" ||
                    ((match.value.endsWith('点') || match.value.endsWith('點')) && suffix.startsWith("半"))
                val zeroBelow = prefix.endsWith("零下")
                if (!hasMagnitude && !hasClassifier && !hasNumberPrefix && !consecutiveDigits && !trailingHalf) return@forEach
                parseChineseNumeral(match.value, suffix)?.let { parsed ->
                    val normalized = if (zeroBelow) {
                        runCatching { BigDecimal(parsed).negate().stripTrailingZeros().toPlainString() }.getOrNull() ?: parsed
                    } else parsed
                    numberMatches += NumericToken(match.range.first, match.range.last, normalized)
                }
            }
        }
        if (numberMatches.isEmpty()) return emptyList()
        val unitMatches = explicitUnitMatches.flatMap { (unitId, pattern) ->
            pattern.findAll(value).map { match -> Triple(match.range.first, match.range.last, unitId) }.toList()
        }.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second - it.first })
        val acceptedUnits = mutableListOf<Triple<Int, Int, String>>()
        unitMatches.forEach { candidate ->
            if (acceptedUnits.none { existing ->
                    candidate.first <= existing.second && existing.first <= candidate.second
                }
            ) acceptedUnits += candidate
        }
        val usedNumbers = HashSet<Int>()
        val pairs = mutableListOf<NumericUnitPair>()
        acceptedUnits.sortedBy { it.first }.forEach { unit ->
            val nearestNumber = numberMatches.withIndex()
                .mapNotNull { (index, number) ->
                    if (index in usedNumbers) return@mapNotNull null
                    val gap = when {
                        number.end < unit.first -> unit.first - number.end - 1
                        unit.second < number.start -> number.start - unit.second - 1
                        else -> Int.MAX_VALUE
                    }
                    if (gap <= 4) Triple(index, number, gap) else null
                }
                .minByOrNull { it.third }
                ?: return@forEach
            usedNumbers += nearestNumber.first
            pairs += NumericUnitPair(nearestNumber.second.value, unit.third)
        }
        return pairs
    }


    /**
     * Reject an explicit unit substitution only when both sides expose a numeric-adjacent unit.
     * Missing/word-rendered units are left to the translation model and broader semantic context.
     */
    fun unitContentMismatchReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage) ||
            SourceLanguagePolicy.isChinese(sourceLanguage)
        ) return null
        val sourceUnits = explicitNumericUnits(sourceText)
        val translatedUnits = explicitNumericUnits(translatedText)
        if (sourceUnits.isEmpty() || translatedUnits.isEmpty()) return null
        if (sourceUnits == translatedUnits) return null
        return "原文和译文均出现明确数字单位，但单位类别不一致；原文单位数=${sourceUnits.size}，译文单位数=${translatedUnits.size}"
    }

    /**
     * Use a supplied language first, then strong script evidence, then a few unmistakable
     * sentence patterns for Latin-script languages. A single common word is never enough.
     */
    private fun resolveSemanticSourceLanguage(source: String, sourceLanguage: String): String {
        if (!SourceLanguagePolicy.isUndetermined(sourceLanguage)) {
            return SourceLanguagePolicy.canonicalPrimaryCode(sourceLanguage)
        }
        val scriptLanguage = SubtitleLanguageDetector.detect(source)
        if (scriptLanguage != "auto") return SourceLanguagePolicy.canonicalPrimaryCode(scriptLanguage)
        val sample = source.trim()
        val englishSignal = Regex(
            """\b(?:i|you|we|they|he|she)\s+(?:(?:do|does|did)\s+not|(?:am|is|are|was|were|have|has|will|can|could|should|must|may|might)\s+not)\b|\b(?:don't|doesn't|didn't|can't|cannot|won't|isn't|aren't|wasn't|weren't|haven't|hasn't|shouldn't|mustn't)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(sample) || Regex(
            """\b(?:i|you|we|they|he|she)\s+(?:do|does|did|am|is|are|was|were|have|has|will|can|could|should|must|may|might)\s+\w+""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(sample)
        if (englishSignal) return "en"

        val frenchSignal = Regex(
            """\bne\b.{0,48}\b(?:pas|plus|jamais|rien|personne)\b|\best-ce\s+que\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(sample)
        if (frenchSignal) return "fr"

        val germanSignal = Regex(
            """\bich\b.{0,48}\b(?:nicht|kein|keine|nie|nichts)\b|\b(?:ich|du|er|sie|wir)\s+(?:bin|bist|ist|sind|war|waren|kann|können|will|wollen)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(sample)
        if (germanSignal) return "de"

        val spanishSignal = Regex(
            """\bno\s+(?:quiero|puedo|sé|se|estoy|soy|tengo|hay|vamos)\b|\b(?:yo|tú|tu|él|ella|nosotros)\s+(?:no\s+)?(?:quiero|puedo|sé|se|estoy|soy|tengo)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(sample)
        if (spanishSignal) return "es"
        return "auto"
    }

    /** Conservative checks for explicit English-to-Chinese semantic contradictions. */
    fun semanticConsistencyMismatchReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage)) return null
        val language = resolveSemanticSourceLanguage(sourceText, sourceLanguage)
        val source = sourceText.trim()
        val target = translatedText.trim()
        if (source.isEmpty() || target.isEmpty() || isNonTranslatableCue(source)) return null
        if (language != "en") {
            val polarity = multilingualPolarityMismatchReason(source, target, language)
            if (polarity != null) return polarity
            val intent = speechIntentMismatchReason(source, target, language)
            if (intent != null) return intent
            return multilingualRelationMismatchReason(source, target, language)
        }
        val intent = speechIntentMismatchReason(source, target, language)
        if (intent != null) return intent

        val sourceAlready = Regex("""\balready\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val sourceNotYet = Regex("""\bnot\s+yet\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val targetAlready = Regex("""已经|早已|早就|已經""").containsMatchIn(target)
        val targetNotYet = Regex("""还没|还未|尚未|仍未|还没有|還沒|還未|還沒有""").containsMatchIn(target)
        if ((sourceAlready && targetNotYet && !targetAlready) ||
            (sourceNotYet && targetAlready && !targetNotYet)
        ) return "SEMANTIC_RELATION_MISMATCH：原文 already/not yet 的完成状态与译文明确表达的时间状态相反"

        val sourceStill = Regex("""\bstill\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val sourceNoLonger = Regex("""\b(?:no\s+longer|not\s+anymore)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(source)
        val targetStill = Regex("""仍然|依然|仍旧|还是""").containsMatchIn(target)
        val targetNoLonger = Regex("""不再|再也不|已不再|已经不在|已不在""").containsMatchIn(target)
        if ((sourceStill && targetNoLonger && !targetStill) ||
            (sourceNoLonger && targetStill && !targetNoLonger)
        ) return "SEMANTIC_RELATION_MISMATCH：原文 still/no longer 的持续状态与译文明确表达的状态相反"

        val sourceProhibition = englishProhibition.containsMatchIn(source)
        val sourceObligation = !sourceProhibition && englishObligation.containsMatchIn(source)
        val sourceRecommendation = englishRecommendation.containsMatchIn(source)
        val mayIsPermission = Regex("""\b(?:may\s+(?:i|we|you)\b|(?:i|we|you)\s+may\b)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(source)
        val sourcePossibility = !mayIsPermission && englishPossibility.containsMatchIn(source)
        val targetProhibition = chineseProhibition.containsMatchIn(target)
        val targetObligation = chineseObligation.containsMatchIn(target)
        val targetRecommendation = chineseRecommendation.containsMatchIn(target)
        val targetPossibility = chinesePossibility.containsMatchIn(target)
        val modalityReversed = when {
            sourceProhibition ->
                !targetProhibition && (targetObligation || targetRecommendation || targetPossibility)
            sourceObligation ->
                !targetObligation && !targetProhibition && (targetRecommendation || targetPossibility)
            sourceRecommendation ->
                !targetRecommendation && (targetProhibition || targetObligation || targetPossibility)
            sourcePossibility ->
                !targetPossibility && (targetProhibition || targetObligation || targetRecommendation)
            else -> false
        }
        if (modalityReversed) {
            return "SEMANTIC_MODALITY_MISMATCH：原文的禁止、必须、应该或可能性语气，与译文明确表达的语气等级相反"
        }

        val sourceNegated = hasClearEnglishNegation(source)
        val targetNegated = hasClearChineseNegation(target)
        if (sourceNegated != targetNegated) {
            return if (sourceNegated) {
                "SEMANTIC_POLARITY_MISMATCH：原文包含明确否定表达，但译文没有保留对应的否定含义"
            } else {
                "SEMANTIC_POLARITY_MISMATCH：译文新增了原文没有的明确否定表达，可能改变肯定/否定含义"
            }
        }

        val sourceMale = englishMalePronoun.containsMatchIn(source)
        val sourceFemale = englishFemalePronoun.containsMatchIn(source)
        val targetMale = chineseMalePronoun.containsMatchIn(target)
        val targetFemale = chineseFemalePronoun.containsMatchIn(target)
        if (sourceMale.xor(sourceFemale) && targetMale.xor(targetFemale) &&
            ((sourceMale && targetFemale) || (sourceFemale && targetMale))
        ) {
            return "SEMANTIC_ENTITY_MISMATCH：原文明确的人称代词与译文使用的性别代词不一致"
        }

        val sourceBefore = Regex("""\bbefore\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val sourceAfter = Regex("""\bafter\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val targetBefore = Regex("""之前|以前|此前|先前|在.{0,8}之前""").containsMatchIn(target)
        val targetAfter = Regex("""之后|以后|过后|随后|之後|以後|過後|隨後|在.{0,8}之后|在.{0,8}之後""")
            .containsMatchIn(target)
        if (sourceBefore.xor(sourceAfter) &&
            ((sourceBefore && targetAfter && !targetBefore) ||
                (sourceAfter && targetBefore && !targetAfter))
        ) return "SEMANTIC_RELATION_MISMATCH：原文 before/after 的时间先后方向与译文明确表达的方向相反"

        val sourceUntil = Regex("""\buntil\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val targetUntil = Regex("""直到|直至|为止|為止|等到""").containsMatchIn(target)
        if (sourceUntil && targetBefore && !targetUntil) {
            return "SEMANTIC_RELATION_MISMATCH：原文 until 的截止时间被译文明确表达成截止点之前"
        }

        val sourceBecause = Regex("""\bbecause\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val sourceAlthough = Regex("""\b(?:although|though|even though)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(source)
        val targetCause = Regex("""因为|由于|所以|因此|因為|由於""").containsMatchIn(target)
        val targetConcession = Regex("""虽然|尽管|即使|可是|然而|雖然|儘管""").containsMatchIn(target)
        if ((sourceBecause && targetConcession && !targetCause) ||
            (sourceAlthough && targetCause && !targetConcession)
        ) return "SEMANTIC_RELATION_MISMATCH：原文的因果/让步关系与译文明确使用的关系词不一致"

        val sourceUnless = Regex("""\bunless\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
        val targetIf = Regex("""如果|若|假如|要是""").containsMatchIn(target)
        val targetHasConditionNegation = Regex("""不|没|未|無|否则|否則|除非""").containsMatchIn(target)
        if (sourceUnless && targetIf && !targetHasConditionNegation) {
            return "SEMANTIC_RELATION_MISMATCH：原文 unless 被译成没有否定限定的普通 if 条件，条件逻辑可能反转"
        }

        semanticOppositions.forEach { opposition ->
            val sourcePositive = opposition.sourcePositive.containsMatchIn(source)
            val sourceNegative = opposition.sourceNegative.containsMatchIn(source)
            if (sourcePositive.xor(sourceNegative)) {
                val targetPositive = opposition.targetPositive.containsMatchIn(target)
                val targetNegative = opposition.targetNegative.containsMatchIn(target)
                if ((sourcePositive && targetNegative && !targetPositive) ||
                    (sourceNegative && targetPositive && !targetNegative)
                ) {
                    return "SEMANTIC_RELATION_MISMATCH：原文的${opposition.label}与译文明示的相反关系不一致"
                }
            }
        }
        return null
    }

    private fun multilingualPolarityMismatchReason(
        source: String,
        target: String,
        language: String,
    ): String? {
        val neutralNegativeSurface = when (language) {
            "ja" -> Regex("""問題(?:は)?(?:ありません|ない(?:です)?|なかった)""").containsMatchIn(source)
            "ko" -> Regex("""문제\s*없(?:다|어요|습니다|었어요)""").containsMatchIn(source)
            "fr" -> Regex("""(?:pas\s+de\s+problème|aucun\s+problème|sans\s+problème|pas\s+mal)""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "de" -> Regex("""(?:kein\s+Problem|nicht\s+schlecht)""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "es" -> Regex("""(?:no\s+hay\s+problema|sin\s+problema|no\s+está\s+mal|no\s+esta\s+mal)""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "pt" -> Regex("""(?:não\s+há\s+problema|nao\s+há\s+problema|sem\s+problema|não\s+é\s+ruim|nao\s+é\s+ruim)""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "ru" -> Regex("""(?:нет\s+проблем|без\s+проблем|ничего\s+страшного|неплохо)""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            else -> false
        }
        if (neutralNegativeSurface) return null
        val sourceNegated = when (language) {
            "ja" -> Regex("""(?:ではない|じゃない|ではありません|じゃありません|ではなかった|じゃなかった|ません|なかった|ないでください|ずに|わけではない|行かない|来ない|食べない|見ない|聞かない|話さない|知らない|分からない|使わない|買わない|書かない|できない|出来ない|持たない|要らない|入らない)""").containsMatchIn(source)
            "ko" -> Regex("""(?:안\s|(?:^|\s)안(?!녕|심|전)[가-힣]+|못\s|없(?:다|어요|습니다|었어요)|아니(?:다|에요|예요)|지\s*않|지\s*못|않(?:다|아요|습니다))""").containsMatchIn(source)
            "fr" -> Regex("""\bne\b.{0,48}\b(?:pas|plus|jamais|rien|personne|aucun|aucune)\b|\bn['’]\w+.{0,48}\b(?:pas|plus|jamais|rien|personne)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source) ||
                Regex("""\b(?:aucun|aucune|sans|jamais|personne|rien)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "de" -> Regex("""\b(?:nicht|kein(?:e|en|er|em)?|nie|niemals|ohne|nichts)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source) &&
                !Regex("""\bnicht\s+nur\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "es" -> Regex("""\b(?:no|nunca|jamás|jamas|sin|ningún|ningun|ninguna|nadie|nada|tampoco)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source) &&
                !Regex("""\bno\s+sólo\b|\bno\s+solo\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "pt" -> Regex("""\b(?:não|nao|nunca|jamais|sem|ninguém|ninguem|nada|tampouco|nenhum|nenhuma)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source) &&
                !Regex("""\bnão\s+só\b|\bnao\s+so\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            "ru" -> Regex("""\b(?:не|ни|нет|никогда|никто|ничего|никакой|никакая|никакое|без|нельзя)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source) &&
                !Regex("""\bне\s+только\b""", RegexOption.IGNORE_CASE).containsMatchIn(source)
            else -> false
        }
        val targetNegated = hasClearChineseNegation(target)
        if (sourceNegated != targetNegated) {
            return if (sourceNegated) {
                "SEMANTIC_POLARITY_MISMATCH：原文包含明确否定表达，但译文没有保留对应的否定含义"
            } else {
                "SEMANTIC_POLARITY_MISMATCH：译文新增了原文没有的明确否定表达，可能改变肯定/否定含义"
            }
        }
        return null
    }

    private fun speechIntentMismatchReason(
        source: String,
        target: String,
        language: String,
    ): String? {
        val sourceQuestionMark = Regex("""[?？][\"'”’）)]?\s*$""").containsMatchIn(source)
        val sourceQuestionForm = when (language) {
            "en" -> Regex("""^(?:who|what|where|when|why|how|which|whom|whose)\s+(?:is|are|am|was|were|do|does|did|can|could|would|will|should|have|has|had)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source.trim())
            "fr" -> Regex("""^est-ce\s+que\b""", RegexOption.IGNORE_CASE).containsMatchIn(source.trim())
            "de" -> Regex("""^(?:wer|was|wo|wann|warum|weshalb|wie|welche|welcher|welches)\s+(?:ist|sind|war|waren|bist|hast|hat|kann|können|kommt|kommen|geht|gehen)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source.trim())
            "es", "pt" -> source.trimStart().startsWith("¿")
            "ja" -> Regex("""(?:か|のか)[。.!！?？]?\s*$""").containsMatchIn(source.trim())
            "ko" -> Regex("""(?:습니까|ㅂ니까|나요|까요|니|냐)[。.!！?？]?\s*$""").containsMatchIn(source.trim())
            else -> false
        }
        val targetQuestion = Regex("""[?？]\s*$""").containsMatchIn(target.trim()) ||
            Regex("""(?:吗|嗎|呢|么|麼|什么|什麼|谁|誰|哪里|哪裡|为什么|為什麼|多少|如何|是否|几|幾)[。.!！?？]?$""")
                .containsMatchIn(target.trim())
        if ((sourceQuestionMark || sourceQuestionForm) && !targetQuestion) {
            return "SEMANTIC_INTENT_MISMATCH：原文是疑问表达，但译文没有保留疑问意图"
        }
        return null
    }

    private fun multilingualRelationMismatchReason(
        source: String,
        target: String,
        language: String,
    ): String? {
        val before = when (language) {
            "fr" -> Regex("""\bavant\b""", RegexOption.IGNORE_CASE)
            "de" -> Regex("""\bvor\b""", RegexOption.IGNORE_CASE)
            "es", "pt" -> Regex("""\bantes\b""", RegexOption.IGNORE_CASE)
            "ru" -> Regex("""\bдо\b""", RegexOption.IGNORE_CASE)
            "ja" -> Regex("""前に|以前""")
            "ko" -> Regex("""전에|이전에""")
            else -> null
        }?.containsMatchIn(source) == true
        val after = when (language) {
            "fr" -> Regex("""\b(?:après|apres)\b""", RegexOption.IGNORE_CASE)
            "de" -> Regex("""\bnach\b""", RegexOption.IGNORE_CASE)
            "es" -> Regex("""\b(?:después|despues)\b""", RegexOption.IGNORE_CASE)
            "pt" -> Regex("""\bdepois\b""", RegexOption.IGNORE_CASE)
            "ru" -> Regex("""\bпосле\b""", RegexOption.IGNORE_CASE)
            "ja" -> Regex("""後に|あとで|後で|以後""")
            "ko" -> Regex("""후에|이후""")
            else -> null
        }?.containsMatchIn(source) == true
        val targetBefore = Regex("""之前|以前|此前|先前|在.{0,8}之前""").containsMatchIn(target)
        val targetAfter = Regex("""之后|以后|过后|随后|之後|以後|過後|隨後|在.{0,8}之后|在.{0,8}之後""").containsMatchIn(target)
        if (before.xor(after) && ((before && targetAfter && !targetBefore) || (after && targetBefore && !targetAfter))) {
            return "SEMANTIC_RELATION_MISMATCH：原文时间先后关系与译文明确表达的方向相反"
        }

        val unless = when (language) {
            "fr" -> Regex("""à\s+moins\s+que""", RegexOption.IGNORE_CASE)
            "de" -> Regex("""es\s+sei\s+denn""", RegexOption.IGNORE_CASE)
            "es", "pt" -> Regex("""a\s+menos\s+que""", RegexOption.IGNORE_CASE)
            "ja" -> Regex("""でない限り|ない限り""")
            "ko" -> Regex("""하지\s*않으면""")
            else -> null
        }?.containsMatchIn(source) == true
        val targetIf = Regex("""如果|若|假如|要是""").containsMatchIn(target)
        val targetHasConditionNegation = Regex("""不|没|未|無|否则|否則|除非""").containsMatchIn(target)
        if (unless && targetIf && !targetHasConditionNegation) {
            return "SEMANTIC_RELATION_MISMATCH：原文的否定条件被译成没有否定限定的普通条件"
        }

        val sourceCause = when (language) {
            "fr" -> Regex("""\b(?:parce\s+que|car|puisque)\b""", RegexOption.IGNORE_CASE)
            "de" -> Regex("""\b(?:weil|da)\b""", RegexOption.IGNORE_CASE)
            "es" -> Regex("""\b(?:porque|ya\s+que)\b""", RegexOption.IGNORE_CASE)
            "pt" -> Regex("""\b(?:porque|já\s+que|ja\s+que|pois)\b""", RegexOption.IGNORE_CASE)
            "ja" -> Regex("""から|ので|ため""")
            "ko" -> Regex("""때문에|니까|므로""")
            else -> null
        }?.containsMatchIn(source) == true
        val sourceConcession = when (language) {
            "fr" -> Regex("""\b(?:bien\s+que|même\s+si|meme\s+si|quoique)\b""", RegexOption.IGNORE_CASE)
            "de" -> Regex("""\b(?:obwohl|wenngleich)\b""", RegexOption.IGNORE_CASE)
            "es" -> Regex("""\b(?:aunque|a\s+pesar\s+de)\b""", RegexOption.IGNORE_CASE)
            "pt" -> Regex("""\b(?:embora|apesar\s+de)\b""", RegexOption.IGNORE_CASE)
            "ja" -> Regex("""けど|けれど|のに|にもかかわらず""")
            "ko" -> Regex("""지만|는데도|더라도|에도\s+불구하고""")
            else -> null
        }?.containsMatchIn(source) == true
        val targetCause = Regex("""因为|由于|所以|因此|因為|由於""").containsMatchIn(target)
        val targetConcession = Regex("""虽然|尽管|即使|可是|然而|雖然|儘管""").containsMatchIn(target)
        if ((sourceCause && targetConcession && !targetCause) ||
            (sourceConcession && targetCause && !targetConcession)
        ) return "SEMANTIC_RELATION_MISMATCH：原文因果/让步关系与译文明确使用的关系词不一致"
        return null
    }

    private fun hasClearEnglishNegation(value: String): Boolean {
        val neutralRanges = neutralEnglishNegation.findAll(value).map { it.range }.toList()
        return englishNegation.findAll(value).any { match ->
            neutralRanges.none { it.contains(match.range.first) }
        }
    }

    private fun hasClearChineseNegation(value: String): Boolean {
        val cleaned = affirmativeChineseSurface.replace(value, " ")
        return chineseNegation.containsMatchIn(cleaned)
    }

    /**
     * Compare explicit source values with Arabic or confidently parsed Chinese numeral values.
     * Chinese clock forms are excluded only when the source also contains a clock token and the
     * translation visibly uses Chinese time notation. An explicit source quantity may not vanish.
     */
    fun numericContentMismatchReason(
        sourceText: String?,
        translatedText: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage) ||
            SourceLanguagePolicy.isChinese(sourceLanguage)
        ) return null

        val localizedClock = listOf("点", "點", "时", "時", "上午", "下午", "凌晨", "中午")
            .any(translatedText::contains) ||
            chineseClockPattern.containsMatchIn(translatedText)
        val ignoredSourceClockRanges = if (localizedClock) {
            clockTimePattern.findAll(sourceText).map { it.range }.toList()
        } else {
            emptyList()
        }
        val sourceNumbers = normalizedExplicitNumbers(sourceText, sourceLanguage, ignoredSourceClockRanges)
        val ignoredTargetClockRanges = if (localizedClock) {
            (clockTimePattern.findAll(translatedText).map { it.range } +
                chineseClockPattern.findAll(translatedText).map { it.range }).toList()
        } else {
            emptyList()
        }
        val translatedNumbers = normalizedTranslationNumbers(
            translatedText, targetLanguage, ignoredTargetClockRanges,
        )
        if (sourceNumbers.isEmpty()) return null
        // Only compare extra quantities when the source actually states an explicit number.
        // This avoids treating a natural Chinese classifier (for example "一只猫" for "a cat")
        // as invented numeric information when the source uses an indefinite article.

        val available = translatedNumbers.groupingBy { it }.eachCount().toMutableMap()
        var missing = 0
        sourceNumbers.forEach { number ->
            val count = available[number] ?: 0
            if (count > 0) available[number] = count - 1 else missing++
        }
        val extra = available.values.sum()
        return when {
            missing > 0 -> "显式数字校验失败：原文有 " + sourceNumbers.size + " 个数字项，译文有 " +
                translatedNumbers.size + " 个数字项；其中 " + missing + " 项未保留或数值不一致"
            extra > 0 -> "显式数字校验失败：译文比原文多出 " + extra + " 个无法对应的数字项，可能擅自增加了数值"
            else -> null
        }
    }

    private fun normalizedTranslationNumbers(
        value: String,
        numberFormatLanguage: String,
        skipRanges: List<IntRange> = emptyList(),
    ): List<String> {
        val tokens = mutableListOf<Pair<Int, String>>()
        val fractions = explicitFractionPattern.findAll(value)
            .filterNot { isSlashDateLike(it, value) }
            .toList()
        fractions.forEach { match ->
            if (skipRanges.none { it.contains(match.range.first) && it.contains(match.range.last) }) {
                normalizeArabicFraction(match.value)?.let { tokens += match.range.first to it }
            }
        }
        explicitNumberPattern.findAll(value).forEach { match ->
            if (fractions.any { it.range.contains(match.range.first) && it.range.contains(match.range.last) }) return@forEach
            if (skipRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            normalizeExplicitNumberMatch(match, value, numberFormatLanguage)?.let { tokens += match.range.first to it }
        }

        val chineseFractions = chineseFractionPattern.findAll(value).toList()
        chineseFractions.forEach { match ->
            if (skipRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            val denominator = parseChineseInteger(match.groupValues[1])?.toBigDecimalOrNull() ?: return@forEach
            val numerator = parseChineseInteger(match.groupValues[2])?.toBigDecimalOrNull() ?: return@forEach
            if (denominator.compareTo(BigDecimal.ZERO) == 0) return@forEach
            val fraction = runCatching {
                numerator.divide(denominator, 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            }.getOrNull() ?: return@forEach
            tokens += match.range.first to fraction
        }

        val ranges = chineseRangePattern.findAll(value).toList()
        ranges.forEach { match ->
            if (skipRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            val start = parseChineseNumeral(match.groupValues[1]) ?: return@forEach
            val end = parseChineseNumeral(match.groupValues[2]) ?: return@forEach
            val firstRange = match.groups[1]?.range ?: return@forEach
            val secondRange = match.groups[2]?.range ?: return@forEach
            tokens += firstRange.first to start
            tokens += secondRange.first to end
        }

        val protectedRanges = fractions.map { it.range } +
            chineseFractions.map { it.range } +
            ranges.map { it.range }
        chineseNumeralPattern.findAll(value).forEach { match ->
            if (protectedRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            if (skipRanges.any { it.contains(match.range.first) && it.contains(match.range.last) }) return@forEach
            val raw = match.value
            val nextChar = value.getOrNull(match.range.last + 1)
            if (raw == "零" && nextChar == '下') return@forEach
            val suffix = value.substring(match.range.last + 1, minOf(value.length, match.range.last + 4))
            val hasMagnitude = raw.any { it in "十百千万萬亿億" }
            val isDigitSequence = raw.length >= 2 && raw.all { it in "零〇一二两兩三四五六七八九" }
            val hasClassifier = listOf(
                "个", "個", "只", "条", "條", "本", "件", "位", "张", "張", "杯", "块", "塊",
                "岁", "歲", "名", "次", "层", "層", "份", "辆", "輛", "座", "家", "种", "種",
                "倍", "人", "号", "號", "年", "月", "日", "天", "周", "星期", "小时", "小時",
                "分钟", "分鐘", "秒", "公里", "千米", "米", "厘米", "毫米", "公斤", "千克", "克",
                "毫升", "升", "元", "美元", "欧元", "英镑", "度", "百分之", "百分比", "点", "點", "分",
            ).any(suffix::startsWith)
            val prefix = value.substring(maxOf(0, match.range.first - 6), match.range.first)
            val hasNumberPrefix = Regex("""(?:是|为|為|有|共|达|達|约|約|第|超过|超過|至少|少于|少於|多于|多於|等于|等於|只剩|还剩|還剩|剩下|剩余|剩餘|重达|重達|长达|長達|正好|刚好|百分之)$""")
                .containsMatchIn(prefix)
            val trailingHalf = (raw == "半" || raw == "一半") ||
                ((raw.endsWith('点') || raw.endsWith('點')) && suffix.startsWith("半"))
            val zeroBelow = prefix.endsWith("零下")
            if (!hasMagnitude && !isDigitSequence && !hasClassifier && !hasNumberPrefix && !trailingHalf) return@forEach
            parseChineseNumeral(raw, suffix)?.let { parsed ->
                val normalized = if (zeroBelow) {
                    runCatching { BigDecimal(parsed).negate().stripTrailingZeros().toPlainString() }.getOrNull() ?: parsed
                } else parsed
                tokens += match.range.first to normalized
            }
        }
        // A recognized expression contributes one token per numeric value, never one per character.
        return tokens.sortedBy { it.first }.distinctBy { it.first }.map { it.second }
    }


    private fun parseChineseNumeral(rawValue: String, followingText: String = ""): String? {
        if (rawValue.isEmpty()) return null
        if (rawValue == "半" || rawValue == "一半") return "0.5"
        val negative = rawValue.first() in "负負"
        val raw = rawValue.removePrefix("负").removePrefix("負").removePrefix("正")
        if (raw.isEmpty()) return null
        val decimalAt = raw.indexOfFirst { it == '点' || it == '點' }
        val integerPart = if (decimalAt >= 0) raw.substring(0, decimalAt) else raw
        val fractionPart = if (decimalAt >= 0) raw.substring(decimalAt + 1) else ""
        if (decimalAt >= 0 && fractionPart.isEmpty() && followingText.startsWith("半")) {
            val whole = parseChineseInteger(integerPart) ?: return null
            val half = BigDecimal(whole).add(BigDecimal("0.5"))
            return (if (negative) half.negate() else half).stripTrailingZeros().toPlainString()
        }
        if (decimalAt >= 0 && (fractionPart.isEmpty() || fractionPart.any { chineseDigitValue(it) == null })) return null
        val whole = parseChineseInteger(integerPart) ?: return null
        val value = if (decimalAt >= 0) {
            BigDecimal("$whole." + fractionPart.mapNotNull(::chineseDigitValue).joinToString(""))
        } else {
            BigDecimal(whole)
        }
        return (if (negative) value.negate() else value).stripTrailingZeros().toPlainString()
    }

    private fun parseChineseInteger(value: String): String? {
        if (value.isEmpty()) return "0"
        // Colloquial "一万三" means 13,000, not 10,003.
        val tenThousandShorthand = Regex("""^([一二两兩三四五六七八九])([万萬])([一二两兩三四五六七八九])$""")
            .matchEntire(value)
        if (tenThousandShorthand != null) {
            val first = chineseDigitValue(tenThousandShorthand.groupValues[1].first()) ?: return null
            val last = chineseDigitValue(tenThousandShorthand.groupValues[3].first()) ?: return null
            return (first * 10_000 + last * 1_000).toString()
        }
        val lowerMagnitudeShorthand = Regex("""^([一二两兩三四五六七八九])([千百])([一二两兩三四五六七八九])$""")
            .matchEntire(value)
        if (lowerMagnitudeShorthand != null) {
            val first = chineseDigitValue(lowerMagnitudeShorthand.groupValues[1].first()) ?: return null
            val scale = when (lowerMagnitudeShorthand.groupValues[2]) {
                "千" -> 1_000
                else -> 100
            }
            val trailingScale = scale / 10
            val last = chineseDigitValue(lowerMagnitudeShorthand.groupValues[3].first()) ?: return null
            return (first * scale + last * trailingScale).toString()
        }
        if (value.all { chineseDigitValue(it) != null }) {
            return value.mapNotNull(::chineseDigitValue).joinToString("").ifEmpty { "0" }
        }
        var total = 0L
        var section = 0L
        var number = 0L
        value.forEach { char ->
            val digit = chineseDigitValue(char)
            when {
                digit != null -> number = digit.toLong()
                char == '十' -> { section += (if (number == 0L) 1L else number) * 10L; number = 0L }
                char == '百' -> { section += (if (number == 0L) 1L else number) * 100L; number = 0L }
                char == '千' -> { section += (if (number == 0L) 1L else number) * 1_000L; number = 0L }
                char == '万' || char == '萬' -> {
                    section += number
                    total += (if (section == 0L) 1L else section) * 10_000L
                    section = 0L
                    number = 0L
                }
                char == '亿' || char == '億' -> {
                    section += number
                    total = (total + if (section == 0L) 1L else section) * 100_000_000L
                    section = 0L
                    number = 0L
                }
                else -> return null
            }
        }
        return (total + section + number).toString()
    }

    private fun chineseDigitValue(char: Char): Int? = when (char) {
        '零', '〇' -> 0
        '一' -> 1
        '二', '两', '兩' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
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
        "take", "than", "thank", "thanks", "that", "the", "their", "them", "then",
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
        decision: TranslationQualityDecision? = null,
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
        val mismatch = decision?.targetLanguageMismatchReason
            ?: targetLanguageMismatchReason(sourceText, output, sourceLanguage, targetLanguage)
        val numericMismatch = decision?.numericMismatchReason
            ?: numericContentMismatchReason(sourceText, output, sourceLanguage, targetLanguage)
        val numericUnitMismatch = decision?.numericUnitAssociationMismatchReason
            ?: numericUnitAssociationMismatchReason(sourceText, output, sourceLanguage, targetLanguage)
        val unitMismatch = decision?.unitMismatchReason
            ?: unitContentMismatchReason(sourceText, output, sourceLanguage, targetLanguage)
        val semanticMismatch = decision?.semanticMismatchReason
            ?: semanticConsistencyMismatchReason(sourceText, output, sourceLanguage, targetLanguage)
        val scriptMismatch = decision?.scriptMismatchReason ?: if (SourceLanguagePolicy.isChinese(sourceLanguage)) null
            else targetChineseScriptMismatchReason(output, targetLanguage)
        val nonTranslatable = isNonTranslatableCue(sourceText)
        val sourceEcho = decision?.sourceEcho ?: isSourceEcho(sourceText, output, sourceLanguage)
        val punctuationOnly = decision?.let { !it.accepted && it.reasonCode == "UNSPEAKABLE_TRANSLATION" }
            ?: isPunctuationOnlyTranslation(sourceText, output)
        val speakable = TtsTextPolicy.hasSpeakableContent(output)
        val shortSpellingMatch = diagnosticShortSpellingMatch(sourceText, output)
        val shortCueAccepted = decision?.shortUntranslatedAccepted
            ?: isAcceptableShortUntranslatedCue(sourceText, output, sourceLanguage, targetLanguage)
        val modelMeta = decision?.modelMetaResponseReason
            ?: translationMetaResponseReason(sourceText, output, sourceLanguage, targetLanguage)
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
            append("; explicitNumericContentMismatch=").append(numericMismatch != null)
            append("; numericMismatchReason=").append(numericMismatch?.take(180) ?: "无")
            append("; numericUnitAssociationMismatch=").append(numericUnitMismatch != null)
            append("; numericUnitAssociationReason=").append(numericUnitMismatch?.take(180) ?: "无")
            append("; explicitUnitContentMismatch=").append(unitMismatch != null)
            append("; unitMismatchReason=").append(unitMismatch?.take(180) ?: "无")
            append("; semanticConsistencyMismatch=").append(semanticMismatch != null)
            append("; semanticMismatchReason=").append(semanticMismatch?.take(180) ?: "无")
            append("; targetChineseScriptMismatch=").append(scriptMismatch != null)
            append("; scriptMismatchReason=").append(scriptMismatch?.take(180) ?: "无")
            append("; modelMetaResponse=").append(modelMeta != null)
            append("; modelMetaReason=").append(modelMeta?.take(180) ?: "无")
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
        if (output in setOf("正在翻译", "正在处理", "翻译中", "处理中", "翻译失败", "请求失败")) {
            return "译文只是处理状态提示，不是字幕内容"
        }

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

        val serviceFailureMarkers = listOf(
            "请求过于频繁", "请求频率过高", "服务暂时不可用", "服务器暂时不可用",
            "翻译服务异常", "翻译接口异常", "接口返回异常", "网络请求失败",
            "网络连接失败", "认证失败", "鉴权失败", "权限不足", "密钥无效",
            "api密钥", "服务端错误", "系统发生错误", "服务器错误",
        )
        val hasServiceFailure = serviceFailureMarkers.any(output::contains)
        val asksForRetryOrConfiguration = listOf(
            "请稍后重试", "稍后再试", "请重试", "检查网络", "检查配置",
            "检查密钥", "重新配置", "再次尝试",
        ).any(output::contains)
        val sourceDiscussesServiceFailure = listOf(
            "server", "network", "request limit", "too many requests",
            "translation service", "api key", "authentication", "unauthorized",
            "access denied", "http 429", "http 500", "try again later",
        ).any(source::contains)
        if (hasServiceFailure && asksForRetryOrConfiguration && !sourceDiscussesServiceFailure) {
            return "译文疑似翻译服务/网络/鉴权错误提示，不是字幕内容"
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

    private val retainedAcronyms = setOf(
        "ai", "api", "atm", "bbc", "ceo", "cpu", "dna", "diy", "dvd", "eu", "fbi",
        "fda", "gps", "hiv", "hr", "id", "iq", "iso", "nba", "nfl", "npr", "pc",
        "pdf", "phd", "ram", "rna", "sms", "tv", "uk", "un", "usa", "usb", "utc",
        "vip", "wifi", "www",
    )

    private fun isLikelyRetainedTerm(
        token: String,
        letters: String,
        sourceTokens: Set<String>,
    ): Boolean {
        val normalized = normalize(token)
        // Common English words remain translatable even when sentence-initial or uppercased.
        if (normalized in commonShortWords) return false
        // A model must not invent an all-caps word or a model/brand name and have it exempted
        // from target-language validation. Numbered identifiers and mixed-case brands must
        // also be grounded in the source cue.
        if (token.any { it.isDigit() }) return normalized in sourceTokens
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) {
            return normalized in sourceTokens || normalized in retainedAcronyms
        }
        if (letters.drop(1).any { it.isUpperCase() }) return normalized in sourceTokens
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
