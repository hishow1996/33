package io.translatetube.local.tts

import java.util.Locale

/**
 * Prepares Chinese subtitles for the multilingual Kokoro model without translating or deleting
 * their English content. The full cue is still synthesized in one call and with one speaker ID.
 */
internal object KokoroMixedLanguageTextPolicy {
    /** Bump this whenever speech normalization changes so old PCM is not reused. */
    const val CACHE_VERSION = "zh-en-v4"

    internal data class PreparedText(
        val text: String,
        val englishWordCount: Int,
        val expandedInitialismCount: Int,
        val expandedProperNameCount: Int,
        val expandedModelIdentifierCount: Int,
    )

    private const val CJK = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"

    private val initialismSpellings = mapOf(
        "AI" to "ay eye",
        "API" to "ay pee eye",
        "CPU" to "see pee you",
        "GPU" to "gee pee you",
        "URL" to "you are ell",
        "GPT" to "gee pee tee",
        "HTML" to "aitch tee em ell",
        "HTTP" to "aitch tee tee pee",
        "HTTPS" to "aitch tee tee pee ess",
        "USB" to "you ess bee",
        "PDF" to "pee dee eff",
        "UI" to "you eye",
        "UX" to "you ex",
        "SDK" to "ess dee kay",
        "VPN" to "vee pee en",
        "DNS" to "dee en ess",
        "LLM" to "ell ell em",
        "XML" to "ex em ell",
        "SSH" to "ess ess aitch",
        "ASR" to "ay ess ar",
        "TTS" to "tee tee ess",
        "IDE" to "eye dee ee",
        "RTX" to "ar tee ex",
    )

