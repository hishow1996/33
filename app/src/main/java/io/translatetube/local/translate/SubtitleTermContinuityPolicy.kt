package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale
import io.translatetube.local.subs.SubtitleLanguageDetector

/**
 * Conservative source-side term extraction for long-range subtitle continuity.
 *
 * Latin-script candidates are filtered by the video's known language. Japanese and Korean
 * support is intentionally limited to repeated Katakana terms and names with explicit honorifics;
 * this avoids treating every CJK word as a guessed person or brand.
 */
internal object SubtitleTermContinuityPolicy {
    private val candidateLatinToken = Regex(
        """(?<![\p{L}\p{N}])(?:[A-Z][A-Za-z0-9]*(?:['’–-][A-Za-z0-9]+)*|[A-Z]{2,8}|[a-z][A-Z][A-Za-z0-9]*)"""
    )
    private val katakanaToken = Regex("""[\u30A1-\u30FA\u30FC]{2,}""")
    private val japaneseHonorificName = Regex("""([\u4E00-\u9FFF]{2,6})(?:さん|様|さま|氏|先生|君|ちゃん)""")
    private val koreanHonorificName = Regex("""(?<![가-힣])([가-힣]{2,4})\s*(?:씨|님|선생님)""")

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
        texts.forEach { text ->
            candidateLatinToken.findAll(text).forEach { match ->
                val key = normalize(match.value)
                if (key.length >= 2 && key !in stopWords) result += key
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
        }
        return result
    }

    fun containsStableTerm(
        text: String,
        terms: Set<String>,
        sourceLanguage: String = "auto",
    ): Boolean {
        if (terms.isEmpty() || text.isBlank()) return false
        val language = resolveLanguage(listOf(text), sourceLanguage)
        return extractStableTerms(listOf(text), language).any(terms::contains)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
