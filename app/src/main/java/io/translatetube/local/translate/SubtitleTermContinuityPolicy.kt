package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale
import io.translatetube.local.subs.SubtitleLanguageDetector

/**
 * Conservative source-side term extraction for long-range subtitle continuity.
 *
 * Latin-script candidates are filtered by the video's known language. Uncased scripts use
 * explicit name markers or a small set of established entities; arbitrary words are not promoted
 * to names merely because the script has no uppercase/lowercase distinction.
 */
internal object SubtitleTermContinuityPolicy {
    // Allow Latin names embedded directly beside another writing system (e.g. このTikTokは…),
    // while still requiring a boundary between adjacent Latin letters/digits.
    private val candidateLatinToken = Regex(
        """(?<![A-Za-z0-9])(?:\p{Lu}[\p{L}\p{M}\p{N}]*(?:['’\u2010-\u2015\u2212-][\p{L}\p{M}\p{N}]+)*|[a-z][A-Z][A-Za-z0-9]*)"""
    )
    private val katakanaToken = Regex("""[\u30A1-\u30FA\u30FC]{2,}""")
    // Same-script boundaries let Cyrillic, Greek, Armenian and Georgian names be recognized when adjacent
    // to Japanese, Arabic, Hebrew, Devanagari or other writing systems.
    private val casedScriptNameToken = Regex(
        """(?<![\p{IsCyrillic}\p{IsGreek}\p{IsArmenian}\p{IsGeorgian}\p{M}\p{N}])\p{Lu}[\p{Ll}\p{M}]{1,}(?:['’–-]\p{Lu}[\p{Ll}\p{M}]+)*(?![\p{IsCyrillic}\p{IsGreek}\p{IsArmenian}\p{IsGeorgian}\p{M}\p{N}])"""
    )
    private val japaneseHonorificName = Regex("""([\u4E00-\u9FFF]{2,6})(?:さん|様|さま|氏|先生|君|ちゃん)""")
    private val koreanHonorificName = Regex("""(?<![가-힣])([가-힣]{2,4})\s*(?:씨|님|선생님)""")
    private val arabicHonorificName = Regex(
        """(?:السيد|السيدة|الدكتور|الدكتورة|الأستاذ|الأستاذة|الشيخ|الشيخة|المهندس|المهندسة|الرئيس|الرئيسة|الأمير|الأميرة|الملك|الملكة|آقای|آقا|خانم|دکتر|استاد|جناب|محترم|محترمہ|مسٹر|مسز)\s+([\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]{2,})"""
    )
    private val hebrewHonorificName = Regex(
        """(?:מר|מרת|ד["׳״]ר|דר׳|פרופ׳|הרב|רבי)\s+([\u05D0-\u05EA]{2,})"""
    )
    private val devanagariHonorificName = Regex(
        """(?:श्री|श्रीमती|डॉ\.?|प्रो\.?|माननीय)\s+([\u0900-\u097F]{2,})"""
    )
    private val thaiHonorificName = Regex(
        """(?:คุณ|นาย|นางสาว|นาง|ดร\.?|ศ\.?|อาจารย์)\s+([\u0E00-\u0E7F]{2,})"""
    )
    private val bengaliHonorificName = Regex(
        """(?:ডঃ|ডা\.?|শ্রী|শ্রীমতী|জনাব|অধ্যাপক|মিস)\s+([\u0980-\u09FF]{2,})"""
    )
    private val khmerHonorificName = Regex(
        """(?:លោកបណ្ឌិត|លោកស្រី|លោក|អ្នកស្រី|សាស្ត្រាចារ្យ)\s+([\u1780-\u17FF]{2,})"""
    )
    private val laoHonorificName = Regex(
        """(?:ທ່ານ|ນາງ|ດຣ\.?|ອາຈານ|ສາດສະດາຈານ)\s+([\u0E80-\u0EFF]{2,})"""
    )
    private val myanmarHonorificName = Regex(
        """(?:ဒေါက်တာ|ဦး|ဒေါ်|ကို|မ|ဆရာမ?|ဆရာ)\s+([\u1000-\u109F]{2,})"""
    )
    private val tamilHonorificName = Regex(
        """(?:திரு\.?|திருமதி|செல்வி|டாக்டர்|பேராசிரியர்)\s+([\u0B80-\u0BFF]{2,})"""
    )
    private val teluguHonorificName = Regex(
        """(?:శ్రీ|శ్రీమతి|డా\.?|డాక్టర్|ప్రొఫెసర్|మిస్టర్)\s+([\u0C00-\u0C7F]{2,})"""
    )
    private val kannadaHonorificName = Regex(
        """(?:ಶ್ರೀ|ಶ್ರೀಮತಿ|ಡಾ\.?|ವೈದ್ಯರು|ಪ್ರೊಫೆಸರ್|ಮಿಸ್ಟರ್)\s+([\u0C80-\u0CFF]{2,})"""
    )
    private val malayalamHonorificName = Regex(
        """(?:ശ്രീ|ശ്രീമതി|ഡോ\.?|ഡോക്ടർ|പ്രൊഫസർ|മിസ്റ്റർ)\s+([\u0D00-\u0D7F]{2,})"""
    )
    private val sinhalaHonorificName = Regex(
        """(?:මහතා|මහත්මිය|වෛද්‍ය|ආචාර්ය|මහාචාර්ය)\s+([\u0D80-\u0DFF]{2,})"""
    )
    private val georgianHonorificName = Regex(
        """(?:ბატონი|ქალბატონი|დოქტორი|პროფესორი)\s+([\u10A0-\u10FF\u1C90-\u1CBF]{2,})"""
    )
    private val armenianHonorificName = Regex(
        """(?:Պարոն|Տիկին|Դոկտոր|Պրոֆեսոր)\s+([\u0530-\u058F]{2,})"""
    )
    private val ethiopicHonorificName = Regex(
        """(?:አቶ|ወ/ሮ|ዶ/ር|ፕሮፌሰር)\s+([\u1200-\u137F\u1380-\u139F]{2,})"""
    )

