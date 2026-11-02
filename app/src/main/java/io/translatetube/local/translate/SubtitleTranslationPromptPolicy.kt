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
            "uncertainty and cause-and-effect. Never strengthen, weaken or reverse the speaker's intent. " +
            "Preserve the identity of names and brands; use an established target-language rendering when one exists " +
            "and keep it consistent. Preserve numbers, dates, times, quantities, units, currencies, percentages, " +
            "model names and technical values exactly. Translate idioms, slang, humor and sarcasm by their intended " +
            "meaning and tone rather than word-for-word, without inventing explanations. " +
            "Keep the result concise and natural for spoken subtitles. "

    const val CHINESE_FIDELITY_GUIDELINES =
        "准确性优先：完整保留肯定与否定、疑问与陈述、命令与请求、条件关系、时态，以及可能、应该、必须、不确定等语气；不得改变说话者态度，不得把不确定翻成肯定。人名、品牌应保持身份准确；存在通行中文译名时使用通行译名，并在全片保持一致。数字、日期、时间、数量、单位、货币、百分比、型号和专业数值必须准确保留，不得自行换算或增删。习语、俚语、幽默和反讽应按实际含义及语气自然翻译，不逐字硬译，也不要自行补充解释。译文简洁自然，适合口语字幕。"
}
