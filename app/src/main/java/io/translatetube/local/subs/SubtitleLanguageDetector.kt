package io.translatetube.local.subs

/**
 * Conservative script-based language hint for imported subtitle files.
 *
 * It returns "auto" when Latin-script text cannot be distinguished safely, and also when
 * Han-only text cannot distinguish Chinese from Japanese written without kana. The playback
 * pipeline then uses on-device language identification or an explicit source-language selection.
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
        Regex("[\\u0980-\\u09FF]"),
        Regex("[\\u1780-\\u17FF]"),
        Regex("[\\u0E80-\\u0EFF]"),
        Regex("[\\u1000-\\u109F\\uA9E0-\\uA9FF\\uAA60-\\uAA7F]"),
        Regex("[\\u0B80-\\u0BFF]"), // Tamil
        Regex("[\\u0C00-\\u0C7F]"), // Telugu
        Regex("[\\u0C80-\\u0CFF]"), // Kannada
        Regex("[\\u0D00-\\u0D7F]"), // Malayalam
        Regex("[\\u0D80-\\u0DFF]"), // Sinhala
        Regex("[\\u10A0-\\u10FF\\u1C90-\\u1CBF\\u2D00-\\u2D2F]"), // Georgian
        Regex("[\\u0530-\\u058F]"), // Armenian
        Regex("[\\u1200-\\u137F\\u1380-\\u139F\\u2D80-\\u2DDF]"), // Ethiopic
    )


    /**
     * Reconcile a transcription provider's language hint with the recognized transcript.
     *
     * Some APIs echo a requested language (for example, "zh") even when the recognized words
     * are English or another language. Strong script detection wins. If the provider claims
     * Chinese but the content is not Chinese-dominant, use "auto" so translation is not skipped.
     */
    fun resolveTranscriptionLanguage(providerLanguage: String?, transcript: String): String {
        val sample = transcript.take(12_000)
        return resolveLanguageUsingContent(providerLanguage, detect(sample), sample)
    }

    /**
     * Resolve a provider hint directly from subtitle cues, avoiding a large joinToString allocation
     * when the transcript contains thousands of timed rows.
     */
    fun resolveTranscriptionLanguageFromCues(
        providerLanguage: String?,
        cues: Iterable<String>,
        maxCues: Int = 120,
    ): String {
        // With no sampled cue text, neither the content nor the provider hint is reliable enough
        // to choose a source language; keep auto-detection enabled.
        if (maxCues <= 0) return "auto"
        val sample = sampleFromCues(cues, maxCues)
        return resolveLanguageUsingContent(providerLanguage, detect(sample), sample)
    }

    private fun resolveLanguageUsingContent(
        providerLanguage: String?,
        contentLanguage: String,
        sampleText: String,
    ): String {
        val supplied = normalizeProviderLanguage(providerLanguage)
        // Arabic-derived scripts are shared by Arabic, Persian and Urdu. Honor an explicit
        // provider language for these scripts instead of collapsing every transcript to "ar".
        if (contentLanguage == "ar" && supplied in setOf("fa", "ur")) return supplied
        // Ethiopic script is shared by Amharic and Tigrinya; retain a reliable provider hint.
        if (contentLanguage == "am" && supplied in setOf("am", "ti")) return supplied
        if (contentLanguage != "auto") return contentLanguage
        // A Han-only sample can be Chinese or Japanese written without kana. Do not let a provider
        // hint force either result; the on-device language identifier gets the next chance.
        if (isHanDominantAmbiguous(sampleText)) return "auto"
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
            "khmer", "khm" -> "km"
            "lao", "lao language" -> "lo"
            "burmese", "myanmar", "mya" -> "my"
            "persian", "farsi", "fas", "per" -> "fa"
            "urdu", "urd" -> "ur"
            "amharic", "amh" -> "am"
            "tigrinya", "tir" -> "ti"
            "tamil", "tam" -> "ta"
            "telugu", "tel" -> "te"
            "kannada", "kan" -> "kn"
            "malayalam", "mal" -> "ml"
            "sinhala", "sin" -> "si"
            "georgian", "geo" -> "ka"
            "armenian", "hye", "arm" -> "hy"
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
        return detect(sampleFromCues(cues, maxCues))
    }

    private fun sampleFromCues(cues: Iterable<String>, maxCues: Int): String {
        val sample = StringBuilder()
        var cueCount = 0
        for (cue in cues) {
            if (sample.length >= 12_000) break
            if (cue.isBlank()) continue
            if (cueCount >= maxCues) break
            cueCount++
            if (sample.isNotEmpty()) sample.append(' ')
            val remaining = 12_000 - sample.length
            if (remaining <= 0) break
            sample.append(cue, 0, minOf(cue.length, remaining))
        }
        return sample.toString()
    }

    /** True when Han characters dominate but no kana/Hangul resolves Chinese vs Japanese. */
    internal fun isHanDominantAmbiguous(text: String): Boolean {
        val sample = text.take(12_000)
        if (sample.isBlank()) return false
        val hasKanaOrHangul = sample.any { char ->
            char in '\u3040'..'\u30FF' ||
                char in '\u31F0'..'\u31FF' ||
                char in '\uFF66'..'\uFF9D' ||
                char in '\u1100'..'\u11FF' ||
                char in '\u3130'..'\u318F' ||
                char in '\uAC00'..'\uD7AF'
        }
        if (hasKanaOrHangul) return false
        val hanCount = sample.count { it in '\u4E00'..'\u9FFF' }
        val otherLetterCount = sample.count { it.isLetter() && it !in '\u4E00'..'\u9FFF' }
        val totalLetters = (hanCount + otherLetterCount).coerceAtLeast(1)
        return hanCount > 0 && hanCount.toDouble() / totalLetters >= 0.65
    }

    internal fun isHanDominantAmbiguousFromCues(
        cues: Iterable<String>,
        maxCues: Int = 120,
    ): Boolean = maxCues > 0 && isHanDominantAmbiguous(sampleFromCues(cues, maxCues))

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
            has(15) -> "bn"
            has(16) -> "km"
            has(17) -> "lo"
            has(18) -> "my"
            has(19) -> "ta"
            has(20) -> "te"
            has(21) -> "kn"
            has(22) -> "ml"
            has(23) -> "si"
            has(24) -> "ka"
            has(25) -> "hy"
            has(26) -> "am"
            // Han-only text is not enough to distinguish Chinese from Japanese written without
            // kana. Keep it ambiguous so ML Kit or an explicit source-language selection can decide.
            hanDominates -> "auto"
            else -> "auto"
        }
    }
}
