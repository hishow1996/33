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

        assertTrue(prompt.contains("reference material only"))
        assertTrue(prompt.contains("Use their aligned translations as a small terminology and character-name guide"))
        assertTrue(prompt.contains("We will meet Sam tomorrow. => 我们明天和萨姆见面。"))
        assertTrue(prompt.contains("Use the Orion name. => 使用“猎户座”这个名称。"))
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

        assertTrue(prompt.contains("Hello there => 你好"))
        assertTrue(prompt.contains("Thank you => 谢谢"))
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
}
