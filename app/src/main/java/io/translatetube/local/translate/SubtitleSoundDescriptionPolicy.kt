package io.translatetube.local.translate

/**
 * Recognizes bracketed captions describing non-verbal audio.
 *
 * These rows are translatable subtitle content and remain visible on the timeline, but they
 * must not be sent to TTS as spoken dialogue. Recognition is limited to known sound/event labels
 * inside one pair of brackets so ordinary bracketed text remains speakable.
 */
internal object SubtitleSoundDescriptionPolicy {
    private val bracketedBody = Regex("""^\\s*(?:\\[|【|（|\\()\\s*(.+?)\\s*(?:\\]|】|）|\\))\\s*[.!?。！？,，;；]*\\s*$""")
    private val englishLabels = setOf(
        "music", "background music", "soft music", "instrumental music", "upbeat music",
        "dramatic music", "sad music", "happy music", "theme music", "singing", "song",
        "applause", "clapping", "laughter", "laughing", "sigh", "sighs", "sighing",
        "gasp", "gasping", "crying", "sobbing", "cheering", "crowd cheering",
        "audience cheering", "footsteps", "door opens", "door closes", "knocking",
        "phone ringing", "birds chirping", "wind", "rain", "thunder", "cough",
        "coughing", "snoring", "breathing", "groaning", "screaming", "shouting",
        "whispering", "typing", "keyboard typing", "car engine", "car horn", "siren",
        "sirens", "alarm", "beeping", "static", "silence", "sound effect", "sound effects",
        "ambient noise", "background noise", "cheers", "gasps", "laughs", "sobs",
        "music fades", "music fades out", "music fades in", "music continues",
        "music starts", "music stops", "music plays", "music playing",
    )
    private val englishPatterns = listOf(
        Regex("""^(?:(?:background|soft|instrumental|upbeat|dramatic|sad|happy|theme|gentle|quiet|loud)\\s+)?music(?:\\s+(?:plays?|playing|continues|fades?(?:\\s+(?:in|out))?|starts?|stops?|begins?|ends?))?$"""),
        Regex("""^(?:crowd|audience)\\s+(?:cheering|cheers|applauding)$"""),
        Regex("""^(?:door|window)\\s+(?:opens?|closes?)$"""),
        Regex("""^(?:phone|telephone)\\s+ringing$"""),
        Regex("""^(?:birds?|dogs?|cats?)\\s+(?:chirping|singing|barking|meowing)$"""),
        Regex("""^(?:heavy\\s+)?(?:rain|wind|thunder|breathing|crying|laughter|applause)(?:\\s+(?:continues|fades|stops))?$"""),
    )
    private val chineseLabels = setOf(
        "音乐", "背景音乐", "轻音乐", "轻柔音乐", "舒缓音乐", "纯音乐", "音乐响起",
        "音乐继续", "音乐渐弱", "音乐渐强", "音乐停止", "音乐结束", "歌声", "唱歌",
        "掌声", "笑声", "大笑声", "轻笑声", "叹气声", "喘息声", "惊呼声",
        "哭声", "抽泣声", "欢呼声", "观众欢呼声", "脚步声", "敲门声",
        "开门声", "关门声", "电话铃声", "鸟鸣声", "风声", "雨声", "雷声",
        "咳嗽声", "呼吸声", "打鼾声", "呻吟声", "尖叫声", "喊叫声",
        "低语声", "耳语声", "键盘声", "打字声", "汽车引擎声", "汽车鸣笛声",
        "警笛声", "警报声", "蜂鸣声", "静电杂音", "背景噪音", "环境音",
        "音效", "静音", "沉默",
    )
    private val chinesePatterns = listOf(
        Regex("""^(?:背景|轻柔|舒缓|轻|纯)?音乐(?:响起|继续|渐弱|渐强|停止|结束|播放)?$"""),
        Regex("""^(?:观众)?(?:掌声|笑声|欢呼声|哭声|抽泣声)$"""),
    )

    fun isSoundDescription(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val body = bracketedBody.matchEntire(text.trim())?.groupValues?.getOrNull(1)
            ?.trim()
            ?.replace(Regex("""\\s+"""), " ")
            ?: return false
        if (body.isEmpty()) return false
        val normalized = body.lowercase(java.util.Locale.ROOT)
            .trimEnd('.', '。', '!', '！', '?', '？', ',', '，', ';', '；')
            .trim()
        return normalized in englishLabels ||
            normalized in chineseLabels ||
            englishPatterns.any { it.matches(normalized) } ||
            chinesePatterns.any { it.matches(body) }
    }
}
