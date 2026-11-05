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
        assertTrue(traditionalPrompt.contains(SubtitleTranslationPromptPolicy.TRADITIONAL_CHINESE_FIDELITY_GUIDELINES))
        assertTrue(traditionalPrompt.contains("全片统一使用繁体字"))
        assertFalse(traditionalPrompt.contains("全片统一使用简体字"))

        listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant-HK").forEach { tag ->
            val regionalTraditional = SubtitleTranslationPromptPolicy.guidelinesForTarget(tag)
            assertTrue("$tag should receive Traditional Chinese rules",
                regionalTraditional.contains("全片统一使用繁体字"))
            assertFalse("$tag must not receive Simplified-only rules",
                regionalTraditional.contains("全片统一使用简体字"))
        }
        listOf("zh", "zh-CN", "zh-Hans", "zh-SG").forEach { tag ->
            val regionalSimplified = SubtitleTranslationPromptPolicy.guidelinesForTarget(tag)
            assertTrue("$tag should receive Simplified Chinese rules",
                regionalSimplified.contains("全片统一使用简体字"))
            assertFalse("$tag must not receive Traditional-only rules",
                regionalSimplified.contains("全片统一使用繁体字"))
        }

        val englishPrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("en")
        assertTrue(englishPrompt.contains(SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES))
    }
    @Test
    fun explicitlyProtectsActionParticipantsDirectionAndUnintroducedFacts() {
        val english = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.lowercase(Locale.ROOT)
        assertTrue(english.contains("who performs an action"))
        assertTrue(english.contains("direction, location, comparisons"))
        assertTrue(english.contains("never add facts, quantities, dates"))

        val simplified = SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES
        assertTrue(simplified.contains("动作执行者、动作对象"))
        assertTrue(simplified.contains("不得增加原文没有的事实"))

        val context = SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES
        assertTrue(context.contains("同一人物、地名、品牌或技术术语"))
        assertTrue(context.contains("不要机械继承错误"))
    }

    @Test
    fun explicitlyTranslatesBracketedSoundCaptionsAndPreservesBrackets() {
        val instructions = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(instructions.contains("[music] -> [音乐]"))
        assertTrue(instructions.contains("[applause] -> [掌声]"))
        assertTrue(instructions.contains("preserving their original brackets"))
    }

    @Test
    fun promptRetainsQuestionAndRequestIntentForChineseTargets() {
        assertTrue(SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES.contains("疑问句不得翻成回答"))
        assertTrue(SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES.contains("请求不得强化成命令"))
        assertTrue(SubtitleTranslationPromptPolicy.TRADITIONAL_CHINESE_FIDELITY_GUIDELINES.contains("疑问句不得翻成回答"))
    }

}
