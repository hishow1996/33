package io.translatetube.local.translate

/**
 * Accuracy constraints shared by prompt-based translation engines.
 *
 * These instructions target meaning-changing errors (especially negation, modality and numbers),
 * rather than trying to judge semantic correctness with brittle string heuristics.
 */
internal object SubtitleTranslationPromptPolicy {
    const val ENGLISH_FIDELITY_GUIDELINES =
        "Fidelity is mandatory: preserve negation and polarity, questions versus statements, " +
            "commands versus requests, conditionals, tense/aspect, modality (may/might/must/should), " +
            "uncertainty and cause-and-effect. Preserve who performs an action and who receives it, " +
            "direction, location, comparisons and the relationship between each number and its object. " +
            "Never add facts, quantities, dates or conclusions absent from the source. " +
            "Never strengthen, weaken or reverse the speaker's intent. " +
            "Preserve the identity of people, places, organizations, companies, brands, products, software, works and specialist terms. " +
            "Use an established target-language rendering (including an established target-language name) when one exists; otherwise keep the original entity spelling when appropriate. " +
            "Never invent a transliteration or substitute a different person or entity. Proper names may remain embedded in an " +
            "otherwise translated sentence, while ordinary surrounding words must still be translated. A clearly name-shaped " +
            "standalone person, place, brand, organization, product or work name may retain its exact spelling when no established " +
            "target-language name is available. A list of standalone entities may use commas or semicolons and may contain multi-word " +
            "names; do not apply this exception to common words or conversational phrases. A subtitle may be an incomplete " +
            "fragment cut off by a line boundary: translate every supported word naturally, do not invent the missing ending, " +
            "and do not reject or leave the fragment untranslated merely because it is grammatically incomplete. Retain only " +
            "source-grounded entities inside such fragments. Keep each entity's rendering consistent throughout the video. " +
            "Preserve numbers, dates, times, quantities, units, currencies, percentages, " +
            "model names and technical values exactly. Translate idioms, slang, humor and sarcasm by their intended " +
            "meaning and tone rather than word-for-word, without inventing explanations. " +
            "For Chinese targets, use the requested script consistently: Simplified Chinese for zh/zh-CN/zh-Hans/zh-SG, " +
            "and Traditional Chinese only for zh-Hant/zh-TW/zh-HK/zh-MO. Prefer natural target-language punctuation. " +
            "Keep the result concise and natural for spoken subtitles. "

    fun targetLanguageLabel(code: String): String {
        val normalized = SourceLanguagePolicy.normalizeTag(code).lowercase(java.util.Locale.ROOT)
        val traditionalChinese = normalized.startsWith("zh-hant") ||
            normalized in setOf("zh-tw", "zh-hk", "zh-mo")
        return when {
            traditionalChinese -> "Traditional Chinese"
            SourceLanguagePolicy.canonicalPrimaryCode(normalized) == "zh" -> "Simplified Chinese"
            else -> LangMap.display(code)
        }
    }

    const val CHINESE_FIDELITY_GUIDELINES =
        "准确性优先：完整保留肯定与否定、疑问与陈述、命令与请求、条件关系、时态，以及可能、应该、必须、不确定等语气；疑问句不得翻成回答，普通陈述不得擅自改成问题；请求不得强化成命令。不得改变说话者态度，不得把不确定翻成肯定。主体、动作执行者、动作对象、方向、位置、比较关系及数字对应的对象必须准确，不得把谁做了什么或数值属于谁翻反。不得增加原文没有的事实、数量、日期或结论。人名、地名、组织机构、公司、品牌、产品、软件、作品名称及专业术语必须保持实体身份准确；存在通行中文译名时使用通行译名，没有可靠译名时保留原文拼写，不要臆造音译或改成其他人物、地点或实体；专有名词可以保留在中文句子中，周围普通词语仍须翻译；单条字幕只有一个明确的人名、地名、组织、品牌、产品或作品专名且没有通行译名时，可以保留原文；由逗号或分号分隔的专名列表也可以保留，其中允许包含多词名称，但不能因此保留普通词语、日常短语或整句英文；跨行截断的不完整片段仍须按现有词义自然翻译，不得因语法不完整就拒绝或整行原样保留，也不得臆造缺失部分；同一实体在全片保持一致。数字、日期、时间、数量、单位、货币、百分比、型号和专业数值必须准确保留，不得自行换算或增删。习语、俚语、幽默和反讽应按实际含义及语气自然翻译，不逐字硬译，也不要自行补充解释。目标为简体中文时，全片统一使用简体字和自然的中文标点，不要混用繁体字。译文简洁自然，适合口语字幕。"
    
