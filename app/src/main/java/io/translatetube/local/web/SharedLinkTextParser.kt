package io.translatetube.local.web

/**
 * Extracts a first web URL from share-card text such as:
 * "【Video title】 https://b23.tv/abc".
 *
 * Kept pure so pasted input and Android ACTION_SEND share intents use the same
 * normalization path. It deliberately recognizes only HTTP(S), not app-only schemes.
 */
internal object SharedLinkTextParser {
    private val webUrlPattern = Regex("""(?i)https?://[^\s<>"'`]+""")
    private val trailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?',
        '，', '。', '；', '：', '！', '？', '、',
        ')', ']', '}', '）', '】', '〕', '〉', '》', '」', '』', '»',
    )

    fun extractFirstHttpUrl(text: String): String? {
        if (text.isBlank()) return null
        val match = webUrlPattern.find(text) ?: return null
        var candidate = match.value
        while (candidate.isNotEmpty() && candidate.last() in trailingPunctuation) {
            candidate = candidate.dropLast(1)
        }
        if (candidate.isBlank()) return null
        return candidate.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }
}
