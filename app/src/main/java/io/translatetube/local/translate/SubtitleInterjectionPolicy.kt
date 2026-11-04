package io.translatetube.local.translate

import java.util.Locale

/**
 * 英文语气词/应答词/拟声词（"Mm-hmm"、"Hmm"、"Uh-huh"、"Oh"、"Aaaah"、"La la la"、"Ha-ha"）本身没有可翻译的内容，
 * 机器翻译和大模型经常原样返回、只返回标点，或者干脆不返回，导致这一句没有可朗读的中文译文。
 *
 * 这里只在译文失败（空/回显原文/没有汉字/只有标点）时，用固定的中文语气词补足该行译文，让这一句也有中文字幕和配音。
 * 模型已经给出有效中文译文时不会被覆盖。
 *
 * 判定分两层，避免"词表里没有的拟声词"一直过不了校验：
 * 1. 固定词表（[lexicon]），含常见写法；
 * 2. 通用规则（[classify]）：拉长写法（Aaaah / Ooooh）、叠音笑声（ha-ha / hehehe / mwahaha）、
 *    哼唱音节（la la la / na na na / da da da）、没有元音的拟声词（Shh / Brr / Pfft / Tsk / Hmph）。
 *    通用规则不会碰正常单词：没有元音时排除 Mr / Dr / TV 这类缩写，拉长写法要求同一字母连续 3 个以上。
 */
internal object SubtitleInterjectionPolicy {
    private val tokenSeparator = Regex("""[\s,，、]+""")
    private val hyphenSeparator = Regex("""[-–—]+""")
    private val laughPattern = Regex("""^(?:ha|he|hee|heh|hah|hoo|ho){2,}$""")
    private val evilLaughPattern = Regex("""^(?:mwa|mua|bwa|muh)(?:ha|hah|hee|ho)+$""")
    private val teeHeePattern = Regex("""^(?:tee|te)?(?:hee|he)+$""")
    private val repeatedRun = Regex("""(.)\1{2,}""")
    private val anyRepeat = Regex("""(.)\1+""")
    private val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val stretchVowelSet = setOf('a', 'e', 'i', 'o', 'u', 'h')
    private val singSyllables = setOf("la", "na", "lala", "nana", "tra", "lalala")
    private val beatSyllables = setOf("da", "dum", "dun", "doo", "dee", "ba", "bum", "bam", "dah", "dadum")