    const val CHINESE_CONTEXT_GUIDELINES =
        "上下文按时间顺序排列，每组原文与已确认译文一一对应。仅用它们统一已出现的人名、称呼、术语和明确指代；同一人物、地名、品牌或技术术语（包括组织机构、品牌、产品、作品名称及公司名称）在当前批次及提供的前文上下文范围内应沿用已确认的译名，不要无理由更换译法；人名可以采用通行中文译名或原文拼写，但不能在不同字幕行之间随意变换。若历史译文彼此冲突，以当前原文和明确上下文为准，不要机械继承错误。当前字幕原文优先，不要为了沿用旧译而改变当前原意。没有充分依据时不要猜测说话者身份、性别、人物关系或指代对象，优先采用自然且不过度具体的表达。字幕可能跨行组成一句话，应结合前后语法理解，但每行只翻译自己的内容，不重复前后行，不遗漏或挪动信息。"

    const val TRADITIONAL_CHINESE_FIDELITY_GUIDELINES =
        "准确性优先：完整保留肯定与否定、疑问与陈述、命令与请求、条件关系、时态，以及可能、应该、必须、不确定等语气；疑问句不得翻成回答，普通陈述不得擅自改成问题；请求不得强化成命令。不得改变说话者态度，不得把不确定翻成肯定。主体、动作执行者、动作对象、方向、位置、比较关系及数字对应的对象必须准确，不得把谁做了什么或数值属于谁翻反。不得增加原文没有的事实、数量、日期或结论。人名、地名、組織機構、公司、品牌、產品、軟體、作品名稱及專業術語必須保持實體身份準確；有通行繁體中文名稱時使用通行中文譯名，沒有可靠譯名時保留原文拼寫，不要臆造音譯或改成其他人物、地點或實體；專有名詞可以保留在中文句子中，周圍普通詞語仍須翻譯；單條字幕只有一個明確的人名、地名、組織、品牌、產品或作品專名且沒有通行譯名時，可以保留原文；由逗號或分號分隔的專名列表也可以保留，其中允許包含多詞名稱，但不能因此保留普通詞語、日常短語或整句英文；同一實體在全片保持一致。数字、日期、时间、数量、单位、货币、百分比、型号和专业数值必须准确保留，不得自行换算或增删。习语、俚语、幽默和反讽应按实际含义及语气自然翻译，不逐字硬译，也不要自行补充解释。目标为繁体中文时，全片统一使用繁体字和自然的中文标点，不要混用简体字。译文简洁自然，适合口语字幕。"

    const val SOURCE_ALIGNMENT_GUIDELINES =
        "Translate each cue faithfully from its own source instead of guessing from the video topic or answering on the speaker's behalf. Preserve the core proposition, participants and their actions, objects, direction, time, quantity, conditions, cause and effect, and speaking intent. Never turn a question into an answer, a command into a reply, or specific information into a generic statement; do not move details from adjacent cues into the current cue. When the source language is auto, use the script and sentence context rather than assuming every Latin fragment is ordinary dialogue; keep source-grounded names and abbreviations when appropriate. When the source is incomplete or ambiguous, translate only what is supported and preserve uncertainty. Never invent names, events, causes, conclusions or background. A cue may be a short or incomplete fragment; translate its available meaning in context without demanding a complete sentence or adding missing words. " +
            "Translate bracketed sound and event captions too, preserving their original brackets: [music] -> [音乐], " +
            "[applause] -> [掌声], [laughter] -> [笑声], [door opens] -> [开门声], [whispering] -> [低语声], " +
            "[glass breaking] -> [玻璃破碎声], [engine starts] -> [发动机启动声], [speaking French] -> [说法语], " +
            "(sighs) -> (叹气声), (crowd cheering) -> (观众欢呼声), [inaudible] -> [听不清], " +
            "[unintelligible] -> [听不清]. Translate unfamiliar but clear sound/event descriptions as captions too; " +
            "the absence of a fixed glossary entry is not a reason to reject them or leave them in the source language. " +
            "Do not leave ordinary English sound labels unchanged. " +
            "Output only the translation, not analysis, explanations, summaries, suggestions or service-status messages."

    /** Complete prompt rules actually sent to a translation model for this target language. */
    fun guidelinesForTarget(targetLanguage: String): String = buildString {
        append(ENGLISH_FIDELITY_GUIDELINES)
        append(SOURCE_ALIGNMENT_GUIDELINES)
        if (SourceLanguagePolicy.isChinese(targetLanguage)) {
            val normalized = SourceLanguagePolicy.normalizeTag(targetLanguage).lowercase(java.util.Locale.ROOT)
            val traditional = normalized.startsWith("zh-hant") ||
                normalized in setOf("zh-tw", "zh-hk", "zh-mo")
            append(
                if (traditional) TRADITIONAL_CHINESE_FIDELITY_GUIDELINES
                else CHINESE_FIDELITY_GUIDELINES
            )
            append(CHINESE_CONTEXT_GUIDELINES)
        }
    }

}
