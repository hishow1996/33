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
            "Never invent a transliteration or substitute a different person or entity. Preserve source-grounded @handles, " +
            "#hashtags, software/game/work titles, abbreviations and version/model identifiers exactly; translate surrounding " +
            "words without altering, omitting or expanding those identifiers. Proper names may remain embedded in an " +
            "otherwise translated sentence, while ordinary surrounding words must still be translated. A clearly name-shaped " +
            "standalone person, place, brand, organization, product or work name may retain its exact spelling when no established " +
            "target-language name is available. A list of standalone entities may use commas or semicolons and may contain multi-word " +
            "names; do not apply this exception to common words, conversational phrases or sentence fragments. A subtitle may be an incomplete " +
            "fragment cut off by a line boundary: translate every supported word naturally, do not invent the missing ending, " +
            "and do not reject or leave the fragment untranslated merely because it is grammatically incomplete. Retain only " +
            "source-grounded entities inside such fragments. Keep each entity's rendering consistent throughout the video. " +
            "Preserve numbers, dates, times, quantities, units, currencies, percentages, " +
            "model names and technical values exactly. Translate idioms, slang, humor and sarcasm by their intended " +
            "meaning and tone rather than word-for-word, without inventing explanations. " +
            "For Chinese targets, use the requested script consistently: Simplified Chinese for zh/zh-CN/zh-Hans/zh-SG, " +
            "and Traditional Chinese only for zh-Hant/zh-TW/zh-HK/zh-MO. Prefer natural target-language punctuation. " +
            "Keep the result concise and natural for spoken subtitles. "

    /**
     * The single place that tells an API model how to use the reference cues sent with every request.
     * (The user message only labels the cues; the rules live here so they are not repeated per batch.)
     */
    const val CONTEXT_USAGE_GUIDELINES =
        "Use the reference cues (earlier cues with their confirmed translations, and upcoming cues) only to understand the current cues; " +
            "never translate or output them. Keep pronouns, tone and the rendering of every person, place, brand or recurring technical term " +
            "identical to the earlier translations, and do not invent alternate names. If earlier translations conflict with each other " +
            "or with the current source, prefer the current source and clearly supported context instead of copying the conflict. " +
            "The current source text is authoritative: never alter its meaning just to match an earlier translation. " +
            "Do not infer a speaker's identity, gender, relationship or an ambiguous referent unless the source and context clearly support it. " +
            "When a cue is only part of a sentence, read it together with its neighbours to get the meaning right, but output only what " +
            "the current cue itself says, phrased so that the neighbouring translations join naturally: do not pull in words from the " +
            "next cue and do not repeat the previous one. " +
            "Example: the cues `I sat on the bank` and `of the river all day` are about a riverbank, not a financial bank. "

    /** Short target-script reminder; the full script rule is already part of [ENGLISH_FIDELITY_GUIDELINES]. */
    const val SIMPLIFIED_TARGET_NOTE =
        "Write the translation in Simplified Chinese characters with natural Chinese punctuation; never mix in Traditional characters. "
    const val TRADITIONAL_TARGET_NOTE =
        "Write the translation in Traditional Chinese characters with natural Chinese punctuation; never mix in Simplified characters. "

    /**
     * Rules for the on-device HY-MT model. It runs on a phone and re-reads the prompt for every cue, and a small model
     * follows a few short instructions better than a long rulebook, so this is deliberately brief.
     */
    const val HY_MT_RULES =
        "翻译要求：忠实原意，不改变肯定与否定、疑问与陈述和语气；不增加、删减或概括信息；" +
            "数字和专有名词要准确，沿用已确认的译名；被切开的半句话只译当前这一条；使用简体中文，简洁自然，适合口语字幕。"

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

    /** Complete prompt rules actually sent to an API translation model for this target language. */
    fun guidelinesForTarget(targetLanguage: String): String = buildString {
        append(ENGLISH_FIDELITY_GUIDELINES)
        append(SOURCE_ALIGNMENT_GUIDELINES)
        if (SourceLanguagePolicy.isChinese(targetLanguage)) {
            val normalized = SourceLanguagePolicy.normalizeTag(targetLanguage).lowercase(java.util.Locale.ROOT)
            val traditional = normalized.startsWith("zh-hant") ||
                normalized in setOf("zh-tw", "zh-hk", "zh-mo")
            append(if (traditional) TRADITIONAL_TARGET_NOTE else SIMPLIFIED_TARGET_NOTE)
        }
    }

}
