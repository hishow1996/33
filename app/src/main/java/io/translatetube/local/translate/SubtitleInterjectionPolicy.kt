package io.translatetube.local.translate

import java.util.Locale

/**
 * 英文语气词/应答词（"Mm-hmm"、"Hmm"、"Uh-huh"、"Oh"）本身没有可翻译的内容，机器翻译经常原样返回
 * 或只返回标点，导致这一句三轮重试后仍然失败，只能回退原声。
 *
 * 这里只在译文失败（空/回显原文/没有汉字）时，用固定的中文语气词代替，让这一句也有中文字幕和配音。
 * 模型已经给出有效中文译文时不会被覆盖。
 */
internal object SubtitleInterjectionPolicy {
    private val tokenSeparator = Regex("""[\s,，、]+""")
    private val laughPattern = Regex("""^(?:ha|he|hee|heh)+$""")
    private val repeatedRun = Regex("""(.)\1{2,}""")

    private val lexicon: Map<String, String> = mapOf(
        "mm" to "嗯", "mmm" to "嗯", "hm" to "嗯", "hmm" to "嗯", "mhm" to "嗯", "mmhmm" to "嗯哼", "mhmm" to "嗯哼",
        "uhhuh" to "嗯哼", "uhuh" to "嗯哼", "uhhuhh" to "嗯哼", "huh" to "啊", "eh" to "诶", "ehh" to "诶",
        "uh" to "呃", "um" to "呃", "umm" to "呃", "er" to "呃", "erm" to "呃", "ah" to "啊", "aah" to "啊",
        "oh" to "哦", "ohh" to "哦", "ooh" to "哦", "oo" to "哦", "aw" to "哎", "aww" to "哎", "ow" to "哎哟",
        "ouch" to "哎哟", "ugh" to "唉", "uhoh" to "哎呀", "oops" to "哎呀", "whoops" to "哎呀",
        "wow" to "哇", "whoa" to "哇", "woah" to "哇", "yay" to "耶", "yippee" to "耶", "phew" to "呼",
        "whew" to "呼", "shh" to "嘘", "sh" to "嘘", "psst" to "嘘", "hey" to "嘿", "ha" to "哈",
        "yeah" to "是啊", "yep" to "嗯", "yup" to "嗯", "nope" to "不", "nah" to "不", "ok" to "好", "okay" to "好的",
        "yum" to "好吃", "ew" to "呃", "eww" to "呃", "oof" to "哎哟", "ahh" to "啊",
    )

    /**
     * @return 中文语气词；原文不是纯语气词、源语言不是英文/自动检测、或目标不是中文时返回 null。
     */
    fun chineseFor(sourceText: String?, sourceLanguage: String, targetLanguage: String): String? {
        if (sourceText.isNullOrBlank()) return null
        if (!SourceLanguagePolicy.isChinese(targetLanguage)) return null
        val language = SourceLanguagePolicy.normalizeTag(sourceLanguage)
        val primary = SourceLanguagePolicy.canonicalPrimaryCode(language)
        if (primary.isNotEmpty() && primary != "en" && !SourceLanguagePolicy.isUndetermined(language)) return null

        val tokens = sourceText.trim().split(tokenSeparator).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 4) return null
        val mapped = tokens.map { token ->
            val letters = token.filter { it.isLetter() }.lowercase(Locale.ROOT)
            if (letters.isEmpty() || letters.any { it.code > 127 }) return null
            lookup(letters) ?: return null
        }
        val tail = when (sourceText.trim().lastOrNull { !it.isWhitespace() && it != '"' && it != '\'' }) {
            '?' -> "？"
            '!' -> "！"
            else -> ""
        }
        return mapped.joinToString("，") + tail
    }

    private fun lookup(letters: String): String? {
        lexicon[letters]?.let { return it }
        // "Hmmmm"、"Ohhhh" 这类拉长写法：连续 3 个以上相同字母收成 2 个后再查。
        val collapsed = repeatedRun.replace(letters) { it.groupValues[1].repeat(2) }
        lexicon[collapsed]?.let { return it }
        lexicon[collapsed.replace(Regex("(.)\\1+"), "$1")]?.let { return it }
        if (laughPattern.matches(letters)) return "哈哈"
        return null
    }
}
