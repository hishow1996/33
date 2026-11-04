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
    // 用户常把 "youtu.be/xxxx" 这类不带 https:// 的链接夹在一段话里；只对已知视频站放宽，
    // 避免把普通文字（例如 "a.com/b"）误当链接。前一个字符不能是域名/路径字符，防止匹配到
    // "notyoutube.com/..." 这类别的域名。
    private val schemelessVideoUrlPattern = Regex(
        """(?i)(?<![A-Za-z0-9@./:_-])(?:[a-z0-9-]+\.)?(?:youtube\.com|youtu\.be|bilibili\.com|b23\.tv|bili2233\.cn)/[^\s<>"'`\u4e00-\u9fff\u3000-\u303f\uff01-\uffef]+""",
    )
    private val trailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?',
        '，', '。', '；', '：', '！', '？', '、',
        ')', ']', '}', '）', '】', '〕', '〉', '》', '」', '』', '»',
    )

    fun extractFirstHttpUrl(text: String): String? {
        if (text.isBlank()) return null
        val httpMatch = webUrlPattern.find(text)
        val schemelessMatch = if (httpMatch == null) schemelessVideoUrlPattern.find(text) else null
        val match = httpMatch ?: schemelessMatch ?: return null
        var candidate = match.value
        while (candidate.isNotEmpty() && candidate.last() in trailingPunctuation) {
            candidate = candidate.dropLast(1)
        }
        if (candidate.isBlank()) return null
        if (schemelessMatch != null) {
            // Drop a path that consisted only of punctuation ("youtu.be/。" was trimmed to "youtu.be/").
            if (candidate.substringAfter('/').isBlank()) return null
            candidate = "https://$candidate"
        }
        return candidate.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }
}
