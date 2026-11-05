package io.translatetube.local.translate

/**
 * Recognizes bracketed captions describing non-verbal audio.
 *
 * These rows are translatable subtitle content and remain visible on the timeline, but they
 * must not be sent to TTS as spoken dialogue. Recognition is limited to known sound/event labels
 * inside one pair of brackets so ordinary bracketed text remains speakable.
 */
internal object SubtitleSoundDescriptionPolicy {
    private val bracketedBody = Regex("""^\s*(?:\[|【|（|\(|\{|「|『|〈|《)\s*(.+?)\s*(?:\]|】|）|\)|\}|」|』|〉|》)\s*[.!?。！？,，;；]*\s*$""")
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
        "inaudible", "inaudible speech", "unintelligible", "unintelligible speech",
        "indistinct", "indistinct speech", "unclear", "unclear speech",
        "muffled speech", "speech is unclear", "speech is inaudible",
        "door slams", "door slamming", "bleep", "beep", "bleeping",
        "engine starts", "engine start", "engine stops", "engine stop",
        "car engine starts", "car engine stops", "glass breaking", "glass shattering",
        "footsteps approaching", "footsteps receding", "water splashing", "waves crashing",
        "door creaking", "dog barking", "cat meowing", "baby crying", "metal clanging",
        "wood cracking", "keys jingling", "fire crackling", "wind blowing",
    )
    private val englishPatterns = listOf(
        Regex("""^(?:(?:background|soft|instrumental|upbeat|dramatic|sad|happy|theme|gentle|quiet|loud)\s+)?music(?:\s+(?:plays?|playing|continues|fades?(?:\s+(?:in|out))?|starts?|stops?|begins?|ends?))?$"""),
        Regex("""^(?:crowd|audience)\s+(?:cheering|cheers|applauding)$"""),
        Regex("""^(?:door|window)\s+(?:opens?|closes?)$"""),
        Regex("""^(?:phone|telephone)\s+ringing$"""),
        Regex("""^(?:birds?|dogs?|cats?)\s+(?:chirping|singing|barking|meowing)$"""),
        Regex("""^(?:heavy\s+)?(?:rain|wind|thunder|breathing|crying|laughter|applause)(?:\s+(?:continues|fades|stops))?$"""),
        Regex("""^speaking(?:\s+in)?\s+(?:english|spanish|french|german|italian|portuguese|russian|japanese|korean|chinese|arabic|hindi|vietnamese|thai|bengali|dutch|swedish|norwegian|danish|finnish|polish|ukrainian|greek|hebrew|persian|turkish|indonesian|malay|cantonese|filipino|tagalog|czech|romanian)$"""),
        Regex("""^(?:an?\s+)?(?:car|engine|motor|machine|glass|window|door|bell|phone|alarm|dog|cat|bird|baby|crowd|water|waves?|wind|rain|thunder|fire|footsteps|keys?|keyboard|metal|wood)\s+(?:starts?(?:\s+up)?|stops?|breaks?|breaking|shatters?|shattering|crashes?|crashing|splashes?|splashing|approaches|approaching|recedes|receding|rings?|ringing|barks?|barking|meows?|meowing|chirps?|chirping|howls?|howling|falls?|falling|opens?|closes?|slams?|slamming|creaks?|creaking|blows?|blowing|flows?|flowing|rushes|rushing|continues|fades?(?:\s+(?:in|out))?)$"""),
        Regex("""^(?:car|truck|motorcycle)\s+engine\s+(?:starts?|stops?)$"""),
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
        "音效", "静音", "沉默", "听不清", "听不清楚", "无法辨认",
        "语音不清", "说话声不清", "消音",
    )
    private val chinesePatterns = listOf(
        Regex("""^(?:背景|轻柔|舒缓|轻|纯)?音乐(?:响起|继续|渐弱|渐强|停止|结束|播放)?$"""),
        Regex("""^(?:观众)?(?:掌声|笑声|欢呼声|哭声|抽泣声)$"""),
    )
    private val speakingLanguageTranslations = mapOf(
        "english" to "英语",
        "spanish" to "西班牙语",
        "french" to "法语",
        "german" to "德语",
        "italian" to "意大利语",
        "portuguese" to "葡萄牙语",
        "russian" to "俄语",
        "japanese" to "日语",
        "korean" to "韩语",
        "chinese" to "中文",
        "arabic" to "阿拉伯语",
        "hindi" to "印地语",
        "vietnamese" to "越南语",
        "thai" to "泰语",
        "bengali" to "孟加拉语",
        "dutch" to "荷兰语",
        "swedish" to "瑞典语",
        "norwegian" to "挪威语",
        "danish" to "丹麦语",
        "finnish" to "芬兰语",
        "polish" to "波兰语",
        "ukrainian" to "乌克兰语",
        "greek" to "希腊语",
        "hebrew" to "希伯来语",
        "persian" to "波斯语",
        "turkish" to "土耳其语",
        "indonesian" to "印度尼西亚语",
        "malay" to "马来语",
        "cantonese" to "粤语",
        "filipino" to "菲律宾语",
        "tagalog" to "他加禄语",
        "czech" to "捷克语",
        "romanian" to "罗马尼亚语",
    )
    private val speakingLanguagePattern = Regex("""^speaking(?:\s+in)?\s+([a-z]+)$""")
    private val knownEnglishTranslations = mapOf(
        "music" to "音乐",
        "background music" to "背景音乐",
        "soft music" to "轻柔音乐",
        "instrumental music" to "纯音乐",
        "upbeat music" to "欢快的音乐",
        "dramatic music" to "戏剧性音乐",
        "sad music" to "悲伤音乐",
        "happy music" to "欢快的音乐",
        "theme music" to "主题音乐",
        "singing" to "歌声",
        "song" to "歌声",
        "applause" to "掌声",
        "clapping" to "掌声",
        "laughter" to "笑声",
        "laughing" to "笑声",
        "sigh" to "叹气声",
        "sighs" to "叹气声",
        "sighing" to "叹气声",
        "gasp" to "惊呼声",
        "gasping" to "惊呼声",
        "crying" to "哭声",
        "sobbing" to "抽泣声",
        "cheering" to "欢呼声",
        "crowd cheering" to "观众欢呼声",
        "audience cheering" to "观众欢呼声",
        "footsteps" to "脚步声",
        "door opens" to "开门声",
        "door closes" to "关门声",
        "door slams" to "关门声",
        "door slamming" to "关门声",
        "bleep" to "哔声",
        "beep" to "哔声",
        "bleeping" to "哔声",
        "knocking" to "敲门声",
        "phone ringing" to "电话铃声",
        "birds chirping" to "鸟鸣声",
        "bird chirping" to "鸟鸣声",
        "wind" to "风声",
        "wind blowing" to "风声",
        "rain" to "雨声",
        "heavy rain" to "大雨声",
        "thunder" to "雷声",
        "cough" to "咳嗽声",
        "coughing" to "咳嗽声",
        "snoring" to "打鼾声",
        "breathing" to "呼吸声",
        "groaning" to "呻吟声",
        "screaming" to "尖叫声",
        "shouting" to "喊叫声",
        "whispering" to "低语声",
        "typing" to "打字声",
        "keyboard typing" to "键盘打字声",
        "car engine" to "汽车引擎声",
        "car horn" to "汽车鸣笛声",
        "siren" to "警笛声",
        "sirens" to "警笛声",
        "alarm" to "警报声",
        "beeping" to "蜂鸣声",
        "static" to "静电杂音",
        "silence" to "静音",
        "sound effect" to "音效",
        "sound effects" to "音效",
        "ambient noise" to "环境音",
        "background noise" to "背景噪音",
        "cheers" to "欢呼声",
        "gasps" to "惊呼声",
        "laughs" to "笑声",
        "sobs" to "抽泣声",
        "music fades" to "音乐渐弱",
        "music fades out" to "音乐渐弱",
        "music fades in" to "音乐渐强",
        "music continues" to "音乐继续",
        "music starts" to "音乐响起",
        "music stops" to "音乐停止",
        "music plays" to "音乐播放中",
        "music playing" to "音乐播放中",
        "inaudible" to "听不清",
        "inaudible speech" to "听不清",
        "unintelligible" to "听不清",
        "unintelligible speech" to "听不清",
        "indistinct" to "听不清",
        "indistinct speech" to "说话声不清",
        "unclear" to "听不清",
        "unclear speech" to "说话声不清",
        "muffled speech" to "说话声模糊",
        "speech is unclear" to "说话声不清",
        "speech is inaudible" to "听不清",
        "engine starts" to "发动机启动声",
        "engine start" to "发动机启动声",
        "engine stops" to "发动机停止声",
        "engine stop" to "发动机停止声",
        "car engine starts" to "汽车发动机启动声",
        "car engine stops" to "汽车发动机停止声",
        "glass breaking" to "玻璃破碎声",
        "glass shattering" to "玻璃碎裂声",
        "footsteps approaching" to "脚步声靠近",
        "footsteps receding" to "脚步声远去",
        "water splashing" to "水花飞溅声",
        "waves crashing" to "海浪拍打声",
        "door creaking" to "门吱呀作响",
        "dog barking" to "狗叫声",
        "cat meowing" to "猫叫声",
        "baby crying" to "婴儿哭声",
        "metal clanging" to "金属碰撞声",
        "wood cracking" to "木头开裂声",
        "keys jingling" to "钥匙碰撞声",
        "fire crackling" to "火焰噼啪声",
        "wind blowing" to "风声",
    )
    private val closingBracketAndSuffix = Regex("""(?:\]|】|）|\)|\}|」|』|〉|》)([.!?。！？,，;；]*)$""")

    /**
     * Uses a small deterministic glossary for common sound captions so every translation engine,
     * including non-prompt engines, handles labels such as [music] consistently.
     * Unknown bracketed captions remain with the selected translation engine.
     */
    fun translateKnownSoundDescription(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val match = bracketedBody.matchEntire(trimmed) ?: return null
        val body = match.groupValues[1].trim().replace(Regex("""\s+"""), " ")
        val normalizedBody = body.lowercase(java.util.Locale.ROOT)
        val translated = knownEnglishTranslations[normalizedBody]
            ?: speakingLanguagePattern.matchEntire(normalizedBody)?.groupValues?.getOrNull(1)
                ?.let(speakingLanguageTranslations::get)
                ?.let { "说$it" }
            ?: return null
        val opening = trimmed.firstOrNull { it in "[【（({「『〈《" } ?: return null
        val closing = when (opening) {
            '[' -> ']'
            '【' -> '】'
            '（' -> '）'
            '(' -> ')'
            '{' -> '}'
            '「' -> '」'
            '『' -> '』'
            '〈' -> '〉'
            '《' -> '》'
            else -> return null
        }
        val suffix = closingBracketAndSuffix.find(trimmed)?.groupValues?.get(1).orEmpty()
        return "$opening$translated$closing$suffix"
    }

    fun isSoundDescription(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val body = bracketedBody.matchEntire(text.trim())?.groupValues?.getOrNull(1)
            ?.trim()
            ?.replace(Regex("""\s+"""), " ")
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
