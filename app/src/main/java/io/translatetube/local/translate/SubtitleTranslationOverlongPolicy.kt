package io.translatetube.local.translate

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Detects a translated row that is far longer than its own source row.
 *
 * Subtitle sources are often cut mid-sentence (ASR captions, verb-final languages such as
 * Japanese). A model then tends to pull the meaning of the following cues into the current row,
 * and every later row in the same answer is usually shifted as well. A row's length relative to
 * the video's own translation ratio is a cheap, language-independent sign that alignment was lost.
 */
internal object SubtitleTranslationOverlongPolicy {
    /** A translation needs at least this many letters/digits/ideographs before it can be overlong. */
    const val MIN_TRANSLATION_UNITS = 16

    /** How many times longer than the video's typical ratio a row may be before it is suspect. */
    const val OVERLONG_FACTOR = 1.8

    private const val MIN_SOURCE_UNITS_FOR_BASELINE = 6
    private const val MIN_BASELINE_SAMPLES = 8
    private const val MAX_BASELINE_SAMPLES = 200

    /** Chinese-per-source length for Japanese/Korean/Chinese sources (measured on a ja->zh video: 0.68). */
    private const val DENSE_SCRIPT_DEFAULT_RATIO = 0.7

    /** Rough Chinese-per-letter length for alphabetic sources; only used until the video has enough rows. */
    private const val ALPHABETIC_DEFAULT_RATIO = 0.35

    /** Letters, digits and ideographs; spaces and punctuation do not count. */
    fun units(text: String?): Int = text?.count { Character.isLetterOrDigit(it) } ?: 0

    private fun isDenseScript(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    /** Typical translation/source ratio when the video has too few translated rows to measure. */
    fun defaultRatio(sourceText: String): Double {
        val letters = sourceText.filter { Character.isLetterOrDigit(it) }
        if (letters.isEmpty()) return DENSE_SCRIPT_DEFAULT_RATIO
        val dense = letters.count { isDenseScript(it) }
        return if (dense * 2 >= letters.length) DENSE_SCRIPT_DEFAULT_RATIO else ALPHABETIC_DEFAULT_RATIO
    }

    /**
     * Median translation/source length ratio of rows already translated in this video, or null when
     * there are too few usable rows. The median keeps a handful of swallowed rows from moving it.
     */
    fun baselineRatio(pairs: Sequence<Pair<String, String>>): Double? {
        val ratios = ArrayList<Double>()
        for ((source, translation) in pairs) {
            val sourceUnits = units(source)
            if (sourceUnits < MIN_SOURCE_UNITS_FOR_BASELINE) continue
            val translationUnits = units(translation)
            if (translationUnits == 0) continue
            ratios += translationUnits.toDouble() / sourceUnits
            if (ratios.size >= MAX_BASELINE_SAMPLES) break
        }
        if (ratios.size < MIN_BASELINE_SAMPLES) return null
        ratios.sort()
        val mid = ratios.size / 2
        return if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2.0
    }

    /**
     * How many times longer than expected the translation is, or null when it is not overlong.
     * [baselineRatio] comes from [baselineRatio]; null falls back to a per-script default.
     */
    fun overlongMultiple(sourceText: String?, translation: String?, baselineRatio: Double?): Double? {
        if (sourceText.isNullOrBlank() || translation.isNullOrBlank()) return null
        val translationUnits = units(translation)
        if (translationUnits < MIN_TRANSLATION_UNITS) return null
        val sourceUnits = units(sourceText).coerceAtLeast(1)
        val ratio = baselineRatio?.takeIf { it > 0.0 } ?: defaultRatio(sourceText)
        val multiple = translationUnits / (ratio * sourceUnits)
        return if (multiple > OVERLONG_FACTOR) multiple else null
    }
}

/**
 * Carries a one-request instruction to the subtitle prompt builders without changing the public
 * engine API: a builder reads [current] while it assembles the prompt of the request in flight.
 */
internal object TranslationRetryNoticeHolder {
    const val OVERLONG_RETRY_NOTICE =
        "RETRY NOTICE: In the previous attempt, at least one cue's translation was far longer than " +
            "its source because it also contained the meaning of the following cues, which shifted " +
            "every later row. In this request each numbered cue must contain ONLY the translation of " +
            "its own source words, even when that cue is a short or incomplete fragment (a single " +
            "particle or half a clause). Keep each translation proportionally short, never carry the " +
            "meaning of an earlier or following cue into it, and never leave a cue's own words untranslated. "

    private val current = ThreadLocal<String?>()

    fun current(): String? = current.get()

    suspend fun <T> withNotice(notice: String?, block: suspend () -> T): T =
        if (notice.isNullOrBlank()) block() else withContext(current.asContextElement(notice)) { block() }
}
