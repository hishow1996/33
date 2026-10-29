package io.translatetube.local.translate

import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptPolicyTest {
    @Test
    fun rulesCoverSplitSentencesAndSpeech() {
        val rules = TranslationPromptPolicy.spokenSubtitleRules("Chinese")
        assertTrue(rules.contains("split across several consecutive lines"))
        assertTrue(rules.contains("one fluent Chinese sentence"))
        assertTrue(rules.contains("full stop"))
        assertTrue(rules.contains("read aloud"))
        // Joined into a system prompt, the rule must end with a space so the next sentence is separate.
        assertTrue(rules.endsWith(" "))
    }
}