    private val initialismPattern = Regex(
        """(?<![A-Za-z0-9])(?:${initialismSpellings.keys.sortedByDescending { it.length }.joinToString("|")})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    // Do not automatically expand ambiguous forms such as RAM, ROM, FAQ, RAG, SQL or JSON.
    // Their common spoken forms differ by context; the English lexicon/frontend handles them.
    private val properNameAliases = listOf(
        Regex("""(?<![A-Za-z0-9])OpenAI(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Open ay eye",
        Regex("""(?<![A-Za-z0-9])ChatGPT(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Chat gee pee tee",
        Regex("""(?<![A-Za-z0-9])YouTube(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "You Tube",
        Regex("""(?<![A-Za-z0-9])GitHub(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Git Hub",
        Regex("""(?<![A-Za-z0-9])GitLab(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Git Lab",
        Regex("""(?<![A-Za-z0-9])TikTok(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Tick Tock",
        Regex("""(?<![A-Za-z0-9])JavaScript(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Java Script",
        Regex("""(?<![A-Za-z0-9])SenseVoice(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Sense Voice",
    )

    private val gptVersionPattern = Regex(
        """(?<![A-Za-z0-9])GPT[- ]?([0-9]{1,2})(?:\.([0-9]))?([oO])?(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val pythonVersionPattern = Regex(
        """(?<![A-Za-z0-9])Python\s+([0-9]{1,2})\.([0-9]{1,2})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val androidVersionPattern = Regex(
        """(?<![A-Za-z0-9])Android\s+([0-9]{1,2})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val rtxModelPattern = Regex(
        """(?<![A-Za-z0-9])RTX\s*([0-9]{4})(?:\s*(Ti))?(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val wifiPattern = Regex(
        """(?<![A-Za-z0-9])Wi[- ]?Fi(?:\s+([0-9]))?(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val usbPortPattern = Regex(
        """(?<![A-Za-z0-9])USB[- ]([AC])(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val videoCodecPattern = Regex(
        """(?<![A-Za-z0-9])H[. -]?(264|265)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val ipVersionPattern = Regex(
        """(?<![A-Za-z0-9])IPv([46])(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    private val latinWordPattern = Regex("""[A-Za-z]+(?:['’][A-Za-z]+)*""")
    private val cjkBeforeLatin = Regex("""([$CJK])([A-Za-z])""")
    private val latinBeforeCjk = Regex("""([A-Za-z])([$CJK])""")
    private val punctuationBeforeLatin = Regex("""([，、；：！？])([A-Za-z])""")

    private val smallNumberWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val tensWords = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    private fun numberToEnglish(value: Int): String {
        require(value in 0..99)
        if (value < 20) return smallNumberWords[value]
        val tens = tensWords[value / 10]
        val units = value % 10
        return if (units == 0) tens else "$tens ${smallNumberWords[units]}"
    }

    private fun digitSequenceToEnglish(value: String): String =
        value.map { smallNumberWords[it.digitToInt()] }.joinToString(" ")

    private fun prepareModelIdentifiers(
        input: String,
        onExpanded: () -> Unit,
    ): String {
        var result = input

        result = gptVersionPattern.replace(result) { match ->
            onExpanded()
            val major = numberToEnglish(match.groupValues[1].toInt())
            val minor = match.groupValues[2]
            val suffix = match.groupValues[3]
            buildString {
                append("gee pee tee ")
                append(major)
                if (minor.isNotEmpty()) {
                    append(" point ")
                    append(smallNumberWords[minor.toInt()])
                }
                if (suffix.isNotEmpty()) append(" oh")
            }
        }

        result = pythonVersionPattern.replace(result) { match ->
            onExpanded()
            "Python ${numberToEnglish(match.groupValues[1].toInt())} point ${numberToEnglish(match.groupValues[2].toInt())}"
        }

        result = androidVersionPattern.replace(result) { match ->
            onExpanded()
            "Android ${numberToEnglish(match.groupValues[1].toInt())}"
        }

        result = rtxModelPattern.replace(result) { match ->
            onExpanded()
            val digits = match.groupValues[1]
            val ti = match.groupValues[2]
            val spoken = "${numberToEnglish(digits.substring(0, 2).toInt())} ${numberToEnglish(digits.substring(2).toInt())}"
            if (ti.isNotEmpty()) "ar tee ex $spoken tie" else "ar tee ex $spoken"
        }

        result = wifiPattern.replace(result) { match ->
            onExpanded()
            val generation = match.groupValues[1]
            if (generation.isEmpty()) "why fie" else "why fie ${smallNumberWords[generation.toInt()]}"
        }

        result = usbPortPattern.replace(result) { match ->
            onExpanded()
            if (match.groupValues[1].equals("A", ignoreCase = true)) {
                "you ess bee ay"
            } else {
                "you ess bee see"
            }
        }

        result = videoCodecPattern.replace(result) { match ->
            onExpanded()
            "aitch ${digitSequenceToEnglish(match.groupValues[1])}"
        }

        result = ipVersionPattern.replace(result) { match ->
            onExpanded()
            "eye pee vee ${smallNumberWords[match.groupValues[1].toInt()]}"
        }

        return result
    }

    fun prepare(text: String): PreparedText {
        val normalizedWhitespace = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedWhitespace.isEmpty()) {
            return PreparedText(normalizedWhitespace, 0, 0, 0, 0)
        }

        val englishWordCount = latinWordPattern.findAll(normalizedWhitespace).count()
        var modelIdentifierCount = 0
        var prepared = prepareModelIdentifiers(normalizedWhitespace) { modelIdentifierCount++ }

        var properNameCount = 0
        for ((pattern, spokenAlias) in properNameAliases) {
            prepared = pattern.replace(prepared) {
                properNameCount++
                spokenAlias
            }
        }

        var initialismCount = 0
        prepared = initialismPattern.replace(prepared) { match ->
            val spelling = initialismSpellings[match.value.uppercase(Locale.ROOT)]
            if (spelling == null) match.value else {
                initialismCount++
                spelling
            }
        }

        // Explicit group references keep language boundaries at CJK/Latin and punctuation edges.
        // The cue is still synthesized once with one Kokoro voice to preserve sentence prosody.
        prepared = cjkBeforeLatin.replace(prepared, "\$1 \$2")
        prepared = punctuationBeforeLatin.replace(prepared, "\$1 \$2")
        prepared = latinBeforeCjk.replace(prepared, "\$1 \$2")
        prepared = prepared.replace(Regex("""\s+"""), " ").trim()

        // Letter expansion must not make an otherwise valid subtitle exceed the per-cue limit.
        val preparedCodePoints = prepared.codePointCount(0, prepared.length)
        val originalCodePoints = normalizedWhitespace.codePointCount(0, normalizedWhitespace.length)
        if (preparedCodePoints > TtsTextPolicy.MAX_CODE_POINTS &&
            originalCodePoints <= TtsTextPolicy.MAX_CODE_POINTS
        ) {
            return PreparedText(normalizedWhitespace, englishWordCount, 0, 0, 0)
        }

        return PreparedText(
            prepared,
            englishWordCount,
            initialismCount,
            properNameCount,
            modelIdentifierCount,
        )
    }
}
