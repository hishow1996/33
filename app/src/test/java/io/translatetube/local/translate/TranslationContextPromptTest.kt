package io.translatetube.local.translate

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
        )

        assertTrue(prompt!!.contains("Previous cues for continuity"))
        assertTrue(prompt.contains("context only — do not translate or output these"))
        assertTrue(prompt.contains("We will meet Sam tomorrow. => 我们明天和萨姆见面。"))
        assertTrue(prompt.contains("Use the Orion name. => 使用“猎户座”这个名称。"))
        assertTrue(prompt.contains("Now translate:\n0|Please continue."))
    }

    @Test
    fun absentOrEmptyContextDoesNotAddAContextHeader() {
        assertNull(buildContextUserPrompt(null, "0|Hello"))
        assertNull(buildContextUserPrompt(emptyList<String>() to emptyList(), "0|Hello"))
    }
}
