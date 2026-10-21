package io.translatetube.local.web

/**
 * Extracts a first web URL from share-card text such as:
 * "【Video title】 https://b23.tv/abc".
 *
 * Kept pure so pasted input and Android ACTION_SEND share intents use the same
 * normalization path. It deliberately recognizes only HTTP(S), not app-only schemes.
 */
internal object SharedLinkTextParser {
    // CJK 统一表意与全角/中文标点不属于合法 URL 字符；分享文案里的「，更多介绍」「，欢迎观看」
    // 等中文尾巴必须被拒在链接之外，否则提取出的 URL 携带中文导致无法打开。
    private val webUrlPattern = Regex("""(?i)https?://[^\s<>"'`\u4e00-\u9fff\u3000-\u303f\uff01-\uffef]+""")
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
