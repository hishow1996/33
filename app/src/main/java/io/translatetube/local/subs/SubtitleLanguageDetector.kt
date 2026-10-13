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
    fun detect(text: String): String {
        val sample = text.take(12_000)
        if (sample.isBlank()) return "auto"

        fun has(pattern: String): Boolean = Regex(pattern).containsMatchIn(sample)

        return when {
            // Scripts with strong, mostly unambiguous Unicode ranges are checked first.
            has("[\\u3040-\\u30FF\\u31F0-\\u31FF]") -> "ja"
            has("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]") -> "ko"
            has("[\\u0E00-\\u0E7F]") -> "th"
            has("[\\u0600-\\u06FF\\u0750-\\u077F]") -> "ar"
            has("[\\u0900-\\u097F]") -> "hi"
            has("[\\u0590-\\u05FF]") -> "he"
            has("[\\u0370-\\u03FF]") -> "el"
            has("[\\u0406\\u0456\\u0404\\u0454\\u0490\\u0491\\u0407\\u0457]") -> "uk"
            has("[\\u040E\\u045E]") -> "be"
            has("[\\u0400-\\u052F]") -> "ru"
            // Latin-script cases where distinctive letters make a useful best-effort guess.
            has("[ñÑ¿¡]") -> "es"
            has("[ãõÃÕ]") -> "pt"
            has("[ğĞışİı]") -> "tr"
            has("[ąćęłńśźżĄĆĘŁŃŚŹŻ]") -> "pl"
            has("[đĐơƠưƯăĂ]") -> "vi"
            // Han ideographs without Japanese kana are treated as Chinese. If the source only
            // contains ASCII/Latin letters, leave detection to the selected translation engine.
            has("[\\u4E00-\\u9FFF]") -> "zh"
            else -> "auto"
        }
    }
}
