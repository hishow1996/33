package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextPromptTest {
    @Test
    fun contextIsExplicitlyMarkedAsContinuityOnlyAndKeepsCurrentBatchSeparate() {
        val prompt = buildContextUserPrompt(
            listOf("We will meet Sam tomorrow.", "Use the Orion name.") to
                listOf("我们明天和萨姆见面。", "使用“猎户座”这个名称。"),
            "0|Please continue.",
        ) ?: throw AssertionError("Expected a prompt with prior context")

        assertTrue(prompt.contains("chronological order as JSON"))
        assertTrue(prompt.contains("one aligned pair"))
        assertTrue(prompt.contains("\"source\":\"We will meet Sam tomorrow.\""))
        assertTrue(prompt.contains("\"translation\":\"我们明天和萨姆见面。\""))
        assertTrue(prompt.contains("current source text is authoritative"))
        assertTrue(prompt.contains("Reuse a confirmed Chinese rendering for the same named entity or technical term consistently"))
        assertTrue(prompt.contains("prefer the current source and clearly supported context"))
        assertTrue(prompt.contains("Reuse a confirmed Chinese rendering for the same named entity or technical term consistently"))
        assertTrue(prompt.contains("prefer the current source and clearly supported context"))
        assertTrue(prompt.contains("Do not infer a speaker's identity"))
        assertTrue(prompt.contains("Translate only the current numbered subtitle cues below."))
        assertTrue(prompt.contains("0|Please continue."))
    }

    @Test
    fun absentOrEmptyContextDoesNotAddAContextHeader() {
        assertNull(buildContextUserPrompt(null, "0|Hello"))
        assertNull(buildContextUserPrompt(emptyList<String>() to emptyList(), "0|Hello"))
    }

    @Test
    fun incompletePreviousTranslationsAreExcludedFromContext() {
        val prompt = buildContextUserPrompt(
            listOf("Hello there", "Unresolved cue", "Thank you") to
                listOf("你好", "", "谢谢"),
            "0|How are you?",
        ) ?: throw AssertionError("Expected complete context pairs")

        assertTrue(prompt.contains("\"source\":\"Hello there\""))
        assertTrue(prompt.contains("\"translation\":\"你好\""))
        assertTrue(prompt.contains("\"source\":\"Thank you\""))
        assertTrue(prompt.contains("\"translation\":\"谢谢\""))
        assertFalse(prompt.contains("Unresolved cue"))
        assertTrue(prompt.contains("0|How are you?"))
    }

    @Test
    fun contextIsOmittedWhenEveryPreviousCueIsUntranslated() {
        assertNull(
            buildContextUserPrompt(
                listOf("Unresolved cue") to listOf(" "),
                "0|How are you?",
            ),
        )
    }

    @Test
    fun multilineAndDelimiterTextRemainsInsideItsAlignedJsonPair() {
        val source = "He said \"go\".\nActually, wait => stop."
        val translation = "他说“走”。\n不，等等。"
        val prompt = buildContextUserPrompt(
            listOf(source) to listOf(translation),
            "0|Continue.",
        ) ?: throw AssertionError("Expected context with one complete pair")
        val expectedPair = org.json.JSONArray().put(
            org.json.JSONObject().put("source", source).put("translation", translation)
        ).toString()

        assertTrue(prompt.contains(expectedPair))
        assertTrue(prompt.contains("0|Continue."))
    }

    @Test
    fun chineseContextRulesAvoidUnsupportedSpeakerAndReferentAssumptions() {
        val instructions = SubtitleTranslationPromptPolicy.CHINESE_CONTEXT_GUIDELINES

        assertTrue(instructions.contains("当前字幕原文优先"))
        assertTrue(instructions.contains("不要猜测说话者身份、性别、人物关系"))
        assertTrue(instructions.contains("每行只翻译自己的内容"))
        assertTrue(instructions.contains("不重复前后行"))
    }
    @Test
    fun lookaheadSourceOnlyAddsFollowingCuesWithoutRequiringPriorTranslations() {
        val prompt = buildContextUserPrompt(
            context = null,
            numbered = "0|We should go",
            lookaheadSource = listOf("before the rain", "", "when it starts", "after the call", "ignore this cue"),
        ) ?: throw AssertionError("Expected lookahead context")

        val expectedFollowing = org.json.JSONArray()
            .put("before the rain")
            .put("when it starts")
            .put("after the call")
            .toString()
        assertTrue(prompt.contains("Following cues (source only, for understanding sentence continuation; do not translate or return them):"))
        assertTrue(prompt.contains(expectedFollowing))
        assertFalse(prompt.contains("ignore this cue"))
        assertTrue(prompt.contains("0|We should go"))
    }

    @Test
    fun followingCuesDoNotReplaceAlignedPreviousTranslationContext() {
        val prompt = buildContextUserPrompt(
            context = listOf("I called Alex.") to listOf("我给亚历克斯打了电话。"),
            numbered = "0|He answered.",
            lookaheadSource = listOf("Then we talked."),
        ) ?: throw AssertionError("Expected prior and following context")

        val expectedPrevious = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("source", "I called Alex.")
                .put("translation", "我给亚历克斯打了电话。")
        ).toString()
        val expectedFollowing = org.json.JSONArray().put("Then we talked.").toString()
        assertTrue(prompt.contains(expectedPrevious))
        assertTrue(prompt.contains(expectedFollowing))
        assertTrue(prompt.contains("0|He answered."))
    }

    @Test
    fun keepsEightRecentAlignedRowsAvailableForLongerTermNameAndTermContinuity() {
        val sources = (0 until 10).map { "Source cue $it" }
        val translations = (0 until 10).map { "译文 $it" }
        val prompt = buildContextUserPrompt(
            sources to translations,
            "0|Current cue",
        ) ?: throw AssertionError("Expected a prompt with recent context")

        assertTrue(prompt.contains("Source cue 2"))
        assertTrue(prompt.contains("译文 2"))
        assertTrue(prompt.contains("Source cue 9"))
        assertFalse(prompt.contains("Source cue 1"))
        assertFalse(prompt.contains("译文 1"))
    }

}
