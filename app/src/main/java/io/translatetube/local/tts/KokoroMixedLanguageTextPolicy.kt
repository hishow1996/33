package io.translatetube.local.tts

import java.util.Locale

/**
 * Prepares Chinese subtitles for the multilingual Kokoro model without translating or deleting
 * their English content. The full cue is still synthesized in one call and with one speaker ID.
 */
internal object KokoroMixedLanguageTextPolicy {
    /**
     * Bump this whenever text preparation changes so cached PCM generated from older speech
     * normalization rules is not reused.
     */
    const val CACHE_VERSION = "zh-en-v3"

    internal data class PreparedText(
        val text: String,
        val englishWordCount: Int,
        val expandedInitialismCount: Int,
        val expandedProperNameCount: Int,
    )

    private const val CJK =
        "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"

    // These initialisms are expanded to unambiguous English letter-name words.
    // Keep this list deliberately explicit: words such as RAM, ROM, FAQ and SQL have
    // competing word/letter readings and must not be guessed or expanded automatically.
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
    )

    private val initialismPattern = Regex(
        """(?<![A-Za-z0-9])(?:${initialismSpellings.keys.sortedByDescending { it.length }.joinToString("|")})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    // These branded names are expanded into familiar English word units for reliable pronunciation.
    // They are changed only in the private speech payload; visible subtitles remain untouched.
    private val properNameAliases = listOf(
        Regex("""(?<![A-Za-z0-9])OpenAI(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Open ay eye",
        Regex("""(?<![A-Za-z0-9])ChatGPT(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Chat gee pee tee",
        Regex("""(?<![A-Za-z0-9])YouTube(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "You Tube",
        Regex("""(?<![A-Za-z0-9])GitHub(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to "Git Hub",
    )

    private val latinWordPattern = Regex("""[A-Za-z]+(?:['’][A-Za-z]+)*""")
    private val cjkBeforeLatin = Regex("""([$CJK])([A-Za-z])""")
    private val latinBeforeCjk = Regex("""([A-Za-z])([$CJK])""")
    private val punctuationBeforeLatin = Regex("""([，、；：！？])([A-Za-z])""")

    fun prepare(text: String): PreparedText {
        val normalizedWhitespace = text.replace(Regex("""\s+"""), " ").trim()
        if (normalizedWhitespace.isEmpty()) {
            return PreparedText(normalizedWhitespace, 0, 0, 0)
        }

        val englishWordCount = latinWordPattern.findAll(normalizedWhitespace).count()
        var properNameCount = 0
        var prepared = normalizedWhitespace
        for ((pattern, spokenAlias) in properNameAliases) {
            prepared = pattern.replace(prepared) {
                properNameCount++
                spokenAlias
            }
        }

        var initialismCount = 0
        prepared = initialismPattern.replace(prepared) { match ->
            val spelling = initialismSpellings[match.value.uppercase(Locale.ROOT)]
            if (spelling == null) {
                match.value
            } else {
                initialismCount++
                spelling
            }
        }

        // Give the multilingual frontend clear language boundaries at CJK/Latin transitions.
        // Do not split the cue into separate languages: Kokoro keeps one voice and one prosody run.
        prepared = cjkBeforeLatin.replace(prepared, "\$1 \$2")
        prepared = punctuationBeforeLatin.replace(prepared, "\$1 \$2")
        prepared = latinBeforeCjk.replace(prepared, "\$1 \$2")
        prepared = prepared.replace(Regex("""\s+"""), " ").trim()

        // Letter spelling and added boundaries must never push a valid subtitle over the existing
        // per-cue limit. In that rare case, retain the original normalized text unchanged.
        val preparedCodePoints = prepared.codePointCount(0, prepared.length)
        val originalCodePoints = normalizedWhitespace.codePointCount(0, normalizedWhitespace.length)
        if (preparedCodePoints > TtsTextPolicy.MAX_CODE_POINTS &&
            originalCodePoints <= TtsTextPolicy.MAX_CODE_POINTS
        ) {
            return PreparedText(normalizedWhitespace, englishWordCount, 0, 0)
        }

        return PreparedText(prepared, englishWordCount, initialismCount, properNameCount)
    }
}