    /** 没有元音但确实是缩写/称谓的词，不能当拟声词处理。 */
    private val vowelessAbbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "vs", "tv", "cd", "dvd", "pc", "ps", "nd", "rd", "th",
        "pm", "am", "cv", "dj", "mc", "pls", "plz", "thx", "btw", "fyi", "lol", "omg",
        "jr", "sr", "sgt", "lt", "cpl", "pvt", "cmdr", "mgr", "dept", "ave", "blvd", "hwy",
        "brb", "imo", "tbh", "wtf", "ftw", "gl", "hf", "wtf", "nsfw", "html", "css", "sql",
    )

    private val vowelessSounds: Map<String, String> = mapOf(
        "shh" to "嘘", "sh" to "嘘", "ssh" to "嘘", "psst" to "嘘", "pst" to "嘘",
        "tsk" to "啧", "tch" to "啧", "brr" to "嘶", "brrr" to "嘶", "grr" to "呜", "grrr" to "呜",
        "pfft" to "噗", "pft" to "噗", "pff" to "噗", "hmph" to "哼", "hmpf" to "哼", "zzz" to "呼",
        "mm" to "嗯", "mmm" to "嗯", "hm" to "嗯", "hmm" to "嗯", "mhm" to "嗯", "hmmm" to "嗯",
        "ng" to "嗯", "nm" to "嗯", "tsktsk" to "啧啧",
    )

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
        "eek" to "呀", "yikes" to "天哪", "argh" to "啊", "aargh" to "啊", "arrgh" to "啊", "bah" to "呸",
        "duh" to "呃", "hooray" to "耶", "hurray" to "耶", "woohoo" to "耶", "whoopee" to "耶",
        "gosh" to "天哪", "gee" to "哎呀", "jeez" to "天哪", "geez" to "天哪", "ahem" to "咳咳",
        "hah" to "哈", "aha" to "啊哈", "ahah" to "啊哈", "oho" to "哦吼", "ooo" to "哦", "hurrah" to "耶",
        "blah" to "啦啦", "yo" to "哟", "ay" to "哎", "aye" to "是", "hi" to "嗨", "hello" to "你好",
        "bravo" to "太棒了", "encore" to "再来一个",
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
        if (tokens.isEmpty() || tokens.size > 6) return null
        // 叠词哼唱："La la la" / "Na na na" / "Da da dum"：整句按音节数给出对应个数的拟声字。
        singingFor(tokens)?.let { return it + tailPunctuation(sourceText) }
        if (tokens.size > 4) return null

        val mapped = tokens.map { token ->
            val letters = token.filter { it.isLetter() }.lowercase(Locale.ROOT)
            if (letters.isEmpty() || letters.any { it.code > 127 }) return null
            lookup(letters) ?: classify(token, letters) ?: return null
        }
        return mapped.joinToString("，") + tailPunctuation(sourceText)
    }

    private fun tailPunctuation(sourceText: String): String =
        when (sourceText.trim().lastOrNull { !it.isWhitespace() && it != '"' && it != '\'' }) {
            '?' -> "？"
            '!' -> "！"
            else -> ""
        }

    private fun singingFor(tokens: List<String>): String? {
        if (tokens.size < 2) return null
        val syllables = tokens.map { token ->
            val letters = token.filter { it.isLetter() || it == '-' }.lowercase(Locale.ROOT)
            letters.split(hyphenSeparator).filter { it.isNotEmpty() }
        }.flatten()
        if (syllables.size < 2 || syllables.size > 8) return null
        if (syllables.all { it in singSyllables }) return "啦".repeat(syllables.size.coerceAtMost(4))
        if (syllables.all { it in singSyllables || it in beatSyllables }) {
            return syllables.joinToString("") { if (it in singSyllables) "啦" else "哒" }.take(4)
        }
        return null
    }

    private fun lookup(letters: String): String? {
        lexicon[letters]?.let { return it }
        // "Hmmmm"、"Ohhhh" 这类拉长写法：连续 3 个以上相同字母收成 2 个后再查。
        val collapsed = repeatedRun.replace(letters) { it.groupValues[1].repeat(2) }
        lexicon[collapsed]?.let { return it }
        lexicon[collapsed.replace(anyRepeat, "$1")]?.let { return it }
        if (laughPattern.matches(letters)) return "哈哈"
        return null
    }

    /** 词表之外的拟声词：只在形态明确（拉长/叠音/无元音）时判定，普通单词一律返回 null。 */
    private fun classify(rawToken: String, letters: String): String? {
        if (letters.length > 14) return null
        if (evilLaughPattern.matches(letters)) return "哈哈哈"
        if (teeHeePattern.matches(letters) && letters.length >= 4) return "嘻嘻"

        val stretched = repeatedRun.containsMatchIn(letters)
        val collapsedOnce = letters.replace(anyRepeat, "$1")
        // 没有元音的拟声词：Shh / Brr / Pfft / Tsk / Hmph；排除 Mr / Dr / TV 这类缩写。
        if (letters.none { it in vowels }) {
            if (letters in vowelessAbbreviations) return null
            // 全大写的无元音词（TV / CD / NSFW / HTML）是缩写，不是拟声词。
            val rawLetters = rawToken.filter { it.isLetter() }
            if (rawLetters.length >= 2 && rawLetters.all { it.isUpperCase() }) return null
            vowelessSounds[letters]?.let { return it }
            vowelessSounds[collapsedOnce]?.let { return it }
            if (letters.length in 2..6) return "嗯"
            return null
        }
        // 元音拉长："Aaaaah" / "Ooooh" / "Eeeek" / "Uhhhh"：同一字母连续 3 个以上，且全部由 a e i o u h 组成。
        if (stretched && letters.all { it in stretchVowelSet }) {
            return when (collapsedOnce.firstOrNull { it != 'h' }) {
                'a' -> "啊"
                'o' -> "哦"
                'e' -> "诶"
                'i' -> "咦"
                'u' -> "呜"
                else -> null
            }
        }
        return null
    }
}