    private val introducedLatinName = Regex(
        """(?i)\b(?:(?:my|his|her|their)\s+name\s+is|call\s+me|meet)\s+([\p{L}\p{M}][\p{L}\p{M}'’\u2010-\u2015-]{1,30})"""
    )
    private val standaloneNameTitle = Regex(
        """(?i)^(?:mr|mrs|ms|miss|dr|doctor|prof|professor|sir|madam|mme|m|sr|sra)\.?$"""
    )
    private val commonLatinWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "because", "but", "by", "can", "could",
        "do", "does", "for", "from", "had", "has", "have", "he", "her", "here", "his", "how",
        "i", "if", "in", "is", "it", "its", "let", "may", "me", "my", "no", "not", "of", "on",
        "or", "our", "she", "so", "that", "the", "their", "them", "there", "these", "they",
        "this", "those", "to", "us", "was", "we", "were", "what", "when", "where", "which",
        "who", "why", "will", "with", "would", "yes", "you", "your", "okay", "ok", "hello",
        "thanks", "thank", "please", "goodbye", "today", "tomorrow", "yesterday",
    )
    private val languageStopWords = mapOf(
        "fr" to setOf("je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "le", "la", "les", "des", "du", "de", "un", "une", "et", "est", "sont", "pas", "pour", "avec", "mais", "dans", "qui", "que", "quoi", "où", "oui", "non", "bonjour", "merci"),
        "de" to setOf("ich", "du", "er", "sie", "es", "wir", "ihr", "sie", "der", "die", "das", "den", "dem", "ein", "eine", "und", "ist", "sind", "nicht", "kein", "keine", "mit", "für", "von", "zu", "auf", "was", "wer", "wie", "hallo", "danke"),
        "es" to setOf("yo", "tú", "tu", "él", "ella", "nosotros", "vosotros", "ellos", "ellas", "el", "la", "los", "las", "un", "una", "y", "es", "son", "no", "con", "para", "por", "de", "en", "qué", "que", "hola", "gracias"),
        "pt" to setOf("eu", "tu", "ele", "ela", "nós", "vocês", "eles", "elas", "o", "a", "os", "as", "um", "uma", "e", "é", "são", "não", "com", "para", "por", "de", "em", "que", "olá", "obrigado", "obrigada"),
        "it" to setOf("io", "tu", "lui", "lei", "noi", "voi", "loro", "il", "lo", "la", "gli", "le", "un", "una", "e", "è", "sono", "non", "con", "per", "di", "che", "ciao", "grazie"),
        "ru" to setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они", "меня", "тебя", "его", "ее", "её", "нас", "вас", "их", "это", "этот", "эта", "эти", "что", "как", "где", "кто", "куда", "почему", "когда", "не", "да", "нет", "привет", "здравствуйте", "спасибо", "пожалуйста", "доброе", "добрый", "утро", "день", "вечер", "пока", "хорошо", "ладно", "конечно", "сейчас", "сегодня", "завтра", "вчера"),
        "el" to setOf("εγώ", "εσύ", "αυτός", "αυτή", "αυτό", "εμείς", "εσείς", "αυτοί", "αυτές", "το", "τα", "και", "είναι", "δεν", "ναι", "όχι", "γεια", "ευχαριστώ", "παρακαλώ", "καλημέρα", "καλησπέρα", "αντίο", "σήμερα", "αύριο", "χθες", "πού", "τι", "πώς", "ποιος", "ποια"),
    )
    private val allLanguageStopWords = languageStopWords.values.flatten().toSet() + setOf(
        "called", "calls", "came", "comes", "arrived", "arrive", "replied", "replies", "asked",
        "asks", "answered", "answers", "used", "uses", "met", "meets", "said", "says", "told",
        "talked", "talks", "spoke", "speaks", "went", "goes", "left", "leaves", "started",
        "starts", "finished", "finished", "watched", "watching", "worked", "works", "parle",
        "parles", "parlent", "parlons", "sommes", "étais", "était", "étions", "haben", "hat",
        "hatte", "geht", "gehen", "kommt", "kommen", "hace", "hacen", "tiene", "tienen",
        "quiere", "quieren", "vamos", "está", "estan", "están", "estoy", "habla", "hablan",
    )
    private val japaneseCommonKatakana = setOf("テレビ", "カメラ", "コーヒー", "ホテル", "タクシー")
    private val koreanCommonTerms = setOf("사람", "오늘", "내일", "어제", "시간", "이것", "저것", "여기", "저기")
    private val knownUncasedScriptTerms = setOf(
        "東京", "大阪", "京都", "名古屋", "北海道", "沖縄", "富士山", "新宿", "渋谷",
        "서울", "부산", "인천", "제주도", "김민수", "김연아",
        "دبي", "القاهرة", "أبوظبي", "الرياض",
        "ירושלים", "תלאביב", "חיפה",
        "กรุงเทพ", "กรุงเทพฯ", "เชียงใหม่", "ঢাকা", "চট্টগ্রাম", "ភ្នំពេញ",
        "ວຽງຈັນ", "ရန်ကုန်", "မန္တလေး",
        "சென்னை", "மதுரை", "கோயம்புத்தூர்",
        "హైదరాబాద్", "విజయవాడ",
        "ಬೆಂಗಳೂರು", "ಮೈಸೂರು",
        "തിരുവനന്തപുരം", "കൊച്ചി",
        "කොළඹ", "මහනුවර",
        "თბილისი", "ბათუმი", "Երևան", "Գյումրի",
        "አዲስ አበባ", "ድሬዳዋ",
    )

    fun resolveLanguage(
        texts: List<String>,
        sourceLanguage: String,
    ): String {
        if (!SourceLanguagePolicy.isUndetermined(sourceLanguage)) {
            return SourceLanguagePolicy.canonicalPrimaryCode(sourceLanguage)
        }
        val sample = texts.asSequence().filter { it.isNotBlank() }.take(8).joinToString(" ").take(4_000)
        val scriptLanguage = SubtitleLanguageDetector.detect(sample)
        if (scriptLanguage != "auto") return SourceLanguagePolicy.canonicalPrimaryCode(scriptLanguage)
        val lower = sample.lowercase(Locale.ROOT)
        return when {
            Regex("""\bne\b.{0,48}\b(?:pas|plus|jamais|rien|personne)\b""").containsMatchIn(lower) -> "fr"
            Regex("""\bich\b.{0,48}\b(?:nicht|kein|keine|nie|nichts)\b""").containsMatchIn(lower) -> "de"
            Regex("""\bno\s+(?:quiero|puedo|sé|se|estoy|soy|tengo|hay|vamos)\b""").containsMatchIn(lower) -> "es"
            Regex("""\b(?:don't|doesn't|didn't|can't|cannot|won't|isn't|aren't|wasn't|weren't|haven't|hasn't)\b""").containsMatchIn(lower) -> "en"
            else -> "auto"
        }
    }

    fun extractStableTerms(
        texts: List<String>,
        sourceLanguage: String = "auto",
    ): Set<String> {
        val language = resolveLanguage(texts, sourceLanguage)
        val stopWords = commonLatinWords + if (language == "auto") allLanguageStopWords else languageStopWords[language].orEmpty()
        val result = LinkedHashSet<String>()
        texts.forEachIndexed { index, cue ->
            val nextCue = texts.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
            val continuityText = if (nextCue == null) cue else "$cue $nextCue"
            // Keep ordinary cue extraction local. Only merge across a cue boundary for a clear
            // name introduction or an honorific/title that directly precedes a name.
            val text = cue
            candidateLatinToken.findAll(text).forEach { match ->
                if (Character.UnicodeScript.of(match.value.first().code) == Character.UnicodeScript.LATIN) {
                    val key = normalize(match.value)
                    if (key.length >= 2 && key !in stopWords) result += key
                }
            }
            introducedLatinName.findAll(continuityText).forEach { match ->
                val name = match.groupValues[1]
                val key = normalize(name)
                if (Character.UnicodeScript.of(name.first().code) == Character.UnicodeScript.LATIN &&
                    key.length >= 2 && key !in stopWords
                ) {
                    result += key
                }
            }
            if (standaloneNameTitle.matches(cue.trim())) {
                val followingToken = nextCue
                    ?.trim()
                    ?.split(Regex("""\s+"""))
                    ?.firstOrNull()
                    ?.trim { it in ".,!?;:，。！？；：" }
                    .orEmpty()
                val key = normalize(followingToken)
                if (followingToken.isNotEmpty() &&
                    Character.UnicodeScript.of(followingToken.first().code) == Character.UnicodeScript.LATIN &&
                    key.length >= 2 && key !in stopWords
                ) {
                    result += key
                }
            }
            casedScriptNameToken.findAll(text).forEach { match ->
                val script = Character.UnicodeScript.of(match.value.first().code)
                if (script == Character.UnicodeScript.CYRILLIC ||
                    script == Character.UnicodeScript.GREEK ||
                    script == Character.UnicodeScript.ARMENIAN ||
                    script == Character.UnicodeScript.GEORGIAN
                ) {
                    val key = normalize(match.value)
                    if (key.length >= 2 && key !in stopWords) result += key
                }
            }
            val normalizedText = normalize(text)
            knownUncasedScriptTerms.forEach { knownTerm ->
                val normalizedTerm = normalize(knownTerm)
                if (normalizedTerm.isNotBlank() && normalizedText.contains(normalizedTerm)) {
                    result += normalizedTerm
                }
            }
            if (language == "ja") {
                katakanaToken.findAll(text).forEach { match ->
                    val key = normalize(match.value)
                    if (key !in japaneseCommonKatakana) result += key
                }
                japaneseHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "ko") {
                koreanHonorificName.findAll(text).forEach { match ->
                    val key = normalize(match.groupValues[1])
                    if (key !in koreanCommonTerms) result += key
                }
            }
            if (language in setOf("ar", "fa", "ur")) {
                arabicHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "he") {
                hebrewHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "hi") {
                devanagariHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "th") {
                thaiHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "bn") {
                bengaliHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "km") {
                khmerHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "lo") {
                laoHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "my") {
                myanmarHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "ta") {
                tamilHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "te") {
                teluguHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "kn") {
                kannadaHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "ml") {
                malayalamHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "si") {
                sinhalaHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "ka") {
                georgianHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "hy") {
                armenianHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
            if (language == "am" || language == "ti") {
                ethiopicHonorificName.findAll(text).forEach { match ->
                    result += normalize(match.groupValues[1])
                }
            }
        }
        return result
    }

    /**
     * Return only high-confidence entities from scripts without upper/lowercase distinctions.
     * This is shared with subtitle quality validation so a source-grounded Arabic/Hebrew/Indic
     * name can remain in a Chinese sentence without allowing arbitrary foreign words through.
     */
    fun extractHighConfidenceUncasedEntityTokens(
        text: String,
        sourceLanguage: String = "auto",
    ): Set<String> {
        if (text.isBlank()) return emptySet()
        val language = resolveLanguage(listOf(text), sourceLanguage)
        val acceptedScripts = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.ARABIC,
            Character.UnicodeScript.HEBREW,
            Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.BENGALI,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.TAMIL,
            Character.UnicodeScript.TELUGU,
            Character.UnicodeScript.KANNADA,
            Character.UnicodeScript.MALAYALAM,
            Character.UnicodeScript.SINHALA,
            Character.UnicodeScript.GEORGIAN,
            Character.UnicodeScript.ARMENIAN,
            Character.UnicodeScript.ETHIOPIC,
        )
        return extractStableTerms(listOf(text), language).filterTo(LinkedHashSet()) { term ->
            term.isNotBlank() &&
                Character.UnicodeScript.of(term.codePointAt(0)) in acceptedScripts
        }
    }

    fun containsStableTerm(
        text: String,
        terms: Set<String>,
        sourceLanguage: String = "auto",
    ): Boolean {
        if (terms.isEmpty() || text.isBlank()) return false
        val normalizedText = normalize(text)
        val language = resolveLanguage(listOf(text), sourceLanguage)
        val extracted = extractStableTerms(listOf(text), language)
        if (extracted.any(terms::contains)) return true

        // Accent and punctuation variants may represent the same source-grounded name. Apply
        // this relaxed key only when the context has exactly one candidate for that key; if two
        // source entities differ only by diacritics, keep them distinct rather than guessing.
        val uniqueNameKeys = terms.groupBy(::comparisonNameKey)
            .filterValues { candidates -> candidates.size == 1 }
            .keys
        if (extracted.any { comparisonNameKey(it) in uniqueNameKeys }) return true

        // Uncased-script names can reappear without their honorific. Match them as complete
        // words in spaced scripts, and as substrings in scripts that normally omit spaces.
        return terms.any { term ->
            if (term.isBlank()) return@any false
            val script = Character.UnicodeScript.of(term.codePointAt(0))
            val unsegmented = script in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.THAI,
                Character.UnicodeScript.LAO,
                Character.UnicodeScript.KHMER,
                Character.UnicodeScript.MYANMAR,
            )
            if (unsegmented) {
                normalizedText.contains(term)
            } else {
                var index = normalizedText.indexOf(term)
                var found = false
                while (index >= 0 && !found) {
                    val end = index + term.length
                    val leftBoundary = index == 0 ||
                        !Character.isLetterOrDigit(normalizedText.codePointBefore(index))
                    val rightBoundary = end >= normalizedText.length ||
                        !Character.isLetterOrDigit(normalizedText.codePointAt(end))
                    found = leftBoundary && rightBoundary
                    if (!found) index = normalizedText.indexOf(term, index + 1)
                }
                found
            }
        }
    }

    private fun comparisonNameKey(value: String): String =
        Normalizer.normalize(
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("’", "'")
                .replace("‘", "'")
                .replace("ʼ", "'")
                .replace("‐", "-")
                .replace("‑", "-")
                .replace("‒", "-")
                .replace("–", "-")
                .replace("—", "-")
                .lowercase(Locale.ROOT),
            Normalizer.Form.NFD,
        ).replace(Regex("""\p{M}+"""), "")
            .filter { it.isLetterOrDigit() }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("’", "'")
            .replace("‘", "'")
            .replace("ʼ", "'")
            .replace("‐", "-")
            .replace("‑", "-")
            .replace("‒", "-")
            .replace("–", "-")
            .replace("—", "-")
            .lowercase(Locale.ROOT)
}
