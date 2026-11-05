package io.translatetube.local.translate

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationPromptPolicyTest {
    @Test
    fun preservesMeaningChangingDetailsAndNumericInformation() {
        val instructions = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.lowercase(Locale.ROOT)

        assertTrue(instructions.contains("preserve negation and polarity"))
        assertTrue(instructions.contains("uncertainty"))
        assertTrue(instructions.contains("dates, times, quantities, units, currencies"))
        assertTrue(instructions.contains("established target-language rendering"))
        assertTrue(instructions.contains("idioms, slang, humor and sarcasm"))
        assertTrue(instructions.contains("simplified chinese for zh/zh-cn/zh-hans"))
        assertTrue(instructions.contains("traditional chinese only for zh-hant/zh-tw/zh-hk"))
    }

    @Test
    fun selectsSimplifiedOrTraditionalChineseFromTheTargetTag() {
        assertTrue(SubtitleTranslationPromptPolicy.targetLanguageLabel("zh").contains("Simplified"))
        assertTrue(SubtitleTranslationPromptPolicy.targetLanguageLabel("zh-CN").contains("Simplified"))
        assertTrue(SubtitleTranslationPromptPolicy.targetLanguageLabel("zh-Hans").contains("Simplified"))
        assertTrue(SubtitleTranslationPromptPolicy.targetLanguageLabel("zh-Hant").contains("Traditional"))
        assertTrue(SubtitleTranslationPromptPolicy.targetLanguageLabel("zh-TW").contains("Traditional"))
    }

    @Test
    fun chineseGuidelinesProtectNegationModalityAndNumbers() {
        val instructions = SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES

        assertTrue(instructions.contains("肯定与否定"))
        assertTrue(instructions.contains("可能、应该、必须、不确定"))
        assertTrue(instructions.contains("存在通行中文译名时使用通行译名"))
        assertTrue(instructions.contains("日期、时间、数量、单位、货币、百分比"))
        assertTrue(instructions.contains("习语、俚语、幽默和反讽"))
        assertTrue(instructions.contains("全片统一使用简体字"))
    }

    @Test
    fun attachesChineseFidelityAndContextRulesToTheActualChineseTargetPrompt() {
        val chinesePrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES))
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES))
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES))
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES))
        assertTrue(chinesePrompt.contains("不得改变说话者态度"))
        assertTrue(chinesePrompt.contains("每行只翻译自己的内容"))

        val traditionalPrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-Hant")
        assertTrue(traditionalPrompt.contains("Traditional Chinese only for zh-Hant/zh-TW/zh-HK"))

        val englishPrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("en")
        assertTrue(englishPrompt.contains(SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES))
    }
}
