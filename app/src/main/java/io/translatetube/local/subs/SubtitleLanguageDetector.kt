package io.translatetube.local.subs

/**
 * Conservative script-based language hint for imported subtitle files.
 *
 * It intentionally returns "auto" when Latin-script text cannot be distinguished safely. Online
 * Google translation and LLM providers can detect the source language from the text; guessing
 * "English" for every non-Chinese subtitle silently mis-translates Japanese, Korean, and many
 * other imported tracks.
 */
object SubtitleLanguageDetector {
    private val scriptPatterns = listOf(
        Regex("[\\u3040-\\u30FF\\u31F0-\\u31FF]"),
        Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]"),
        Regex("[\\u0E00-\\u0E7F]"),
        Regex("[\\u0600-\\u06FF\\u0750-\\u077F]"),
        Regex("[\\u0900-\\u097F]"),
        Regex("[\\u0590-\\u05FF]"),
        Regex("[\\u0370-\\u03FF]"),
        Regex("[\\u0406\\u0456\\u0404\\u0454\\u0490\\u0491\\u0407\\u0457]"),
        Regex("[\\u040E\\u045E]"),
        Regex("[\\u0400-\\u052F]"),
        Regex("[ñÑ¿¡]"),
        Regex("[ãõÃÕ]"),
        Regex("[ğĞışİı]"),
        Regex("[ąćęłńśźżĄĆĘŁŃŚŹŻ]"),
        Regex("[đĐơƠưƯăĂ]"),
    )


    /**
     * Reconcile a transcription provider's language hint with the recognized transcript.
     *
     * Some APIs echo a requested language (for example, "zh") even when the recognized words
     * are English or another language. Strong script detection wins. If the provider claims
     * Chinese but the content is not Chinese-dominant, use "auto" so translation is not skipped.
     */
    fun resolveTranscriptionLanguage(providerLanguage: String?, transcript: String): String {
        val contentLanguage = detect(transcript)
        if (contentLanguage != "auto") return contentLanguage

        val supplied = normalizeProviderLanguage(providerLanguage)
        return if (supplied.startsWith("zh") || supplied == "cmn" || supplied.startsWith("yue")) {
            "auto"
        } else {
            supplied
        }
    }

    private fun normalizeProviderLanguage(value: String?): String {
        val normalized = value.orEmpty().trim().replace('_', '-').lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty() || normalized in setOf("auto", "und", "unknown", "undefined")) return "auto"
        return when (normalized) {
            "chinese", "mandarin", "simplified chinese", "traditional chinese", "cmn", "zho", "chi" -> "zh"
            "cantonese", "yue" -> "yue"
            "english", "eng" -> "en"
            "japanese", "jpn" -> "ja"
            "korean", "kor" -> "ko"
            "spanish", "spa" -> "es"
            "french", "fra", "fre" -> "fr"
            "german", "deu", "ger" -> "de"
            "portuguese", "por" -> "pt"
            "russian", "rus" -> "ru"
            "arabic", "ara" -> "ar"
            "hindi", "hin" -> "hi"
            "thai", "tha" -> "th"
            "vietnamese", "vie" -> "vi"
            "indonesian", "ind" -> "id"
            "dutch", "nld", "dut" -> "nl"
            "polish", "pol" -> "pl"
            "turkish", "tur" -> "tr"
            "swedish", "swe" -> "sv"
            "danish", "dan" -> "da"
            "finnish", "fin" -> "fi"
            "norwegian", "nor" -> "no"
            "greek", "ell", "gre" -> "el"
            "hebrew", "heb" -> "he"
            "hungarian", "hun" -> "hu"
            "czech", "ces", "cze" -> "cs"
            "romanian", "ron", "rum" -> "ro"
            "ukrainian", "ukr" -> "uk"
            "bengali", "ben" -> "bn"
            "malay", "msa", "may" -> "ms"
            else -> normalized.substringBefore('-').takeIf { it.length == 2 || it == "yue" } ?: "auto"
        }
    }

    /**
     * Detect from the first bounded slice of cues instead of joining an entire media transcript.
     * Sampling more short cues reduces the chance that names or "OK" captions dominate the hint.
     */
    fun detectFromCues(cues: Iterable<String>, maxCues: Int = 120): String {
        if (maxCues <= 0) return "auto"
        val sample = StringBuilder()
        var cueCount = 0
        for (cue in cues) {
            if (cueCount >= maxCues || sample.length >= 12_000) break
            cueCount++
            if (cue.isBlank()) continue
            if (sample.isNotEmpty()) sample.append(' ')
            val remaining = 12_000 - sample.length
            if (remaining <= 0) break
            sample.append(cue, 0, minOf(cue.length, remaining))
        }
        return detect(sample.toString())
    }

    fun detect(text: String): String {
        val sample = text.take(12_000)
        if (sample.isBlank()) return "auto"

        fun has(index: Int): Boolean = scriptPatterns[index].containsMatchIn(sample)

        // A single Chinese name/word inside otherwise foreign-language captions must not make
        // the whole track look Chinese. Require Han ideographs to dominate the alphabetic text.
        val hanCount = sample.count { it in '\u4E00'..'\u9FFF' }
        val otherLetterCount = sample.count { it.isLetter() && it !in '\u4E00'..'\u9FFF' }
        val totalLetters = (hanCount + otherLetterCount).coerceAtLeast(1)
        val hanDominates = hanCount > 0 && hanCount.toDouble() / totalLetters >= 0.65

        return when {
            // Scripts with strong, mostly unambiguous Unicode ranges are checked first.
            has(0) -> "ja"
            has(1) -> "ko"
            has(2) -> "th"
            has(3) -> "ar"
            has(4) -> "hi"
            has(5) -> "he"
            has(6) -> "el"
            has(7) -> "uk"
            has(8) -> "be"
            has(9) -> "ru"
            // Latin-script cases where distinctive letters make a useful best-effort guess.
            has(10) -> "es"
            has(11) -> "pt"
            has(12) -> "tr"
            has(13) -> "pl"
            has(14) -> "vi"
            // Detect Chinese only when Han characters dominate; short foreign-language
            // captions with an embedded Chinese name should still be passed to auto-detection.
            hanDominates -> "zh"
            else -> "auto"
        }
    }
}
