package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextPromptTest {
    @Test
    fun usesOnlyCompletePreviousSourceTranslationPairsAsContext() {
        val prompt = buildContextUserPrompt(
            context = listOf("Hello there", "Unresolved cue", "Thank you") to
                listOf("你好", "", "谢谢"),
            numbered = "0|How are you?",
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Hello there => 你好"))
        assertTrue(prompt.contains("Thank you => 谢谢"))
        assertFalse(prompt.contains("Unresolved cue"))
        assertTrue(prompt.contains("0|How are you?"))
        assertTrue(prompt.contains("reference material only"))
    }

    @Test
    fun omitsContextWhenNoCompleteTranslationPairExists() {
        assertNull(
            buildContextUserPrompt(
                context = listOf("Unresolved cue") to listOf(" "),
                numbered = "0|How are you?",
            ),
        )
    }
}
