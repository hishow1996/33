package io.translatetube.local.translate

import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationPromptPolicyTest {
    @Test
    fun preservesMeaningChangingDetailsAndNumericInformation() {
        val instructions = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.lowercase()

        assertTrue(instructions.contains("preserve negation and polarity"))
        assertTrue(instructions.contains("uncertainty"))
        assertTrue(instructions.contains("dates, times, quantities, units, currencies"))
        assertTrue(instructions.contains("established target-language rendering"))
        assertTrue(instructions.contains("idioms, slang, humor and sarcasm"))
    }

    @Test
    fun chineseGuidelinesProtectNegationModalityAndNumbers() {
        val instructions = SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES

        assertTrue(instructions.contains("肯定与否定"))
        assertTrue(instructions.contains("可能、应该、必须、不确定"))
        assertTrue(instructions.contains("存在通行中文译名时使用通行译名"))
        assertTrue(instructions.contains("日期、时间、数量、单位、货币、百分比"))
        assertTrue(instructions.contains("习语、俚语、幽默和反讽"))
    }
}
