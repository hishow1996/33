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
    fun chineseTargetsGetOneEnglishRulebookPlusAShortScriptNote() {
        val chinesePrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES))
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES))
        assertTrue(chinesePrompt.contains(SubtitleTranslationPromptPolicy.SIMPLIFIED_TARGET_NOTE))
        // 不再重复发送中文版规则：整段提示词不含中文规则文字。
        assertFalse(chinesePrompt.contains("准确性优先"))
        assertFalse(chinesePrompt.contains(SubtitleTranslationPromptPolicy.HY_MT_RULES))

        val traditionalPrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-Hant")
        assertTrue(traditionalPrompt.contains(SubtitleTranslationPromptPolicy.TRADITIONAL_TARGET_NOTE))
        assertFalse(traditionalPrompt.contains(SubtitleTranslationPromptPolicy.SIMPLIFIED_TARGET_NOTE))

        listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant-HK").forEach { tag ->
            val regionalTraditional = SubtitleTranslationPromptPolicy.guidelinesForTarget(tag)
            assertTrue("$tag should receive Traditional Chinese rules",
                regionalTraditional.contains(SubtitleTranslationPromptPolicy.TRADITIONAL_TARGET_NOTE))
            assertFalse("$tag must not receive Simplified-only rules",
                regionalTraditional.contains(SubtitleTranslationPromptPolicy.SIMPLIFIED_TARGET_NOTE))
        }
        listOf("zh", "zh-CN", "zh-Hans", "zh-SG").forEach { tag ->
            val regionalSimplified = SubtitleTranslationPromptPolicy.guidelinesForTarget(tag)
            assertTrue("$tag should receive Simplified Chinese rules",
                regionalSimplified.contains(SubtitleTranslationPromptPolicy.SIMPLIFIED_TARGET_NOTE))
            assertFalse("$tag must not receive Traditional-only rules",
                regionalSimplified.contains(SubtitleTranslationPromptPolicy.TRADITIONAL_TARGET_NOTE))
        }

        val englishPrompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("en")
        assertTrue(englishPrompt.contains(SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.SIMPLIFIED_TARGET_NOTE))
        assertFalse(englishPrompt.contains(SubtitleTranslationPromptPolicy.TRADITIONAL_TARGET_NOTE))
    }

    @Test
    fun contextRulesLiveInOnePlaceAndCoverConflictsSpeakersAndFragments() {
        val rules = SubtitleTranslationPromptPolicy.CONTEXT_USAGE_GUIDELINES
        assertTrue(rules.contains("never translate or output them"))
        assertTrue(rules.contains("do not invent alternate names"))
        assertTrue(rules.contains("prefer the current source and clearly supported context"))
        assertTrue(rules.contains("The current source text is authoritative"))
        assertTrue(rules.contains("Do not infer a speaker's identity"))
        assertTrue(rules.contains("do not pull in words from the next cue"))
    }

    @Test
    fun hyMtRulesStayShortAndKeepTheEssentials() {
        val rules = SubtitleTranslationPromptPolicy.HY_MT_RULES
        assertTrue("HY-MT rules must stay brief, was ${rules.length}", rules.length <= 160)
        assertTrue(rules.contains("肯定与否定"))
        assertTrue(rules.contains("不增加、删减或概括"))
        assertTrue(rules.contains("沿用已确认的译名"))
        assertTrue(rules.contains("简体中文"))
    }

    @Test
    fun explicitlyProtectsActionParticipantsDirectionAndUnintroducedFacts() {
        val english = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.lowercase(Locale.ROOT)
        assertTrue(english.contains("who performs an action"))
        assertTrue(english.contains("direction, location, comparisons"))
        assertTrue(english.contains("never add facts, quantities, dates"))
    }

    @Test
    fun promptProtectsBroaderProperNounCategoriesAndEntityConsistency() {
        val english = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES
        assertTrue(english.contains("organizations, companies, brands, products"))
        assertTrue(english.contains("Never invent a transliteration"))
        assertTrue(english.contains("A list of standalone entities may use commas or semicolons"))
        assertTrue(english.contains("common words, conversational phrases or sentence fragments"))
        assertTrue(english.contains("substitute a different person or entity"))
    }

    @Test
    fun promptAllowsClearlyIdentifiedSingleNamesAndMultiWordEntityListsButNotCommonPhrases() {
        val english = SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES
        assertTrue(english.contains("standalone person, place, brand"))
        assertTrue(english.contains("commas or semicolons"))
        assertTrue(english.contains("multi-word names"))
        assertTrue(english.contains("common words, conversational phrases"))
    }

    @Test
    fun explicitlyTranslatesBracketedSoundCaptionsAndPreservesBrackets() {
        val instructions = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(instructions.contains("[music] -> [音乐]"))
        assertTrue(instructions.contains("[applause] -> [掌声]"))
        assertTrue(instructions.contains("preserving their original brackets"))
    }

    @Test
    fun promptIncludesUnintelligibleSpeechLabelsAndAutoSourceGuidance() {
        val prompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(prompt.contains("[inaudible] -> [听不清]"))
        assertTrue(prompt.contains("[unintelligible] -> [听不清]"))
        assertTrue(prompt.contains("When the source language is auto"))
        assertTrue(prompt.contains("source-grounded names and abbreviations"))
    }

    @Test
    fun promptRetainsQuestionAndRequestIntentForChineseTargets() {
        val prompt = SubtitleTranslationPromptPolicy.guidelinesForTarget("zh-CN")
        assertTrue(prompt.contains("questions versus statements"))
        assertTrue(prompt.contains("commands versus requests"))
        assertTrue(prompt.contains("Never turn a question into an answer"))
    }

}
