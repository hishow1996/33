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
}
