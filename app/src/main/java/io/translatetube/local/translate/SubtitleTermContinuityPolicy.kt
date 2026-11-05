package io.translatetube.local.translate

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative source-side key extraction for long-range subtitle continuity.
 *
 * This is not named-entity recognition. It deliberately keeps only visible, high-confidence
 * Latin names, acronyms and mixed-case product names, and uses them to select aligned historical
 * examples for the existing translation request. It never creates or guesses a translation.
 */
internal object SubtitleTermContinuityPolicy {
    private val candidateToken = Regex(
        """(?<![\p{L}\p{N}])(?:[A-Z][A-Za-z0-9]*(?:['’–-][A-Za-z0-9]+)*|[A-Z]{2,8}|[a-z][A-Z][A-Za-z0-9]*)"""
    )
    private val commonWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "because", "but", "by", "can", "could",
        "do", "does", "for", "from", "had", "has", "have", "he", "her", "here", "his", "how",
        "i", "if", "in", "is", "it", "its", "let", "may", "me", "my", "no", "not", "of", "on",
        "or", "our", "she", "so", "that", "the", "their", "them", "there", "these", "they",
        "this", "those", "to", "us", "was", "we", "were", "what", "when", "where", "which",
        "who", "why", "will", "with", "would", "yes", "you", "your", "okay", "ok", "hello",
        "thanks", "thank", "please", "goodbye", "today", "tomorrow", "yesterday",
    )

    fun extractStableTerms(texts: List<String>): Set<String> =
        texts.asSequence()
            .flatMap { text -> candidateToken.findAll(text).map { it.value } }
            .map(::normalize)
            .filter { it.length >= 2 && it !in commonWords }
            .toSet()

    fun containsStableTerm(text: String, terms: Set<String>): Boolean {
        if (terms.isEmpty() || text.isBlank()) return false
        return candidateToken.findAll(text).any { match ->
            val key = normalize(match.value)
            key.length >= 2 && key !in commonWords && key in terms
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
