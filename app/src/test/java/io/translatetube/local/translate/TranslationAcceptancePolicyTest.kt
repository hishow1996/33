package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAcceptancePolicyTest {
    @Test
    fun adaptiveAcceptorRejectsTheSameHighConfidenceErrorsAsTheFinalBatchGate() {
        val accept = subtitleTranslationAcceptor("en", "zh-CN")

        assertFalse(accept("I bought a car.", "我卖了一辆车。"))
        assertFalse(accept("I have 3 cats.", "我有3只猫和5只狗。"))
        assertFalse(accept("The software will be updated later.", "這個軟體後續會持續更新。"))

        assertTrue(accept("I bought a car.", "我买了一辆车。"))
        assertTrue(accept("I have 3 cats.", "我有3只猫。"))
    }

    @Test
    fun shortUnchangedNameExceptionCannotBypassNumericOrSemanticChecks() {
        val accept = subtitleShortUntranslatedAcceptor("en", "zh-CN")

        assertTrue(accept("John", "John"))
        assertFalse(accept("H-1B", "H-2B"))
        assertFalse(accept("I bought a car.", "我卖了一辆车。"))
    }

    @Test
    fun ChineseSourceCaptionsAreNotRejectedForRetainingTheirOriginalScript() {
        val accept = subtitleTranslationAcceptor("zh-Hant", "zh-CN")
        assertTrue(accept("這是原始字幕。", "這是原始字幕。"))
    }
}
